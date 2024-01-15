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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import scala.concurrent.duration._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import org.http4s.{Method, Request, Uri}

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis
import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http

class CustomPathsSpec extends Specification with Localstack with CatsIO {

  override protected val Timeout = 5.minutes

  "collector" should {
    "map custom paths" in {
      val testName   = "custom-paths"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      val originalPaths = List(
        "/acme/track",
        "/acme/redirect",
        "/acme/iglu"
      )
      val targetPaths = List(
        "/com.snowplowanalytics.snowplow/tp2",
        "/r/tp2",
        "/com.snowplowanalytics.iglu/v1"
      )
      val customPaths = originalPaths.zip(targetPaths)
      val config      = s"""
      {
        "collector": {
          "paths": {
            ${customPaths.map { case (k, v) => s""""$k": "$v"""" }.mkString(",\n")}
          }
        }
      }"""

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(config)
        )
        .use { collector =>
          val requests = originalPaths.map { p =>
            val uri = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}$p")
            Request[IO](Method.POST, uri).withEntity("foo")
          }

          for {
            _               <- Http.statuses(requests)
            _               <- IO.sleep(5.second)
            collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
            outputPaths = collectorOutput.good.map(cp => cp.getPath())
          } yield outputPaths must beEqualTo(targetPaths)
        }
    }
  }
}
