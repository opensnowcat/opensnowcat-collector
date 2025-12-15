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
import org.apache.kafka.clients.producer._

import java.util.Properties

/** Kafka Sink for the Scala Stream Collector
  */
class KafkaSink(
  val maxBytes: Int,
  kafkaConfig: Kafka,
  bufferConfig: BufferConfig,
  topicName: String
) extends Sink {

  private val kafkaProducer     = createProducer
  @volatile private var healthy = true

  /** Creates a new Kafka Producer with the given
    * configuration options
    *
    * @return a new Kafka Producer
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

    // Can't use `putAll` in JDK 11 because of https://github.com/scala/bug/issues/10418
    kafkaConfig.producerConf.getOrElse(Map()).foreach { case (k, v) => props.setProperty(k, v) }

    new KafkaProducer[String, Array[Byte]](props)
  }

  /** Store raw events to the topic
    *
    * @param events The list of events to send
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit = {
    log.debug(s"Writing ${events.size} Thrift records to Kafka topic $topicName at key $key")
    events.foreach { event =>
      kafkaProducer.send(
        new ProducerRecord(topicName, key, event),
        new Callback {
          override def onCompletion(metadata: RecordMetadata, e: Exception): Unit =
            if (e != null) {
              healthy = false
              log.error(s"Sending event failed: ${e.getMessage}")
            } else {
              healthy = true
            }
        }
      )
    }
  }

  override def isHealthy: Boolean = healthy

  override def shutdown(): Unit =
    kafkaProducer.close()
}
