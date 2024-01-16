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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis

import scala.concurrent.duration._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.http4s.{Method, Request, Status, Uri}

import org.specs2.mutable.Specification

import org.testcontainers.containers.GenericContainer

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.{EventGenerator, Http}

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._

class KinesisCollectorSpec extends Specification with Localstack with CatsIO {

  override protected val Timeout = 5.minutes

  val stopTimeout = 20.second

  "collector-kinesis" should {
    "be able to parse the minimal config" in {
      val testName = "minimal"
      Collector
        .container(
          "examples/config.kinesis.minimal.hocon",
          testName,
          s"${testName}-raw",
          s"${testName}-bad-1"
        )
        .use { collector =>
          IO(collector.container.getLogs() must contain("REST interface bound to"))
        }
    }

    "emit the correct number of collector payloads and bad rows" in {
      val testName   = "count"
      val nbGood     = 1000
      val nbBad      = 10
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad
        )
        .use { collector =>
          for {
            _ <- log(testName, "Sending data")
            _ <- EventGenerator.sendEvents(
              collector.host,
              collector.port,
              nbGood,
              nbBad,
              Collector.maxBytes
            )
            _               <- log(testName, "Data sent. Waiting for collector to work")
            _               <- IO.sleep(5.second)
            _               <- log(testName, "Consuming collector's output")
            collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
            _               <- printBadRows(testName, collectorOutput.bad)
          } yield {
            collectorOutput.good.size should beEqualTo(nbGood)
            collectorOutput.bad.size should beEqualTo(nbBad)
          }
        }
    }

    s"shutdown within $stopTimeout when it receives a SIGTERM" in {
      val testName = "stop"
      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          s"${testName}-raw",
          s"${testName}-bad-1"
        )
        .use { collector =>
          val container = collector.container
          for {
            _ <- log(testName, "Sending signal")
            _ <- IO(container.getDockerClient().killContainerCmd(container.getContainerId()).withSignal("TERM").exec())
            _ <- waitWhile[GenericContainer[_]](container, _.isRunning, stopTimeout)
          } yield {
            container.isRunning() must beFalse
            container.getLogs() must contain("Server terminated")
          }
        }
    }

    "start with /sink-health unhealthy and insert pending events when streams become available" in {
      val testName   = "sink-health"
      val nbGood     = 10
      val nbBad      = 10
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          createStreams = false
        )
        .use { collector =>
          val uri     = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}/sink-health")
          val request = Request[IO](Method.GET, uri)

          for {
            statusBeforeCreate <- Http.status(request)
            _ <- EventGenerator.sendEvents(
              collector.host,
              collector.port,
              nbGood,
              nbBad,
              Collector.maxBytes
            )
            _                 <- Localstack.createStreams(List(streamGood, streamBad))
            _                 <- IO.sleep(10.second)
            statusAfterCreate <- Http.status(request)
            collectorOutput   <- Kinesis.readOutput(streamGood, streamBad)
            _                 <- printBadRows(testName, collectorOutput.bad)
          } yield {
            statusBeforeCreate should beEqualTo(Status.ServiceUnavailable)
            statusAfterCreate should beEqualTo(Status.Ok)
            collectorOutput.good.size should beEqualTo(nbGood)
            collectorOutput.bad.size should beEqualTo(nbBad)
          }
        }
    }
  }
}
