/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs.SQSPublisher
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.clients.producer._

import java.nio.ByteBuffer
import java.util.Properties
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.util.{Failure, Success, Try}

/** Kafka Sink for the Scala Stream Collector.
  * Follows the Kinesis pattern with internal buffering, retry logic, and health checks.
  *
  * @param maxBytes Maximum bytes per event
  * @param kafkaConfig Kafka configuration
  * @param bufferConfig Buffer configuration
  * @param topicName Kafka topic name
  * @param executorService Executor for async operations and health checks
  * @param maybeSqs Optional SQS publisher for failover
  * @param enableHealthCheck Whether to run background health check (only needed for good sink)
  */
class KafkaSink(
  val maxBytes: Int,
  kafkaConfig: Kafka,
  bufferConfig: BufferConfig,
  topicName: String,
  executorService: ScheduledExecutorService,
  maybeSqs: Option[SQSPublisher],
  enableHealthCheck: Boolean = true
) extends Sink {
  import KafkaSink._

  maybeSqs match {
    case Some(_) =>
      log.info(s"SQS backup for Kafka topic $topicName is enabled")
    case None =>
      log.info(s"No SQS backup for Kafka topic $topicName")
  }

  private val ByteThreshold   = bufferConfig.byteLimit
  private val RecordThreshold = bufferConfig.recordLimit
  private val TimeThreshold   = bufferConfig.timeLimit

  private val maxBackoff      = kafkaConfig.backoffPolicy.maxBackoff
  private val minBackoff      = kafkaConfig.backoffPolicy.minBackoff
  private val maxRetries      = kafkaConfig.backoffPolicy.maxRetries
  private val randomGenerator = new java.util.Random()

  private val kafkaProducer = createProducer

  // Separate execution context for non-blocking callbacks
  implicit lazy val ec: ExecutionContextExecutorService =
    scala.concurrent.ExecutionContext.fromExecutorService(executorService)

  // Separate thread pool for blocking Kafka operations to avoid deadlock
  // When all main threads are blocked in latch.await(), we need separate threads for onComplete
  private val blockingExecutor = java
    .util
    .concurrent
    .Executors
    .newCachedThreadPool(new java.util.concurrent.ThreadFactory {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"kafka-blocking-$topicName-${counter.getAndIncrement()}")
        t.setDaemon(true)
        t
      }
    })
  private val blockingEc = scala.concurrent.ExecutionContext.fromExecutor(blockingExecutor)

  @volatile private var kafkaHealthy: Boolean = true

  override def isHealthy: Boolean = kafkaHealthy

  /** Store raw events to internal buffer (non-blocking, returns immediately)
    *
    * @param events The list of events to store
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    events.foreach(e => EventStorage.store(e, key))

  /** Internal event storage buffer.
    * Flushes events when buffer limits are reached or time threshold expires.
    */
  object EventStorage {
    private val storedEvents              = ListBuffer.empty[Events]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity

      synchronized {
        if (storedEvents.size + 1 > RecordThreshold || byteCount + eventSize > ByteThreshold) {
          flush()
        }
        storedEvents += Events(eventBytes.array(), key)
        byteCount += eventSize
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.result()
        storedEvents.clear()
        byteCount = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sinkBatch(eventsToSend)
    }

    def getLastFlushTime: Long = lastFlushedTime

    /** Recursively schedule a task to send everything in EventStorage.
      * Even if the incoming event flow dries up, all stored events will eventually get sent.
      * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
      * @param interval When to schedule the next flush
      */
    def scheduleFlush(interval: Long = TimeThreshold): Unit = {
      executorService.schedule(
        new Runnable {
          override def run(): Unit = {
            val lastFlushed = getLastFlushTime
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlushed >= TimeThreshold) {
              flush()
              scheduleFlush(TimeThreshold)
            } else {
              scheduleFlush(TimeThreshold + lastFlushed - currentTime)
            }
          }
        },
        interval,
        MILLISECONDS
      )
      ()
    }
  }

  /** Send batch to Kafka or SQS based on health status.
    *
    * @param batch List of events to send
    */
  def sinkBatch(batch: List[Events]): Unit =
    if (batch.nonEmpty) maybeSqs match {
      // Kafka healthy - send to Kafka
      case _ if kafkaHealthy =>
        writeBatchToKafkaWithRetries(batch, minBackoff, maxRetries)
      // No SQS backup - always try Kafka
      case None =>
        writeBatchToKafkaWithRetries(batch, minBackoff, maxRetries)
      // Kafka not healthy and SQS backup defined - use SQS
      case Some(sqs) =>
        log.info(s"Kafka unhealthy, routing ${batch.size} records to SQS backup")
        writeBatchToSqs(batch, sqs)
    }

  /** Write batch to Kafka with retry logic.
    *
    * @param batch Events to send
    * @param nextBackoff Next backoff delay
    * @param retriesLeft Number of retries remaining
    */
  def writeBatchToKafkaWithRetries(batch: List[Events], nextBackoff: Long, retriesLeft: Int): Unit = {
    log.info(s"Writing ${batch.size} records to Kafka topic $topicName (${retriesLeft} retries left)")
    writeBatchToKafka(batch).onComplete {
      case Success(failedRecords) =>
        log.debug(s"writeBatchToKafka completed: ${failedRecords.size} failed out of ${batch.size}")
        if (failedRecords.isEmpty) {
          kafkaHealthy = true
          log.info(s"Successfully wrote ${batch.size} records to Kafka topic $topicName")
        } else {
          // Mark Kafka unhealthy immediately on first failure
          // This causes concurrent batches to route directly to SQS without retrying
          // But this batch will still retry in case it's a transient failure
          if (kafkaHealthy) {
            this.synchronized {
              if (kafkaHealthy) {
                log.warn(s"Kafka failure detected, marking as unhealthy. Concurrent batches will route to SQS.")
                kafkaHealthy = false
                checkKafkaHealth()
              }
            }
          }
          log.info(
            s"Successfully wrote ${batch.size - failedRecords.size} out of ${batch.size} records to Kafka topic $topicName"
          )
          handleKafkaError(failedRecords, nextBackoff, retriesLeft)
        }
      case Failure(f) =>
        log.error(s"writeBatchToKafka Future failed with error: ${f.getMessage}", f)
        if (kafkaHealthy) {
          this.synchronized {
            if (kafkaHealthy) {
              log.warn(s"Kafka failure detected, marking as unhealthy. Concurrent batches will route to SQS.")
              kafkaHealthy = false
              checkKafkaHealth()
            }
          }
        }
        handleKafkaError(batch, nextBackoff, retriesLeft)
    }
  }

  /** Handle Kafka write errors with retries.
    * After max retries, marks Kafka as unhealthy and starts background health check.
    *
    * @param failedRecords Events that failed to send
    * @param nextBackoff Next backoff delay
    * @param retriesLeft Number of retries remaining
    */
  def handleKafkaError(failedRecords: List[Events], nextBackoff: Long, retriesLeft: Int): Unit = {
    log.debug(s"handleKafkaError called: ${failedRecords.size} failed records, $retriesLeft retries left")

    // If Kafka has been marked unhealthy by another batch and SQS is available,
    // skip retries and send directly to SQS for fast failover
    if (!kafkaHealthy && maybeSqs.isDefined && retriesLeft > 0) {
      log.info(
        s"Kafka already marked unhealthy by another batch, sending ${failedRecords.size} records directly to SQS (skipping ${retriesLeft} remaining retries)"
      )
      maybeSqs.foreach(sqs => writeBatchToSqs(failedRecords, sqs))
      return
    }

    if (retriesLeft > 0) {
      log.warn(
        s"$retriesLeft retries left. Retrying to write ${failedRecords.size} records to Kafka topic $topicName in $nextBackoff milliseconds"
      )
      scheduleRetryToKafka(failedRecords, nextBackoff, retriesLeft - 1)
    } else {
      log.error(s"Maximum number of retries reached for Kafka topic $topicName for ${failedRecords.size} records")
      // Mark Kafka as unhealthy and start background health check
      // If Kafka was already unhealthy, the background check is already running
      if (kafkaHealthy) {
        this.synchronized {
          if (kafkaHealthy) {
            log.info(s"Marking Kafka as unhealthy and starting background health check")
            kafkaHealthy = false
            checkKafkaHealth()
          }
        }
      }

      // Try to send failed records to SQS if available
      maybeSqs match {
        case Some(sqs) =>
          log.info(s"Sending ${failedRecords.size} failed records to SQS backup after Kafka retries exhausted")
          writeBatchToSqs(failedRecords, sqs)
        case None =>
          log.warn(s"Dropping ${failedRecords.size} records after exhausting retries (no SQS backup configured)")
      }
    }
  }

  /** Write batch to Kafka asynchronously.
    * Waits for all callbacks to complete and returns failed records.
    * Uses separate blocking thread pool to avoid deadlock when all main threads are blocked.
    *
    * @param batch Events to send
    * @return Future containing list of failed events
    */
  def writeBatchToKafka(batch: List[Events]): Future[List[Events]] =
    Future {
      val failedRecords = ListBuffer.empty[Events]
      val latch         = new java.util.concurrent.CountDownLatch(batch.size)

      batch.foreach { event =>
        try kafkaProducer.send(
          new ProducerRecord(topicName, event.key, event.payloads),
          new Callback {
            override def onCompletion(metadata: RecordMetadata, e: Exception): Unit =
              try if (e != null) {
                log.debug(s"Kafka callback: event failed with ${e.getMessage}")
                val _ = failedRecords.synchronized {
                  failedRecords += event
                }
              } finally latch.countDown()
          }
        )
        catch {
          case e: Exception =>
            // Handle synchronous exceptions from send() (e.g., metadata timeout)
            log.debug(s"Kafka send() threw exception: ${e.getMessage}")
            val _ = failedRecords.synchronized {
              failedRecords += event
            }
            latch.countDown()
        }
      }

      // Wait for all callbacks to complete
      val timeouts  = kafkaConfig.kafkaTimeouts.getOrElse(KafkaTimeouts())
      val timeoutMs = timeouts.deliveryTimeoutMs + 5000 // Add buffer

      if (!latch.await(timeoutMs.toLong, TimeUnit.MILLISECONDS)) {
        log.error(s"Timeout waiting for Kafka callbacks after ${timeoutMs}ms - treating all as failed")
        batch // Return all as failed
      } else {
        val result = failedRecords.result()
        log.debug(s"Batch complete: ${batch.size - result.size} succeeded, ${result.size} failed")
        result
      }
    }(blockingEc) // Use separate thread pool to avoid blocking main executorService

  /** Write batch to SQS backup.
    * Converts Events to byte arrays and publishes to SQS.
    *
    * @param batch Events to send to SQS
    * @param sqs SQS publisher
    */
  def writeBatchToSqs(batch: List[Events], sqs: SQSPublisher): Unit = {
    log.info(s"Writing ${batch.size} records to SQS backup (Kafka unhealthy)")
    batch.foreach { event =>
      sqs.publish(List(event.payloads), event.key)
    }
  }

  /** Schedule retry to Kafka after backoff delay.
    *
    * @param failedRecords Events to retry
    * @param currentBackoff Current backoff delay
    * @param retriesLeft Number of retries remaining
    */
  def scheduleRetryToKafka(failedRecords: List[Events], currentBackoff: Long, retriesLeft: Int): Unit = {
    val nextBackoff = getNextBackoff(currentBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = writeBatchToKafkaWithRetries(failedRecords, nextBackoff, retriesLeft)
      },
      currentBackoff,
      MILLISECONDS
    )
    ()
  }

  /** Calculate next backoff delay with randomized jitter.
    *
    * Picks a random delay between minBackoff and maxBackoff, with a floor
    * of 2/3 of the previous backoff to prevent rapid decrease between retries.
    *
    * @param lastBackoff The previous backoff time
    * @return Maximum of two-thirds of lastBackoff and a random number between minBackoff and maxBackoff
    */
  private def getNextBackoff(lastBackoff: Long): Long = {
    val diff = (maxBackoff - minBackoff + 1).toInt
    (minBackoff + randomGenerator.nextInt(diff)).max(lastBackoff / 3 * 2)
  }

  /** Create Kafka producer with configuration.
    *
    * @return Kafka producer instance
    */
  private def createProducer: KafkaProducer[String, Array[Byte]] = {
    log.info(s"Create Kafka Producer to brokers: ${kafkaConfig.brokers}")

    val props = new Properties()
    props.setProperty("bootstrap.servers", kafkaConfig.brokers)
    props.setProperty("acks", "all")
    props.setProperty("retries", kafkaConfig.retries.toString)
    props.setProperty("buffer.memory", bufferConfig.byteLimit.toString)
    props.setProperty("linger.ms", bufferConfig.timeLimit.toString)
    props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")

    // Timeout configurations to prevent blocking when Kafka is down
    val timeouts = kafkaConfig.kafkaTimeouts.getOrElse(KafkaTimeouts())
    props.setProperty("max.block.ms", timeouts.maxBlockMs.toString)
    props.setProperty("request.timeout.ms", timeouts.requestTimeoutMs.toString)
    props.setProperty("delivery.timeout.ms", timeouts.deliveryTimeoutMs.toString)
    props.setProperty("metadata.max.age.ms", timeouts.metadataMaxAgeMs.toString)

    // Can't use `putAll` in JDK 11 because of https://github.com/scala/bug/issues/10418
    kafkaConfig.producerConf.getOrElse(Map()).foreach { case (k, v) => props.setProperty(k, v) }

    new KafkaProducer[String, Array[Byte]](props)
  }

  /** Background health check for Kafka recovery.
    * Checks if Kafka cluster is accessible using Admin API.
    * Runs until Kafka is marked healthy again.
    * Only runs if enableHealthCheck is true (to avoid duplicate checks for good/bad sinks).
    */
  private def checkKafkaHealth(): Unit = if (enableHealthCheck) {
    val healthRunnable = new Runnable {
      override def run(): Unit = {
        log.info(s"Starting background health check for Kafka cluster at ${kafkaConfig.brokers}")

        // Create admin client for health checks
        val adminProps = new Properties()
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.brokers)
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
        val adminClient = AdminClient.create(adminProps)

        try while (!kafkaHealthy)
          Try {
            // Lightweight cluster metadata query - checks if cluster is reachable
            // Don't check specific topics - they may not exist yet (especially bad topic)
            val clusterInfo = adminClient.describeCluster()
            clusterInfo.nodes().get(5, TimeUnit.SECONDS)
          } match {
            case Success(_) =>
              log.info(s"Kafka cluster at ${kafkaConfig.brokers} is accessible - marking Kafka as healthy")
              kafkaHealthy = true
            case Failure(err) =>
              log.warn(s"Kafka cluster at ${kafkaConfig.brokers} not accessible: ${err.getMessage}")
              Thread.sleep(kafkaConfig.startupCheckInterval.toMillis)
          } finally adminClient.close()
      }
    }
    executorService.execute(healthRunnable)
  } else {
    log.info(s"Health check disabled for topic $topicName (using shared cluster health from good sink)")
  }

  override def shutdown(): Unit = {
    // First flush any buffered events so they can be sent to Kafka or SQS as needed
    EventStorage.flush()

    // Then flush and close Kafka producer
    kafkaProducer.flush()
    kafkaProducer.close()

    // Stop and drain the shared executor to ensure all async sends complete
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)

    // Finally shut down the blocking executor
    blockingExecutor.shutdown()
    blockingExecutor.awaitTermination(10000, MILLISECONDS)
    ()
  }
}

