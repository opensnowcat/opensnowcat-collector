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
import scala.collection.JavaConverters._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http
import com.snowplowanalytics.snowplow.collectors.scalastream.it.EventGenerator

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis

class DoNotTrackCookieSpec extends Specification with Localstack with CatsIO {

  override protected val Timeout = 5.minutes

  "collector" should {
    val cookieName  = "foo"
    val cookieValue = "bar"

    "ignore events that have a cookie whose name and value match doNotTrackCookie config if enabled" in {
      val testName   = "doNotTrackCookie-enabled"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(mkConfig(true, cookieName, cookieValue))
        )
        .use { collector =>
          val requests = List(
            EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieName),
            EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieValue, cookieValue),
            EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieValue)
          )

          val expected = List(s"Cookie: $cookieName=$cookieName", s"Cookie: $cookieValue=$cookieValue")

          for {
            statuses        <- Http.statuses(requests)
            _               <- IO.sleep(5.second)
            collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
            headers = collectorOutput.good.map(_.headers.asScala)
          } yield {
            statuses.map(_.code) must beEqualTo(List(200, 200, 200))
            headers must haveSize(2)
            expected.forall(cookie => headers.exists(_.contains(cookie))) must beTrue
          }
        }
    }

    "track events that have a cookie whose name and value match doNotTrackCookie config if disabled" in {
      val testName   = "doNotTrackCookie-disabled"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(mkConfig(false, cookieName, cookieValue))
        )
        .use { collector =>
          val request = EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieValue)

          val expected = s"Cookie: $cookieName=$cookieValue"

          for {
            status          <- Http.status(request)
            _               <- IO.sleep(5.second)
            collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
            headers = collectorOutput.good.map(_.headers.asScala)
          } yield {
            status.code must beEqualTo(200)
            headers match {
              case List(one) if one.contains(expected) => ok
              case other =>
                ko(s"$other is not one list that contains [$expected]")
            }
          }
        }
    }
  }

  private def mkConfig(enabled: Boolean, cookieName: String, cookieValue: String): String =
    s"""
      {
        "collector": {
          "doNotTrackCookie": {
            "enabled": $enabled,
            "name" : "$cookieName",
            "value": "$cookieValue"
          }
        }
      }
      """
}
