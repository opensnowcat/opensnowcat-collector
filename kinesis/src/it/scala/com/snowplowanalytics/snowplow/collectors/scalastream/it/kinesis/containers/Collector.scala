/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

import com.dimafeng.testcontainers.GenericContainer

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collectors.scalastream.generated.ProjectMetadata

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorContainer

object Collector {

  val port     = 8080
  val maxBytes = 10000

  def container(
    configPath: String,
    testName: String,
    streamGood: String,
    streamBad: String,
    createStreams: Boolean = true,
    additionalConfig: Option[String] = None
  ): Resource[IO, CollectorContainer] = {
    val container = GenericContainer(
      dockerImage = s"snowplow/scala-stream-collector-kinesis:${ProjectMetadata.dockerTag}",
      env = Map(
        "AWS_ACCESS_KEY_ID"     -> "whatever",
        "AWS_SECRET_ACCESS_KEY" -> "whatever",
        "PORT"                  -> port.toString,
        "STREAM_GOOD"           -> streamGood,
        "STREAM_BAD"            -> streamBad,
        "REGION"                -> Localstack.region,
        "KINESIS_ENDPOINT"      -> Localstack.privateEndpoint,
        "MAX_BYTES"             -> maxBytes.toString,
        "JDK_JAVA_OPTIONS"      -> "-Dorg.slf4j.simpleLogger.log.com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink=warn"
      ) ++ configParameters(additionalConfig),
      exposedPorts = Seq(port),
      fileSystemBind = Seq(
        GenericContainer.FileSystemBind(
          configPath,
          "/snowplow/config/collector.hocon",
          BindMode.READ_ONLY
        )
      ),
      command = Seq(
        "--config",
        "/snowplow/config/collector.hocon"
      ),
      waitStrategy = Wait.forLogMessage(s".*REST interface bound to.*", 1)
    )
    container.container.withNetwork(Localstack.network)

    val create = if (createStreams) Localstack.createStreams(List(streamGood, streamBad)) else IO.unit

    Resource.make(
      create *>
        IO(startContainerWithLogs(container.container, testName)).map(c =>
          CollectorContainer(c, c.getHost, c.getMappedPort(Collector.port))
        )
    )(c => IO(c.container.stop()))
  }

  private def configParameters(rawConfig: Option[String]): Map[String, String] =
    rawConfig match {
      case None => Map.empty[String, String]
      case Some(config) =>
        val fields = getConfigParameters(config).mkString(" ")
        Map("JDK_JAVA_OPTIONS" -> fields)
    }
}
