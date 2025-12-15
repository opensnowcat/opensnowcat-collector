package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.util.concurrent.ScheduledThreadPoolExecutor

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs._

/** Kafka sink that mirrors events to SQS for redundancy/backup.
  *
  * Writes events to both Kafka (primary) and SQS (mirror) simultaneously.
  */
final class SQSMirrorSink(
  kafkaSink: KafkaSink,
  primaryMaxBytes: Int,
  mirror: SQSPublisher
) extends Sink {

  override val maxBytes: Int = primaryMaxBytes

  mirror.start()

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit = {
    val kafkaResult =
      try {
        kafkaSink.storeRawEvents(events, key)
        Right(())
      } catch {
        case ex: Throwable =>
          SQSMirrorSink.log.error(s"Kafka write failed for ${events.size} events", ex)
          Left(ex)
      }

    val sqsResult =
      try {
        mirror.mirror(events, key)
        Right(())
      } catch {
        case ex: Throwable =>
          SQSMirrorSink.log.error(s"SQS mirror write failed for ${events.size} events", ex)
          Left(ex)
      }

    (kafkaResult, sqsResult) match {
      case (Right(_), Right(_)) =>
        SQSMirrorSink.log.debug(s"Successfully wrote ${events.size} events to both Kafka and SQS")

      case (Right(_), Left(sqsError)) =>
        SQSMirrorSink
          .log
          .warn(
            s"SQS mirror failed but Kafka succeeded for ${events.size} events - events are safe in Kafka",
            sqsError
          )

      case (Left(kafkaError), Right(_)) =>
        SQSMirrorSink
          .log
          .warn(
            s"Kafka write failed but SQS mirror succeeded for ${events.size} events - events are safe in SQS for recovery",
            kafkaError
          )

      case (Left(kafkaError), Left(sqsError)) =>
        SQSMirrorSink
          .log
          .error(
            s"BOTH Kafka and SQS failed for ${events.size} events - data loss!",
            kafkaError
          )
        throw new RuntimeException(
          s"Both Kafka and SQS write failed. Kafka: ${kafkaError.getMessage}, SQS: ${sqsError.getMessage}",
          kafkaError
        )
    }
  }

  override def isHealthy: Boolean = {
    val healthy = kafkaSink.isHealthy || mirror.isHealthy

    if (!kafkaSink.isHealthy && mirror.isHealthy) {
      SQSMirrorSink.log.warn("Kafka unhealthy but SQS mirror is healthy - operating in SQS-only mode")
    } else if (kafkaSink.isHealthy && !mirror.isHealthy) {
      SQSMirrorSink.log.warn("SQS mirror unhealthy but Kafka is healthy - operating in Kafka-only mode")
    }

    healthy
  }

  def mirrorHealthy: Boolean = mirror.isHealthy

  override def shutdown(): Unit = {
    kafkaSink.shutdown()
    mirror.stop()
  }
}

object SQSMirrorSink {
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[SQSMirrorSink])

  def create(
    kafkaSink: KafkaSink,
    sqsConf: Kafka.SQS,
    bufferConfig: BufferConfig,
    queueUrl: String,
    queueLabel: String
  ): SQSMirrorSink = {
    val executor = new ScheduledThreadPoolExecutor(sqsConf.threadPoolSize)
    val publisher = new SQSPublisher(
      sqsConf,
      bufferConfig,
      queueUrl,
      queueLabel,
      executor
    )
    new SQSMirrorSink(kafkaSink, kafkaSink.maxBytes, publisher)
  }
}
