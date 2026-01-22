/** Copyright (c) 2014-2022 Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Apache License Version 2.0,
  * and you may not use this file except in compliance with the Apache
  * License Version 2.0.
  * You may obtain a copy of the Apache License Version 2.0 at
  * http://www.apache.org/licenses/LICENSE-2.0.
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the Apache License Version 2.0 is distributed
  * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  * either express or implied.
  *
  * See the Apache License Version 2.0 for the specific language
  * governing permissions and limitations there under.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.config

import com.snowplowanalytics.snowplow.collectors.scalastream.Collector
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.specs2.mutable.Specification
import org.specs2.specification.core.{Fragment, Fragments}

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

abstract class ConfigSpec extends Specification {

  def configRefFactory(app: String): CollectorConfig = CollectorConfig(
    interface = "0.0.0.0",
    port = 8080,
    paths = Map.empty[String, String],
    p3p = P3PConfig(
      policyRef = "/w3c/p3p.xml",
      CP = "NOI DSP COR NID PSA OUR IND COM NAV STA"
    ),
    crossDomain = CrossDomainConfig(
      enabled = false,
      domains = List("*"),
      secure = true
    ),
    cookie = CookieConfig(
      enabled = true,
      expiration = 365.days,
      name = "sp",
      domains = None,
      fallbackDomain = None,
      secure = true,
      httpOnly = true,
      sameSite = Some("None")
    ),
    doNotTrackCookie = DoNotTrackCookieConfig(
      enabled = false,
      name = "",
      value = ""
    ),
    cookieBounce = CookieBounceConfig(
      enabled = false,
      name = "n3pc",
      fallbackNetworkUserId = "00000000-0000-4000-A000-000000000000",
      forwardedProtocolHeader = None
    ),
    redirectMacro = RedirectMacroConfig(
      enabled = false,
      placeholder = None
    ),
    rootResponse = RootResponseConfig(
      enabled = false,
      statusCode = 302,
      headers = Map.empty[String, String],
      body = ""
    ),
    cors = CORSConfig(60.minutes),
    monitoring = MonitoringConfig(MetricsConfig(StatsdConfig(false, "localhost", 8125, 10.seconds))),
    telemetry = Some(TelemetryConfig()),
    ssl = SSLConfig(enable = false, redirect = false, port = 443),
    enableDefaultRedirect = false,
    redirectDomains = Set.empty,
    terminationDeadline = 10.seconds,
    preTerminationPeriod = 10.seconds,
    preTerminationUnhealthy = false,
    streams = StreamsConfig(
      good = "good",
      bad = "bad",
      useIpAddressAsPartitionKey = false,
      buffer =
        if (app == "pubsub")
          BufferConfig(
            byteLimit = 100000,
            recordLimit = 40,
            timeLimit = 1000
          )
        else
          BufferConfig(
            byteLimit = 3145728,
            recordLimit = 500,
            timeLimit = 5000
          ),
      sink = sinkConfigRefFactory(app)
    ),
    experimental = ExperimentalConfig(WarmupConfig(false, 2000, 2000, 3))
  )

  def sinkConfigRefFactory(app: String): SinkConfig = app match {
    case "nsq"   => Nsq(maxBytes = 1000000, "nsqHost", 4150)
    case "kafka" => Kafka(
      maxBytes = 1000000,
      brokers = "localhost:9092,another.host:9092",
      retries = 10,
      producerConf = None,
      threadPoolSize = 10
    )
    case "pubsub" =>
      GooglePubSub(
        maxBytes = 10000000,
        googleProjectId = "google-project-id",
        backoffPolicy = GooglePubSubBackoffPolicyConfig(
          minBackoff = 1000,
          maxBackoff = 1000,
          totalBackoff = 9223372036854L,
          multiplier = 2,
          initialRpcTimeout = 10000,
          maxRpcTimeout = 10000,
          rpcTimeoutMultiplier = 2
        ),
        startupCheckInterval = 1.second,
        retryInterval = 10.seconds,
        gcpUserAgent = GcpUserAgent(productName = "Snowplow OSS")
      )
    case "sqs" =>
      Sqs(
        maxBytes = 192000,
        region = "eu-central-1",
        threadPoolSize = 10,
        aws = AWSConfig(
          accessKey = "iam",
          secretKey = "iam"
        ),
        backoffPolicy = SqsBackoffPolicyConfig(
          minBackoff = 500,
          maxBackoff = 1500,
          maxRetries = 3
        ),
        startupCheckInterval = 1.second
      )
    case "stdout" => Stdout(maxBytes = 1000000000)
    case "kinesis" =>
      Kinesis(
        maxBytes = 1000000,
        region = "eu-central-1",
        threadPoolSize = 10,
        aws = AWSConfig(
          accessKey = "iam",
          secretKey = "iam"
        ),
        backoffPolicy = KinesisBackoffPolicyConfig(
          minBackoff = 500,
          maxBackoff = 1500,
          maxRetries = 3
        ),
        sqsBadBuffer = None,
        sqsGoodBuffer = None,
        sqsMaxBytes = 192000,
        customEndpoint = None,
        startupCheckInterval = 1.second
      )
  }

  def makeConfigTest(app: String, appVer: String, scalaVer: String): Fragments = {
    object stubCollector extends Collector {
      def appName      = app
      def appVersion   = appVer
      def scalaVersion = scalaVer
    }

    "Config.parseConfig" >> Fragment.foreach(
      Seq(("minimal", app), ("extended", app))
    ) { case (suffix, app) =>
      s"accept example $suffix $app config" >> {
        val config      = Paths.get(getClass.getResource(s"/config.$app.$suffix.hocon").toURI)
        val argv        = Array("--config", config.toString)
        val (result, _) = stubCollector.parseConfig(argv)
        val expected = (app, suffix) match {
          case ("kafka", "extended") =>
            // Extended Kafka config includes sqs configuration and kafkaTimeouts
            configRefFactory(app).copy(
              streams = configRefFactory(app)
                .streams
                .copy(
                  sink = configRefFactory(app)
                    .streams
                    .sink
                    .asInstanceOf[Kafka]
                    .copy(
                      kafkaTimeouts = Some(
                        KafkaTimeouts(
                          maxBlockMs = 5000,
                          requestTimeoutMs = 5000,
                          deliveryTimeoutMs = 10000,
                          metadataMaxAgeMs = 5000
                        )
                      ),
                      sqs = Some(
                        Kafka.SQS(
                          region = "us-east-1",
                          threadPoolSize = 10,
                          aws = AWSConfig("iam", "iam"),
                          backoffPolicy = SqsBackoffPolicyConfig(500, 5000, 5),
                          startupCheckInterval = 5.seconds,
                          goodQueueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/good-events",
                          badQueueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/bad-events",
                          maxBufferSize = 50000
                        )
                      )
                    )
                )
            )
          case _ =>
            configRefFactory(app)
        }
        (result must be).equalTo(expected)
      }
    }
  }
}
