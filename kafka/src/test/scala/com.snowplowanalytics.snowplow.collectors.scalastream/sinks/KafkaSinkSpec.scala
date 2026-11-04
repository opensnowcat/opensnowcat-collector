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

    "keep AdminClient bootstrap and timeout settings authoritative over producerConf" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "authoritative-broker:9092",
        retries = 10,
        kafkaTimeouts = Some(KafkaTimeouts(requestTimeoutMs = 15000)),
        producerConf = Some(
          Map(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG  -> "overridden-broker:1234",
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> "99999",
            "security.protocol"                         -> "SASL_SSL"
          )
        )
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG) must beEqualTo("authoritative-broker:9092")
      props.getProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG) must beEqualTo("15000")
      props.getProperty("security.protocol") must beEqualTo("SASL_SSL")
    }

    "wire AdminClient metadata.max.age.ms from kafkaTimeouts" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = Some(KafkaTimeouts(metadataMaxAgeMs = 7000)),
        producerConf = None
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.METADATA_MAX_AGE_CONFIG) must beEqualTo("7000")
    }

    "use the producer default metadata.max.age.ms for AdminClient when kafkaTimeouts are not provided" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = None,
        producerConf = None
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.METADATA_MAX_AGE_CONFIG) must beEqualTo(
        KafkaTimeouts().metadataMaxAgeMs.toString
      )
    }

    "let producerConf override metadata.max.age.ms for AdminClient, as it does for the producer" in {
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = Some(KafkaTimeouts(metadataMaxAgeMs = 7000)),
        producerConf = Some(Map(AdminClientConfig.METADATA_MAX_AGE_CONFIG -> "60000"))
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      props.getProperty(AdminClientConfig.METADATA_MAX_AGE_CONFIG) must beEqualTo("60000")
    }

    "classify producer-only keys" in {
      KafkaSink.isProducerOnlyConfig("batch.size") must beTrue
      KafkaSink.isProducerOnlyConfig("compression.type") must beTrue
      KafkaSink.isProducerOnlyConfig("security.protocol") must beFalse
      KafkaSink.isProducerOnlyConfig("metadata.recovery.strategy") must beFalse
      KafkaSink.isProducerOnlyConfig("custom.plugin.key") must beFalse
    }

    "drop producer-only tuning keys from AdminClient properties and keep shared and custom keys" in {
      val producerOnly = Seq(
        "batch.size",
        "linger.ms",
        "buffer.memory",
        "max.request.size",
        "compression.type",
        "enable.idempotence",
        "max.in.flight.requests.per.connection"
      )
      val conf = Kafka(
        maxBytes = 1000000,
        brokers = "example.com:9092",
        retries = 10,
        kafkaTimeouts = None,
        producerConf = Some(
          Map(
            "batch.size"                            -> "1000000",
            "linger.ms"                             -> "100",
            "buffer.memory"                         -> "128000000",
            "max.request.size"                      -> "64000000",
            "compression.type"                      -> "lz4",
            "enable.idempotence"                    -> "false",
            "max.in.flight.requests.per.connection" -> "1000",
            "client.dns.lookup"                     -> "use_all_dns_ips",
            "metadata.recovery.strategy"            -> "rebootstrap",
            "custom.plugin.key"                     -> "kept"
          )
        )
      )

      val props = KafkaSink.buildAdminClientProperties(conf)

      producerOnly.flatMap(k => Option(props.getProperty(k))) must beEmpty
      props.getProperty("client.dns.lookup") must beEqualTo("use_all_dns_ips")
      props.getProperty("metadata.recovery.strategy") must beEqualTo("rebootstrap")
      props.getProperty("custom.plugin.key") must beEqualTo("kept")
    }
  }
}
