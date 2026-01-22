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

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    // Check Kafka health first - if unhealthy, skip Kafka entirely and go straight to SQS
    if (!kafkaSink.isHealthy) {
      // Kafka is known to be unhealthy, go straight to SQS backup
      SQSBackupSink.log.debug(s"Kafka unhealthy, routing ${events.size} events directly to SQS backup")
      backup.publish(events, key)
    } else {
      // Kafka is healthy - send to Kafka
      // Events are buffered internally in KafkaSink and sent asynchronously in background
      kafkaSink.storeRawEvents(events, key)
      SQSBackupSink.log.debug(s"Buffered ${events.size} events for Kafka (will be flushed in background)")
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
