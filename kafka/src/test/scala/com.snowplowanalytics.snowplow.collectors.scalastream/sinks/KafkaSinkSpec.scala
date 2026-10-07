package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.model.Kafka
import com.snowplowanalytics.snowplow.collectors.scalastream.model.KafkaTimeouts
import org.apache.kafka.clients.admin.AdminClientConfig
import org.specs2.mutable.Specification

class KafkaSinkSpec extends Specification {

  "KafkaSink" should {
    "reuse producerConf security settings for AdminClient" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:443",
        retries = 10,
        kafkaTimeouts = Some(KafkaTimeouts(requestTimeoutMs = 15000)),
        producerConf = Some(
          Map(
            "security.protocol" -> "SASL_SSL",
            "sasl.mechanism"    -> "SCRAM-SHA-256",
            "sasl.jaas.config" ->
              "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"p\";"
          )
        )
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG) must beEqualTo("example.com:443")
      props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG) must beEqualTo("15000")
      props.getProperty("security.protocol") must beEqualTo("SASL_SSL")
      props.getProperty("sasl.mechanism") must beEqualTo("SCRAM-SHA-256")
      props.getProperty("sasl.jaas.config") must contain("ScramLoginModule")
    }

    "wire AdminClient request timeout from kafkaTimeouts" in {
      val configuredTimeoutMs = 12345
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = Some(KafkaTimeouts(requestTimeoutMs = configuredTimeoutMs)),
        producerConf = None
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG) must beEqualTo(configuredTimeoutMs.toString)
    }

    "use default AdminClient request timeout when kafkaTimeouts are not provided" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = None,
        producerConf = None
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG) must beEqualTo(
        KafkaTimeouts().requestTimeoutMs.toString
      )
    }
  }
}
