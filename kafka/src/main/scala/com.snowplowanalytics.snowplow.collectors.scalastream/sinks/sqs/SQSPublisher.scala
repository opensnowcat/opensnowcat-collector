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
    checkSqsHealth()
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
          log.warn(s"Circuit breaker prevented SQS write for queue $queueLabel: $msg")
          sqsHealthy = false
        // Don't retry immediately, circuit breaker will test recovery

        case Failure(err) =>
          log.error(
            s"Writing ${batch.size} records to SQS backup queue $queueLabel failed: ${err.getMessage}"
          )
          handleError(batch, nextBackoff, retriesLeft)
      }
    }

  private def writeBatchToSqs(batch: List[EventBuffer.Event]): Future[List[(EventBuffer.Event, BatchResultErrorInfo)]] =
    Future {
      // SQS has a hard 1 MiB limit per message (including body and attributes).
      // We check the size of the Base64-encoded payload before sending.
      val MaxSqsMessageBytes = 1024 * 1024 // 1 MiB = 1,048,576 bytes

      batch
        .grouped(MaxSqsBatchSize)
        .flatMap { chunk =>
          val entries: List[(EventBuffer.Event, SendMessageBatchRequestEntry)] =
            chunk.flatMap { event =>
              val encoded = java.util.Base64.getEncoder.encodeToString(event.payload)
              // Base64 output is ASCII, so the number of bytes equals encoded.length
              val messageSizeBytes = encoded.length
              if (messageSizeBytes > MaxSqsMessageBytes) {
                log.error(
                  s"Dropping event for SQS backup queue $queueLabel because its encoded size " +
                    s"$messageSizeBytes bytes exceeds the SQS limit of $MaxSqsMessageBytes bytes"
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
      val nextBackoff = retryPolicy.nextBackoff(curBackoff)
      executorService.schedule(
        new Runnable {
          override def run(): Unit = sinkBatch(batch, nextBackoff, retriesLeft - 1)
        },
        curBackoff,
        MILLISECONDS
      )
      ()
    } else {
      log.error(
        s"Maximum retries (${retryPolicy.maxRetries}) exhausted for SQS backup queue $queueLabel; dropping ${batch.size} events"
      )
      sqsHealthy = false
      checkSqsHealth()
    }

  private def checkSqsHealth(): Unit = {
    if (stopped) return // Don't schedule health checks after stopped
    executorService.schedule(
      new Runnable {
        override def run(): Unit =
          try {
            import com.amazonaws.services.sqs.model.GetQueueAttributesRequest
            client.getQueueAttributes(new GetQueueAttributesRequest(queueUrl).withAttributeNames("QueueArn"))
            log.info(s"SQS backup queue $queueLabel reachable")
            sqsHealthy = true
          } catch {
            case e: Throwable =>
              log.warn(
                s"SQS backup queue $queueLabel not reachable: ${e.getMessage}; will retry"
              )
              checkSqsHealth()
          }
      },
      sqsConfig.startupCheckInterval.toMillis,
      MILLISECONDS
    )
    ()
  }
}

object SQSPublisher {
  private val MaxSqsBatchSize = 10
  private val log             = org.slf4j.LoggerFactory.getLogger(classOf[SQSPublisher])

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
