package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs

import com.amazonaws.auth._
import com.amazonaws.services.sqs.model.{MessageAttributeValue, SendMessageBatchRequest, SendMessageBatchRequestEntry}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.snowplowanalytics.snowplow.collectors.scalastream.model._

import java.util.UUID
import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.collection.JavaConverters._
import scala.concurrent.duration.{MILLISECONDS, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Publisher that publishes events to SQS with buffering, retries, and circuit breaker.
  *
  * @param sqsConfig SQS configuration
  * @param bufferConfig Buffer thresholds
  * @param queueUrl SQS queue URL
  * @param queueLabel Label for logging
  * @param executorService Scheduler for flush and health checks
  */
final class SQSPublisher(
  sqsConfig: Kafka.SQS,
  bufferConfig: BufferConfig,
  queueUrl: String,
  queueLabel: String,
  executorService: ScheduledExecutorService
) {
  import SQSPublisher._

  @volatile private var sqsHealthy: Boolean = false
  @volatile private var lastFlushedTime     = 0L
  @volatile private var stopped: Boolean    = false

  private val buffer = new EventBuffer(
    maxSize = sqsConfig.maxBufferSize,
    byteThreshold = bufferConfig.byteLimit,
    recordThreshold = bufferConfig.recordLimit.toInt
  )

  private val retryPolicy = new RetryPolicy(
    minBackoff = sqsConfig.backoffPolicy.minBackoff,
    maxBackoff = sqsConfig.backoffPolicy.maxBackoff,
    maxRetries = sqsConfig.backoffPolicy.maxRetries
  )

  private val circuitBreaker = new CircuitBreaker(
    maxFailures = 5,
    resetTimeout = 60.seconds
  )

  // Separate thread pool for blocking I/O operations (SQS calls)
  private val ioThreadPool = Executors.newFixedThreadPool(
    sqsConfig.threadPoolSize,
    new java.util.concurrent.ThreadFactory {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"sqs-backup-io-$queueLabel-${counter.getAndIncrement()}")
        t.setDaemon(true)
        t
      }
    }
  )
  implicit private val ioEc: ExecutionContext = ExecutionContext.fromExecutor(ioThreadPool)

  private val client = SQSPublisher.buildClient(sqsConfig)

  def start(): Unit = {
    scheduleFlush()
    scheduleSqsHealthCheck()
  }

  def stop(): Unit = {
    // Mark as stopped to prevent scheduled tasks from running
    stopped = true

    // Synchronous final flush to prevent data loss
    val finalBatch = buffer.drain()

    if (finalBatch.nonEmpty) {
      log.info(s"Performing final flush of ${finalBatch.size} events for SQS backup queue $queueLabel")
      try {
        // Synchronous write with timeout
        Await.result(writeBatchToSqs(finalBatch), 30.seconds)
        log.info(s"Final flush completed for SQS backup queue $queueLabel")
      } catch {
        case e: Exception =>
          log.error(s"Final flush failed for SQS backup queue $queueLabel: ${e.getMessage}", e)
      }
    }

    // Shut down resources we own (ioThreadPool and AWS client)
    // The executorService is shared with KafkaSink and will be shut down by its owner
    ioThreadPool.shutdown()
    ioThreadPool.awaitTermination(10, SECONDS)
    client.shutdown()
    ()
  }

  def publish(events: List[Array[Byte]], key: String): Unit =
    events.foreach { payload =>
      val status = buffer.store(payload, key)

      if (status.overflowed) {
        log.warn(s"SQS backup buffer full (${sqsConfig.maxBufferSize} events), dropped 1 event")
      }

      if (status.shouldFlush) {
        // Schedule flush asynchronously to avoid blocking the HTTP request thread
        scheduleImmediateFlush()
      }
    }

  private def scheduleImmediateFlush(): Unit =
    executorService.execute(new Runnable {
      override def run(): Unit = flush()
    })

  def isHealthy: Boolean = sqsHealthy && !circuitBreaker.isOpen && !stopped

  private def flush(): Unit = {
    if (stopped) return // Don't flush after stopped
    val eventsToSend = buffer.drain()
    if (eventsToSend.nonEmpty) {
      lastFlushedTime = System.currentTimeMillis()
      sinkBatch(eventsToSend, retryPolicy.initialBackoff, retryPolicy.maxRetries)
    }
  }

  private def scheduleFlush(interval: Long = bufferConfig.timeLimit): Unit = {
    if (stopped) return // Don't schedule tasks after stopped
    executorService.schedule(
      new Runnable {
        override def run(): Unit = {
          val lastFlushed = lastFlushedTime
          val currentTime = System.currentTimeMillis()
          if (currentTime - lastFlushed >= bufferConfig.timeLimit) {
            flush()
            scheduleFlush(bufferConfig.timeLimit)
          } else {
            scheduleFlush(bufferConfig.timeLimit + lastFlushed - currentTime)
          }
        }
      },
      interval,
      MILLISECONDS
    )
    ()
  }

  private def sinkBatch(batch: List[EventBuffer.Event], nextBackoff: Long, retriesLeft: Int): Unit =
    if (batch.nonEmpty) {
      log.info(
        s"Writing ${batch.size} records to SQS backup queue $queueLabel (circuit: ${circuitBreaker.currentState})"
      )

      circuitBreaker.protect(writeBatchToSqs(batch)).onComplete {
        case Success(retryHints) =>
          sqsHealthy = retryHints.isEmpty
          if (retryHints.nonEmpty) {
            log.warn(s"Retrying ${retryHints.size} records for SQS backup queue $queueLabel")
            handleError(retryHints.map(_._1), nextBackoff, retriesLeft)
          }

        case Failure(CircuitBreaker.CircuitBreakerOpenException(msg)) =>
          log.warn(s"Circuit breaker open for queue $queueLabel: $msg - will retry batch in ${nextBackoff}ms")
          sqsHealthy = false
          val circuitOpenBackoff = Math.max(nextBackoff, CircuitBreakerOpenMinBackoffMs)
          scheduleRetry(batch, circuitOpenBackoff, retriesLeft)

        case Failure(err) =>
          log.error(
            s"Writing ${batch.size} records to SQS backup queue $queueLabel failed: ${err.getMessage}"
          )
          handleError(batch, nextBackoff, retriesLeft)
      }
    }

  /** Schedules a retry of the batch after the specified delay in ms.
    */
  private def scheduleRetry(batch: List[EventBuffer.Event], delay: Long, retriesLeft: Int): Unit = {
    val nextBackoff = retryPolicy.nextBackoff(delay)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = sinkBatch(batch, nextBackoff, retriesLeft)
      },
      delay,
      MILLISECONDS
    )
    ()
  }

  private def writeBatchToSqs(batch: List[EventBuffer.Event]): Future[List[(EventBuffer.Event, BatchResultErrorInfo)]] =
    Future {
      batch
        .grouped(MaxSqsBatchSize)
        .flatMap { chunk =>
          val entries: List[(EventBuffer.Event, SendMessageBatchRequestEntry)] =
            chunk.flatMap { event =>
              val encoded = java.util.Base64.getEncoder.encodeToString(event.payload)
              // Calculate total message size including Base64 payload, kinesisKey attribute, and overhead
              val messageSizeBytes = encoded.length + event.key.length + MessageAttributeOverhead
              if (messageSizeBytes > MaxSqsMessageBytes) {
                log.error(
                  s"Dropping event for SQS backup queue $queueLabel because its total message size " +
                    s"(~$messageSizeBytes bytes including attributes) exceeds the SQS limit of $MaxSqsMessageBytes bytes"
                )
                None
              } else {
                val entry =
                  new SendMessageBatchRequestEntry(UUID.randomUUID().toString, encoded).withMessageAttributes(
                    Map(
                      "kinesisKey" ->
                        new MessageAttributeValue().withDataType("String").withStringValue(event.key)
                    ).asJava
                  )
                Some((event, entry))
              }
            }

          if (entries.isEmpty) {
            List.empty
          } else {
            val result = client.sendMessageBatch(
              new SendMessageBatchRequest().withQueueUrl(queueUrl).withEntries(entries.map(_._2).asJava)
            )
            val failures =
              result.getFailed.asScala.map(f => (f.getId, BatchResultErrorInfo(f.getCode, f.getMessage))).toMap
            entries.collect {
              case (event, entry) if failures.contains(entry.getId) =>
                (event, failures(entry.getId))
            }
          }
        }
        .toList
    }(ioEc) // Use dedicated I/O pool for blocking SQS operations

  private def handleError(batch: List[EventBuffer.Event], curBackoff: Long, retriesLeft: Int): Unit =
    if (retryPolicy.shouldRetry(retriesLeft)) {
      log.debug(s"Scheduling retry of ${batch.size} events in ${curBackoff}ms ($retriesLeft retries left)")
      scheduleRetry(batch, curBackoff, retriesLeft - 1)
    } else {
      log.error(
        s"Maximum retries (${retryPolicy.maxRetries}) exhausted for SQS backup queue $queueLabel. " +
          s"Dropping ${batch.size} events."
      )
      sqsHealthy = false
    }

  private def checkSqsHealth(): Unit =
    try {
      import com.amazonaws.services.sqs.model.GetQueueAttributesRequest
      client.getQueueAttributes(new GetQueueAttributesRequest(queueUrl).withAttributeNames("QueueArn"))
      if (!sqsHealthy) {
        log.info(s"SQS backup queue $queueLabel reachable")
      }
      sqsHealthy = true
    } catch {
      case e: Throwable =>
        if (sqsHealthy) {
          log.warn(
            s"SQS backup queue $queueLabel not reachable: ${e.getMessage}; will retry"
          )
        }
        sqsHealthy = false
    }

  private def scheduleSqsHealthCheck(): Unit = {
    if (stopped) return // Don't schedule health checks after stopped
    executorService.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit =
          if (!stopped) {
            checkSqsHealth()
          }
      },
      0,
      sqsConfig.startupCheckInterval.toMillis,
      MILLISECONDS
    )
    ()
  }
}

object SQSPublisher {
  private val MaxSqsBatchSize = 10
  // SQS has a hard 1 MiB limit per message (including body and attributes)
  private val MaxSqsMessageBytes = 1024 * 1024 // 1 MiB = 1,048,576 bytes
  // Approximate overhead for message attributes (attribute name + value + metadata)
  private val MessageAttributeOverhead = 100 // bytes
  // Minimum backoff when circuit breaker is open (defaulting to 30 seconds)
  private val CircuitBreakerOpenMinBackoffMs = 30000L

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[SQSPublisher])

  final case class BatchResultErrorInfo(code: String, message: String)

  def buildClient(config: Kafka.SQS): AmazonSQS = {
    val provider = config.aws match {
      case AWSConfig("default", "default") => new DefaultAWSCredentialsProviderChain()
      case AWSConfig("iam", "iam")         => InstanceProfileCredentialsProvider.getInstance()
      case AWSConfig("env", "env")         => new EnvironmentVariableCredentialsProvider()
      case AWSConfig(accessKey, secretKey) =>
        new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey))
    }
    AmazonSQSClientBuilder.standard().withRegion(config.region).withCredentials(provider).build()
  }
}