/** KafkaSink companion object with factory method */
object KafkaSink {

  /** Events to be written to Kafka.
    * @param payloads Serialized event data
    * @param key Partition key for Kafka
    */
  final case class Events(payloads: Array[Byte], key: String)

  /** Create a KafkaSink and schedule its EventStorage flush task.
    *
    * @param maxBytes Maximum bytes per event
    * @param kafkaConfig Kafka configuration
    * @param bufferConfig Buffer configuration
    * @param topicName Kafka topic name
    * @param executorService Executor for async operations
    * @param maybeSqs Optional SQS publisher for failover
    * @param enableHealthCheck Whether to run background health check
    * @return Initialized KafkaSink
    */
  def createAndInitialize(
    maxBytes: Int,
    kafkaConfig: Kafka,
    bufferConfig: BufferConfig,
    topicName: String,
    executorService: ScheduledExecutorService,
    maybeSqs: Option[SQSPublisher],
    enableHealthCheck: Boolean = true
  ): KafkaSink = {
    val ks = new KafkaSink(
      maxBytes,
      kafkaConfig,
      bufferConfig,
      topicName,
      executorService,
      maybeSqs,
      enableHealthCheck
    )
    // Don't check health at startup - we start healthy
    // Health check will run automatically when Kafka is marked unhealthy
    ks.EventStorage.scheduleFlush()
    ks
  }
}
