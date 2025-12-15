package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs._

import java.util.concurrent.ScheduledThreadPoolExecutor

/** Kafka sink with SQS failover backup.
  *
  * Tries Kafka first (primary). If Kafka fails, writes to SQS as backup.
  */
final class SQSBackupSink(
  kafkaSink: KafkaSink,
  primaryMaxBytes: Int,
  backup: SQSPublisher
) extends Sink {

  override val maxBytes: Int = primaryMaxBytes

  backup.start()

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit = {
    // Try Kafka first (primary)
    val kafkaResult =
      try {
        kafkaSink.storeRawEvents(events, key)
        Right(())
      } catch {
        case ex: Throwable =>
          SQSBackupSink
            .log
            .warn(
              s"Kafka write failed for ${events.size} events, failing over to SQS backup",
              ex
            )
          Left(ex)
      }

    kafkaResult match {
      case Right(_) =>
        SQSBackupSink.log.debug(s"Successfully wrote ${events.size} events to Kafka")

      case Left(kafkaError) =>
        SQSBackupSink
          .log
          .info(
            s"Kafka unavailable, writing ${events.size} events to SQS backup for recovery"
          )

        try {
          backup.mirror(events, key)
          SQSBackupSink
            .log
            .info(
              s"Successfully wrote ${events.size} events to SQS backup - can recover when Kafka returns"
            )
        } catch {
          case sqsError: Throwable =>
            SQSBackupSink
              .log
              .error(
                s"BOTH Kafka and SQS backup failed for ${events.size} events - data loss!",
                sqsError
              )
            throw new RuntimeException(
              s"Both Kafka and SQS backup write failed. Kafka: ${kafkaError.getMessage}, SQS: ${sqsError.getMessage}",
              kafkaError
            )
        }
    }
  }

  override def isHealthy: Boolean = {
    val healthy = kafkaSink.isHealthy || backup.isHealthy

    if (!kafkaSink.isHealthy && backup.isHealthy) {
      SQSBackupSink
        .log
        .warn(
          "Kafka unhealthy but SQS backup is healthy - operating in failover mode (SQS only)"
        )
    } else if (kafkaSink.isHealthy && !backup.isHealthy) {
      SQSBackupSink.log.debug("Kafka healthy, SQS backup status doesn't affect health")
    }

    healthy
  }

  def backupHealthy: Boolean = backup.isHealthy

  override def shutdown(): Unit = {
    kafkaSink.shutdown()
    backup.stop()
  }
}

object SQSBackupSink {
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[SQSBackupSink])

  def create(
    kafkaSink: KafkaSink,
    sqsConf: Kafka.SQS,
    bufferConfig: BufferConfig,
    queueUrl: String,
    queueLabel: String
  ): SQSBackupSink = {
    val executor = new ScheduledThreadPoolExecutor(sqsConf.threadPoolSize)
    val publisher = new SQSPublisher(
      sqsConf,
      bufferConfig,
      queueUrl,
      queueLabel,
      executor
    )
    new SQSBackupSink(kafkaSink, kafkaSink.maxBytes, publisher)
  }
}
