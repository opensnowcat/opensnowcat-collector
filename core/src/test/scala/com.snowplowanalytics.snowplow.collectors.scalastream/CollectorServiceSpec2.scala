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
package com.snowplowanalytics.snowplow.collectors.scalastream

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.collectors.scalastream.fixtures.AnalyticsJsFixture
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.apache.thrift.{TDeserializer, TSerializer}
import org.specs2.mutable.Specification

import java.net.InetAddress

// TODO: Move these methods to another class
class CollectorServiceSpec2 extends Specification {
  case class ProbeService(service: CollectorService, good: TestSink, bad: TestSink)

  val service = new CollectorService(
    TestUtils.testConf,
    CollectorSinks(new TestSink, new TestSink),
    "app",
    "version"
  )

  def probeService(): ProbeService = {
    val good = new TestSink
    val bad  = new TestSink
    val s = new CollectorService(
      TestUtils.testConf,
      CollectorSinks(good, bad),
      "app",
      "version"
    )
    ProbeService(s, good, bad)
  }
  def bouncingService(): ProbeService = {
    val good = new TestSink
    val bad  = new TestSink
    val s = new CollectorService(
      TestUtils.testConf.copy(cookieBounce = TestUtils.testConf.cookieBounce.copy(enabled = true)),
      CollectorSinks(good, bad),
      "app",
      "version"
    )
    ProbeService(s, good, bad)
  }
  val uuidRegex    = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".r
  val event        = new CollectorPayload("iglu-schema", "ip", System.currentTimeMillis, "UTF-8", "collector")
  val hs           = List(`Raw-Request-URI`("uri"), `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1"))))
  def serializer   = new TSerializer()
  def deserializer = new TDeserializer()

  "The collector service (analytics.js)" should {
    "cookie" in {
      "accepts a pageView payload" in {
        val body                       = AnalyticsJsFixture.pagePayload.noSpaces
        val ProbeService(s, good, bad) = probeService()
        val response = s.cookie(
          queryString = None,
          body = Option(body),
          path = "v1/p",
          cookie = None,
          userAgent = None,
          refererUri = None,
          hostname = "h",
          ip = RemoteAddress.Unknown,
          request = HttpRequest(),
          pixelExpected = false,
          doNotTrack = false,
          analyticsJsBridge = true
        )

        response.status.isSuccess() must beTrue

        val actualEvent = good.storedRawEvents.head
        val decoded     = new CollectorPayload
        val decoder     = new org.apache.thrift.TDeserializer
        decoder.deserialize(decoded, actualEvent)

//        val actualJson = io.circe.parser.parse(decoded.body).right.get.noSpacesSortKeys
//        val expectedJson = io.circe.parser.parse(body).right.get.noSpacesSortKeys

        val encoder = new org.apache.thrift.TSerializer(new org.apache.thrift.protocol.TSimpleJSONProtocol.Factory)
        val output  = encoder.serialize(decoded)
        println(new String(output))

//        actualJson must be_==(expectedJson)
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
      }
    }
  }
}
