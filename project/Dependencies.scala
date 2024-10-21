/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
import sbt._

object Dependencies {

  val resolutionRepos = Seq(
    "Snowplow Analytics Maven repo".at("http://maven.snplow.com/releases/").withAllowInsecureProtocol(true),
    // For uaParser utils
    "user-agent-parser repo".at("https://clojars.org/repo/")
  )

  object V {
    // Java
    val awsSdk      = "1.12.767"
    val pubsub      = "1.132.0"
    val kafka       = "3.8.0"
    val mskAuth     = "2.2.0"
    val nsqClient   = "1.3.0"
    val jodaTime    = "2.12.7"
    val slf4j       = "2.0.16"
    val log4j       = "2.23.1"
    val config      = "1.4.3"
    val rabbitMQ    = "5.21.0"
    val jackson     = "2.17.2"
    val thrift      = "0.15.0"
    val jnrUnixsock = "0.38.22"
    val protobuf    = "3.25.4"

    // Scala
    val collectorPayload = "0.0.0"
    val tracker          = "1.0.1"
    val akkaHttp         = "10.2.7"
    val akka             = "2.6.16"
    val scopt            = "4.1.0"
    val pureconfig       = "0.17.7"
    val akkaHttpMetrics  = "1.7.1"
    val badRows          = "2.1.2"
    val circeParser      = "0.14.2"

    // Scala (test only)
    val specs2         = "4.20.8"
    val specs2CE       = "0.5.4"
    val testcontainers = "0.41.4"
    val catsRetry      = "2.1.1"
    val http4s         = "0.21.34"
  }

  object Libraries {
    // Java
    val jackson = "com.fasterxml.jackson.core" % "jackson-databind"     % V.jackson // nsq only
    val thrift  = "org.apache.thrift"          % "libthrift"            % V.thrift
    val kinesis = "com.amazonaws"              % "aws-java-sdk-kinesis" % V.awsSdk
    val sqs     = "com.amazonaws"              % "aws-java-sdk-sqs"     % V.awsSdk
    val sts =
      "com.amazonaws" % "aws-java-sdk-sts" % V.awsSdk % Runtime // Enables web token authentication https://github.com/snowplow/stream-collector/issues/169
    val pubsub       = "com.google.cloud" % "google-cloud-pubsub" % V.pubsub
    val kafkaClients = "org.apache.kafka" % "kafka-clients"       % V.kafka
    val mskAuth =
      "software.amazon.msk" % "aws-msk-iam-auth" % V.mskAuth % Runtime // Enables AWS MSK IAM authentication https://github.com/snowplow/stream-collector/pull/214
    val nsqClient      = "com.snowplowanalytics"    % "nsq-java-client"  % V.nsqClient
    val jodaTime       = "joda-time"                % "joda-time"        % V.jodaTime
    val slf4j          = "org.slf4j"                % "slf4j-simple"     % V.slf4j
    val log4jOverSlf4j = "org.slf4j"                % "log4j-over-slf4j" % V.slf4j
    val log4j          = "org.apache.logging.log4j" % "log4j-core"       % V.log4j
    val config         = "com.typesafe"             % "config"           % V.config
    val jnrUnixsocket  = "com.github.jnr"           % "jnr-unixsocket"   % V.jnrUnixsock
    val rabbitMQ       = "com.rabbitmq"             % "amqp-client"      % V.rabbitMQ
    val protobuf       = "com.google.protobuf"      % "protobuf-java"    % V.protobuf

    // Scala
    val collectorPayload = "com.snowplowanalytics"  % "collector-payload-1"               % V.collectorPayload
    val badRows          = "com.snowplowanalytics" %% "snowplow-badrows"                  % V.badRows
    val trackerCore      = "com.snowplowanalytics" %% "snowplow-scala-tracker-core"       % V.tracker
    val trackerEmitterId = "com.snowplowanalytics" %% "snowplow-scala-tracker-emitter-id" % V.tracker
    val scopt            = "com.github.scopt"      %% "scopt"                             % V.scopt
    val akkaHttp         = "com.typesafe.akka"     %% "akka-http"                         % V.akkaHttp
    val akkaStream       = "com.typesafe.akka"     %% "akka-stream"                       % V.akka
    val akkaSlf4j        = "com.typesafe.akka"     %% "akka-slf4j"                        % V.akka
    val pureconfig       = "com.github.pureconfig" %% "pureconfig"                        % V.pureconfig
    val akkaHttpMetrics  = "fr.davit"              %% "akka-http-metrics-datadog"         % V.akkaHttpMetrics
    val circeParser      = "io.circe"              %% "circe-parser"                      % V.circeParser

    // Scala (test only)
    val specs2            = "org.specs2"        %% "specs2-core"                % V.specs2         % Test
    val specs2It          = "org.specs2"        %% "specs2-core"                % V.specs2         % IntegrationTest
    val specs2CEIt        = "com.codecommit"    %% "cats-effect-testing-specs2" % V.specs2CE       % IntegrationTest
    val testcontainersIt  = "com.dimafeng"      %% "testcontainers-scala-core"  % V.testcontainers % IntegrationTest
    val catsRetryIt       = "com.github.cb372"  %% "cats-retry"                 % V.catsRetry      % IntegrationTest
    val http4sClientIt    = "org.http4s"        %% "http4s-blaze-client"        % V.http4s         % IntegrationTest
    val akkaTestkit       = "com.typesafe.akka" %% "akka-testkit"               % V.akka           % Test
    val akkaHttpTestkit   = "com.typesafe.akka" %% "akka-http-testkit"          % V.akkaHttp       % Test
    val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit"        % V.akka           % Test
  }
}
