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

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.apache.thrift.{TDeserializer, TSerializer}

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import cats.data.NonEmptyList
import io.circe._
import io.circe.parser._
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.{BadRow, Failure, Payload, Processor}
import com.snowplowanalytics.snowplow.collectors.scalastream.fixtures.AnalyticsJsFixture
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.specs2.mutable.Specification

class CollectorServiceSpec extends Specification {
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

  "The collector service" should {
    "cookie" in {
      "attach p3p headers" in {
        val ProbeService(s, good, bad) = probeService()
        val r = s.cookie(
          Some("nuid=12"),
          Some("b"),
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          false
        )
        r.headers must have size 4
        r.headers must contain(
          RawHeader(
            "P3P",
            "policyref=\"%s\", CP=\"%s\"".format("/w3c/p3p.xml", "NOI DSP COR NID PSA OUR IND COM NAV STA")
          )
        )
        r.headers must contain(`Access-Control-Allow-Origin`(HttpOriginRange.`*`))
        r.headers must contain(`Access-Control-Allow-Credentials`(true))
        r.headers.filter(_.toString.startsWith("Set-Cookie")) must have size 1
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
      }
      "not store stuff and provide no cookie if do not track is on" in {
        val ProbeService(s, good, bad) = probeService()
        val r = s.cookie(
          Some("nuid=12"),
          Some("b"),
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          true
        )
        r.headers must have size 3
        r.headers must contain(
          RawHeader(
            "P3P",
            "policyref=\"%s\", CP=\"%s\"".format("/w3c/p3p.xml", "NOI DSP COR NID PSA OUR IND COM NAV STA")
          )
        )
        r.headers must contain(`Access-Control-Allow-Origin`(HttpOriginRange.`*`))
        r.headers must contain(`Access-Control-Allow-Credentials`(true))
        good.storedRawEvents must have size 0
        bad.storedRawEvents must have size 0
      }
      "not set a cookie if SP-Anonymous is present" in {
        val r = service.cookie(
          Some("nuid=12"),
          Some("b"),
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          false,
          None,
          Some("*")
        )

        r.headers.filter(_.toString.startsWith("Set-Cookie")) must have size 0
      }
      "not set a network_userid from cookie if SP-Anonymous is present" in {
        val ProbeService(s, good, bad) = probeService()
        s.cookie(
          None,
          Some("b"),
          "p",
          Some(HttpCookie("sp", "cookie-nuid")),
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          false,
          None,
          Some("*")
        )
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
        val newEvent = new CollectorPayload("iglu-schema", "ip", System.currentTimeMillis, "UTF-8", "collector")
        deserializer.deserialize(newEvent, good.storedRawEvents.head)
        newEvent.networkUserId shouldEqual "00000000-0000-0000-0000-000000000000"
      }
      "network_userid from cookie should persist if SP-Anonymous is not present" in {
        val ProbeService(s, good, bad) = probeService()
        s.cookie(
          None,
          Some("b"),
          "p",
          Some(HttpCookie("sp", "cookie-nuid")),
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          false,
          None,
          None
        )
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
        val newEvent = new CollectorPayload("iglu-schema", "ip", System.currentTimeMillis, "UTF-8", "collector")
        deserializer.deserialize(newEvent, good.storedRawEvents.head)
        newEvent.networkUserId shouldEqual "cookie-nuid"
      }
      "not store stuff if bouncing and provide a location header" in {
        val ProbeService(s, good, bad) = bouncingService()
        val r = s.cookie(
          None,
          Some("b"),
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          true,
          false
        )
        r.headers must have size 6
        r.headers must contain(`Location`("/?bounce=true"))
        r.headers must contain(`Cache-Control`(`no-cache`, `no-store`, `must-revalidate`))
        good.storedRawEvents must have size 0
        bad.storedRawEvents must have size 0
      }
      "store stuff if having already bounced with the fallback nuid" in {
        val ProbeService(s, good, bad) = bouncingService()
        val r = s.cookie(
          Some("bounce=true"),
          Some("b"),
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          true,
          false
        )
        r.headers must have size 5
        r.headers must contain(`Cache-Control`(`no-cache`, `no-store`, `must-revalidate`))
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
        val newEvent = new CollectorPayload("iglu-schema", "ip", System.currentTimeMillis, "UTF-8", "collector")
        deserializer.deserialize(newEvent, good.storedRawEvents.head)
        newEvent.networkUserId shouldEqual "new-nuid"
      }
      "respond with a 200 OK and a bad row in case of illegal querystring" in {
        val ProbeService(s, good, bad) = probeService()
        val r = s.cookie(
          Some("a b"),
          None,
          "p",
          None,
          None,
          None,
          "h",
          RemoteAddress.Unknown,
          HttpRequest(),
          false,
          false
        )
        good.storedRawEvents must have size 0
        bad.storedRawEvents must have size 1
        r.status mustEqual StatusCodes.OK

        val brJson  = parse(new String(bad.storedRawEvents.head, StandardCharsets.UTF_8)).getOrElse(Json.Null)
        val failure = brJson.hcursor.downField("data").downField("failure").downField("errors").downArray.as[String]
        val payload = brJson.hcursor.downField("data").downField("payload").as[String]

        failure must beRight(
          "Illegal query: Invalid input ' ', expected '+', '=', query-char, 'EOI', '&' or pct-encoded (line 1, column 2): a b\n ^"
        )
        payload must beRight("a b")
      }
    }

    "extractQueryParams" in {
      "extract the parameters from a valid querystring" in {
        val qs = Some("a=b&c=d")
        val r  = service.extractQueryParams(qs)

        r shouldEqual Right(Map("a" -> "b", "c" -> "d"))
      }

      "fail on invalid querystring" in {
        val qs = Some("a=b&c=d a")
        val r  = service.extractQueryParams(qs)

        r should beLeft
      }
    }

    "preflightResponse" in {
      "return a response appropriate to cors preflight options requests" in {
        service.preflightResponse(HttpRequest(), CORSConfig(-1.seconds)) shouldEqual HttpResponse().withHeaders(
          List(
            `Access-Control-Allow-Origin`(HttpOriginRange.`*`),
            `Access-Control-Allow-Credentials`(true),
            `Access-Control-Allow-Headers`("Content-Type", "SP-Anonymous"),
            `Access-Control-Max-Age`(-1)
          )
        )
      }
    }

    "flashCrossDomainPolicy" in {
      "return the cross domain policy with the specified config" in {
        service.flashCrossDomainPolicy(CrossDomainConfig(true, List("*"), false)) shouldEqual HttpResponse(
          entity = HttpEntity(
            contentType = ContentType(MediaTypes.`text/xml`, HttpCharsets.`ISO-8859-1`),
            string =
              "<?xml version=\"1.0\"?>\n<cross-domain-policy>\n  <allow-access-from domain=\"*\" secure=\"false\" />\n</cross-domain-policy>"
          )
        )
      }
      "return the cross domain policy with multiple domains" in {
        service.flashCrossDomainPolicy(CrossDomainConfig(true, List("*", "acme.com"), false)) shouldEqual HttpResponse(
          entity = HttpEntity(
            contentType = ContentType(MediaTypes.`text/xml`, HttpCharsets.`ISO-8859-1`),
            string =
              "<?xml version=\"1.0\"?>\n<cross-domain-policy>\n  <allow-access-from domain=\"*\" secure=\"false\" />\n  <allow-access-from domain=\"acme.com\" secure=\"false\" />\n</cross-domain-policy>"
          )
        )
      }
      "return the cross domain policy with no domains" in {
        service.flashCrossDomainPolicy(CrossDomainConfig(true, List.empty, false)) shouldEqual HttpResponse(
          entity = HttpEntity(
            contentType = ContentType(MediaTypes.`text/xml`, HttpCharsets.`ISO-8859-1`),
            string = "<?xml version=\"1.0\"?>\n<cross-domain-policy>\n\n</cross-domain-policy>"
          )
        )
      }
      "return 404 if the specified config is absent" in {
        service.flashCrossDomainPolicy(CrossDomainConfig(false, List("*"), false)) shouldEqual
          HttpResponse(404, entity = "404 not found")
      }
    }

    "rootResponse" in {
      "return the configured response for root requests" in {
        service.rootResponse(
          RootResponseConfig(enabled = true, 302, Map("Location" -> "https://127.0.0.1/"))
        ) shouldEqual HttpResponse(
          302,
          collection.immutable.Seq(RawHeader("Location", "https://127.0.0.1/")),
          entity = ""
        )
      }
      "return the configured response for root requests (no headers)" in {
        service.rootResponse(RootResponseConfig(enabled = true, 302)) shouldEqual HttpResponse(
          302,
          entity = ""
        )
      }
      "return the original 404 if not configured" in {
        service.rootResponse shouldEqual HttpResponse(
          404,
          entity = "404 not found"
        )
      }
    }

    "buildEvent" in {
      "fill the correct values if SP-Anonymous is not present" in {
        val l   = `Location`("l")
        val xff = `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1")))
        val ct  = Some("image/gif")
        val r   = HttpRequest().withHeaders(l :: hs)
        val e   = service.buildEvent(Some("q"), Some("b"), "p", Some("ua"), Some("ref"), "h", "ip", r, "nuid", ct, None)
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "ip"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"app-version-kinesis"
        e.querystring shouldEqual "q"
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "ua"
        e.refererUri shouldEqual "ref"
        e.hostname shouldEqual "h"
        e.networkUserId shouldEqual "nuid"
        e.headers shouldEqual (l.unsafeToString :: xff.unsafeToString :: ct.toList).asJava
        e.contentType shouldEqual ct.get
      }
      "fill the correct values if SP-Anonymous is present" in {
        val l    = `Location`("l")
        val ct   = Some("image/gif")
        val r    = HttpRequest().withHeaders(l :: hs)
        val nuid = service.networkUserId(r, None, Some("*")).get
        val e =
          service.buildEvent(
            Some("q"),
            Some("b"),
            "p",
            Some("ua"),
            Some("ref"),
            "h",
            "unknown",
            r,
            nuid,
            ct,
            Some("*")
          )
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "unknown"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"app-version-kinesis"
        e.querystring shouldEqual "q"
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "ua"
        e.refererUri shouldEqual "ref"
        e.hostname shouldEqual "h"
        e.networkUserId shouldEqual "00000000-0000-0000-0000-000000000000"
        e.headers shouldEqual (l.unsafeToString :: ct.toList).asJava
        e.contentType shouldEqual ct.get
      }
      "have a null queryString if it's None" in {
        val l    = `Location`("l")
        val ct   = Some("image/gif")
        val r    = HttpRequest().withHeaders(l :: hs)
        val nuid = service.networkUserId(r, None, Some("*")).get
        val e =
          service.buildEvent(
            None,
            Some("b"),
            "p",
            Some("ua"),
            Some("ref"),
            "h",
            "unknown",
            r,
            nuid,
            ct,
            Some("*")
          )
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "unknown"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"app-version-kinesis"
        e.querystring shouldEqual null
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "ua"
        e.refererUri shouldEqual "ref"
        e.hostname shouldEqual "h"
        e.networkUserId shouldEqual "00000000-0000-0000-0000-000000000000"
        e.headers shouldEqual (l.unsafeToString :: ct.toList).asJava
        e.contentType shouldEqual ct.get
      }
      "have an empty nuid if SP-Anonymous is present" in {
        val l    = `Location`("l")
        val ct   = Some("image/gif")
        val r    = HttpRequest().withHeaders(l :: hs)
        val nuid = service.networkUserId(r, None, Some("*")).get
        val e =
          service.buildEvent(
            None,
            Some("b"),
            "p",
            Some("ua"),
            Some("ref"),
            "h",
            "unknown",
            r,
            nuid,
            ct,
            Some("*")
          )
        e.networkUserId shouldEqual "00000000-0000-0000-0000-000000000000"
      }
      "have a nuid if SP-Anonymous is not present" in {
        val l  = `Location`("l")
        val ct = Some("image/gif")
        val r  = HttpRequest().withHeaders(l :: hs)
        val e =
          service.buildEvent(None, Some("b"), "p", Some("ua"), Some("ref"), "h", "ip", r, "nuid", ct, None)
        e.networkUserId shouldEqual "nuid"
      }
    }

    "sinkEvent" in {
      "send back the produced events" in {
        val ProbeService(s, good, bad) = probeService()
        s.sinkEvent(event, "key")
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
        good.storedRawEvents.head.zip(serializer.serialize(event)).forall { case (a, b) => a mustEqual b }
      }
    }

    "sinkBad" in {
      "write out the generated bad row" in {
        val br = BadRow.GenericError(
          Processor("", ""),
          Failure.GenericFailure(Instant.now(), NonEmptyList.one("IllegalQueryString")),
          Payload.RawPayload("")
        )
        val ProbeService(s, good, bad) = probeService()
        s.sinkBad(br, "key")

        bad.storedRawEvents must have size 1
        good.storedRawEvents must have size 0
        bad.storedRawEvents.head.zip(br.compact).forall { case (a, b) => a mustEqual b }
      }
    }

    "buildHttpResponse" in {
      val redirConf = TestUtils.testConf.redirectMacro
      val domain    = TestUtils.testConf.redirectDomains.head

      "rely on buildRedirectHttpResponse if redirect is true" in {
        val res = service.buildHttpResponse(event, Map("u" -> s"https://$domain/12"), hs, true, true, false, redirConf)
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"https://$domain/12") :: hs)
      }
      "send back a gif if pixelExpected is true" in {
        val res = service.buildHttpResponse(event, Map.empty, hs, false, true, false, redirConf)
        res shouldEqual HttpResponse(200)
          .withHeaders(hs)
          .withEntity(HttpEntity(contentType = ContentType(MediaTypes.`image/gif`), bytes = CollectorService.pixel))
      }
      "send back a found if pixelExpected and bounce is true" in {
        val res = service.buildHttpResponse(event, Map.empty, hs, false, true, true, redirConf)
        res shouldEqual HttpResponse(302).withHeaders(hs)
      }
      "send back ok otherwise" in {
        val res = service.buildHttpResponse(event, Map.empty, hs, false, false, false, redirConf)
        res shouldEqual HttpResponse(200, entity = "ok").withHeaders(hs)
      }
    }

    "buildUsualHttpResponse" in {
      "send back a found if pixelExpected and bounce is true" in {
        service.buildUsualHttpResponse(true, true) shouldEqual HttpResponse(302)
      }
      "send back a gif if pixelExpected is true" in {
        service.buildUsualHttpResponse(true, false) shouldEqual HttpResponse(200).withEntity(
          HttpEntity(contentType = ContentType(MediaTypes.`image/gif`), bytes = CollectorService.pixel)
        )
      }
      "send back ok otherwise" in {
        service.buildUsualHttpResponse(false, true) shouldEqual HttpResponse(200, entity = "ok")
      }
      "send back the analytics.js supported response" in {
        service.buildUsualHttpResponse(false, true, true) shouldEqual HttpResponse(200, entity = """{"success":true}""")
      }
    }

    "buildRedirectHttpResponse" in {
      val redirConf = TestUtils.testConf.redirectMacro
      val domain    = TestUtils.testConf.redirectDomains.head
      "give back a 302 if redirecting and there is a u query param" in {
        val res = service.buildRedirectHttpResponse(event, Map("u" -> s"http://$domain/12"), redirConf)
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://$domain/12"))
      }
      "give back a 400 if redirecting and there are no u query params" in {
        val res = service.buildRedirectHttpResponse(event, Map.empty, redirConf)
        res shouldEqual HttpResponse(400)
      }
      "the redirect url should ignore a cookie replacement macro on redirect if not enabled" in {
        event.networkUserId = "1234"
        val res =
          service.buildRedirectHttpResponse(event, Map("u" -> s"http://$domain/?uid=$${SP_NUID}"), redirConf)
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://$domain/?uid=$${SP_NUID}"))
      }
      "the redirect url should support a cookie replacement macro on redirect if enabled" in {
        event.networkUserId = "1234"
        val res = service.buildRedirectHttpResponse(
          event,
          Map("u" -> s"http://$domain/?uid=$${SP_NUID}"),
          redirConf.copy(enabled = true)
        )
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://$domain/?uid=1234"))
      }
      "the redirect url should allow for custom token placeholders" in {
        event.networkUserId = "1234"
        val res = service.buildRedirectHttpResponse(
          event,
          Map("u" -> s"http://$domain/?uid=[TOKEN]"),
          redirConf.copy(enabled = true, Some("[TOKEN]"))
        )
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://$domain/?uid=1234"))
      }
      "the redirect url should allow for double encoding for return redirects" in {
        val res =
          service.buildRedirectHttpResponse(event, Map("u" -> s"http://$domain/a%3Db"), redirConf)
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://$domain/a%3Db"))
      }
      "give back a 400 if redirecting to a disallowed domain" in {
        val res = service.buildRedirectHttpResponse(event, Map("u" -> s"http://invalid.acme.com/12"), redirConf)
        res shouldEqual HttpResponse(400)
      }
      "give back a 302 if redirecting to an unknown domain, with no restrictions on domains" in {
        def conf = TestUtils.testConf.copy(redirectDomains = Set.empty)
        val permissiveService = new CollectorService(
          conf,
          CollectorSinks(new TestSink, new TestSink),
          "app",
          "version"
        )
        val res =
          permissiveService.buildRedirectHttpResponse(event, Map("u" -> s"http://unknown.acme.com/12"), redirConf)
        res shouldEqual HttpResponse(302).withHeaders(`RawHeader`("Location", s"http://unknown.acme.com/12"))
      }
    }

    "cookieHeader" in {
      "give back a cookie header with the appropriate configuration" in {
        val nuid = "nuid"
        val conf = CookieConfig(
          true,
          "name",
          5.seconds,
          Some(List("domain")),
          None,
          secure = false,
          httpOnly = false,
          sameSite = None
        )
        val Some(`Set-Cookie`(cookie)) = service.cookieHeader(HttpRequest(), Some(conf), nuid, false, None)

        cookie.name shouldEqual conf.name
        cookie.value shouldEqual nuid
        cookie.domain shouldEqual None
        cookie.path shouldEqual Some("/")
        cookie.expires must beSome
        (cookie.expires.get - DateTime.now.clicks).clicks must beCloseTo(conf.expiration.toMillis, 1000L)
        cookie.secure must beFalse
        cookie.httpOnly must beFalse
        cookie.extension must beEmpty
      }
      "give back None if no configuration is given" in {
        service.cookieHeader(HttpRequest(), None, "nuid", false, None) shouldEqual None
      }
      "give back None if doNoTrack is true" in {
        val conf = CookieConfig(
          true,
          "name",
          5.seconds,
          Some(List("domain")),
          None,
          secure = false,
          httpOnly = false,
          sameSite = None
        )
        service.cookieHeader(HttpRequest(), Some(conf), "nuid", true, None) shouldEqual None
      }
      "give back None if SP-Anonymous header is present" in {
        val conf = CookieConfig(
          true,
          "name",
          5.seconds,
          Some(List("domain")),
          None,
          secure = false,
          httpOnly = false,
          sameSite = None
        )
        service.cookieHeader(HttpRequest(), Some(conf), "nuid", true, Some("*")) shouldEqual None
      }
      "give back a cookie header with Secure, HttpOnly and SameSite=None" in {
        val nuid = "nuid"
        val conf = CookieConfig(
          true,
          "name",
          5.seconds,
          Some(List("domain")),
          None,
          secure = true,
          httpOnly = true,
          sameSite = Some("None")
        )
        val Some(`Set-Cookie`(cookie)) =
          service.cookieHeader(HttpRequest(), Some(conf), networkUserId = nuid, doNotTrack = false, spAnonymous = None)
        cookie.secure must beTrue
        cookie.httpOnly must beTrue
        cookie.extension must beSome("SameSite=None")
        service.cookieHeader(HttpRequest(), Some(conf), nuid, true, None) shouldEqual None
      }
    }

    "bounceLocationHeader" in {
      "build a location header if bounce is true" in {
        val header = service.bounceLocationHeader(
          Map("a" -> "b"),
          HttpRequest().withUri(Uri("st")),
          CookieBounceConfig(true, "bounce", "", None),
          true
        )
        header shouldEqual Some(`Location`("st?a=b&bounce=true"))
      }
      "give back none otherwise" in {
        val header = service.bounceLocationHeader(
          Map("a" -> "b"),
          HttpRequest().withUri(Uri("st")),
          CookieBounceConfig(false, "bounce", "", None),
          false
        )
        header shouldEqual None
      }
      "use forwarded protocol header if present and enabled" in {
        val header = service.bounceLocationHeader(
          Map("a" -> "b"),
          HttpRequest().withUri(Uri("http://st")).addHeader(RawHeader("X-Forwarded-Proto", "https")),
          CookieBounceConfig(true, "bounce", "", Some("X-Forwarded-Proto")),
          true
        )
        header shouldEqual Some(`Location`("https://st?a=b&bounce=true"))
      }
      "allow missing forwarded protocol header if forward header is enabled but absent" in {
        val header = service.bounceLocationHeader(
          Map("a" -> "b"),
          HttpRequest().withUri(Uri("http://st")),
          CookieBounceConfig(true, "bounce", "", Some("X-Forwarded-Proto")),
          true
        )
        header shouldEqual Some(`Location`("http://st?a=b&bounce=true"))
      }
    }

    "headers" in {
      "filter out the correct headers if SP-Anonymous is not present" in {
        val request = HttpRequest()
          .withHeaders(
            List(
              `Location`("a"),
              `Raw-Request-URI`("uri")
            )
          )
          .withAttributes(
            Map(AttributeKeys.remoteAddress -> RemoteAddress.Unknown)
          )
        service.headers(request, None) shouldEqual List(`Location`("a").unsafeToString)
      }
      "filter out the correct headers if SP-Anonymous is present" in {
        val request = HttpRequest()
          .withHeaders(
            List(
              `Location`("a"),
              `Raw-Request-URI`("uri"),
              `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1"))),
              `X-Real-Ip`(RemoteAddress(InetAddress.getByName("127.0.0.1"))),
              `Cookie`(
                "_sp_id.dc78",
                "82dd4038-e749-4f9c-b502-d54a3611cc89.1598608039.19.1605281535.1604957469.5a2d5fe4-6323-4414-9bf0-9867a940d53b"
              )
            )
          )
          .withAttributes(
            Map(AttributeKeys.remoteAddress -> RemoteAddress.Unknown)
          )
        service.headers(request, Some("*")) shouldEqual List(`Location`("a").unsafeToString)
      }
    }

    "ipAndPartitionkey" in {
      "give back the ip and partition key as ip if remote address is defined" in {
        val address = RemoteAddress(InetAddress.getByName("localhost"))
        service.ipAndPartitionKey(address, true) shouldEqual (("127.0.0.1", "127.0.0.1"))
      }
      "give back the ip and a uuid as partition key if ipAsPartitionKey is false" in {
        val address    = RemoteAddress(InetAddress.getByName("localhost"))
        val (ip, pkey) = service.ipAndPartitionKey(address, false)
        ip shouldEqual "127.0.0.1"
        pkey must beMatching(uuidRegex)
      }
      "give back unknown as ip and a random uuid as partition key if the address isn't known" in {
        val (ip, pkey) = service.ipAndPartitionKey(RemoteAddress.Unknown, true)
        ip shouldEqual "unknown"
        pkey must beMatching(uuidRegex)
      }
    }

    "netwokUserId" in {
      "with SP-Anonymous header not present" in {
        "give back the nuid query param if present" in {
          service.networkUserId(
            HttpRequest().withUri(Uri().withRawQueryString("nuid=12")),
            Some(HttpCookie("nuid", "13")),
            None
          ) shouldEqual Some("12")
        }
        "give back the request cookie if there no nuid query param" in {
          service.networkUserId(HttpRequest(), Some(HttpCookie("nuid", "13")), None) shouldEqual Some("13")
        }
        "give back none otherwise" in {
          service.networkUserId(HttpRequest(), None, None) shouldEqual None
        }
      }

      "with SP-Anonymous header present" in {
        "give back the dummy nuid" in {
          "if query param is present" in {
            service.networkUserId(
              HttpRequest().withUri(Uri().withRawQueryString("nuid=12")),
              Some(HttpCookie("nuid", "13")),
              Some("*")
            ) shouldEqual Some("00000000-0000-0000-0000-000000000000")
          }
          "if the request cookie can be used in place of a missing nuid query param" in {
            service.networkUserId(HttpRequest(), Some(HttpCookie("nuid", "13")), Some("*")) shouldEqual Some(
              "00000000-0000-0000-0000-000000000000"
            )
          }
          "in any other case" in {
            service.networkUserId(HttpRequest(), None, Some("*")) shouldEqual Some(
              "00000000-0000-0000-0000-000000000000"
            )
          }
        }
      }
    }

    "accessControlAllowOriginHeader" in {
      "give a restricted ACAO header if there is an Origin header in the request" in {
        val origin  = HttpOrigin("http", Host("origin"))
        val request = HttpRequest().withHeaders(`Origin`(origin))
        service.accessControlAllowOriginHeader(request) shouldEqual
          `Access-Control-Allow-Origin`(HttpOriginRange.Default(List(origin)))
      }
      "give an open ACAO header if there are no Origin headers in the request" in {
        val request = HttpRequest()
        service.accessControlAllowOriginHeader(request) shouldEqual
          `Access-Control-Allow-Origin`(HttpOriginRange.`*`)
      }
    }

    "cookieDomain" in {
      "not return a domain" in {
        "if a list of domains is not supplied in the config and there is no fallback domain" in {
          val request      = HttpRequest()
          val cookieConfig = CookieConfig(true, "name", 5.seconds, None, None, false, false, None)
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
        "if a list of domains is supplied in the config but the Origin request header is empty and there is no fallback domain" in {
          val request      = HttpRequest()
          val cookieConfig = CookieConfig(true, "name", 5.seconds, Some(List("domain.com")), None, false, false, None)
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
        "if none of the domains in the request's Origin header has a match in the list of domains supplied with the config and there is no fallback domain" in {
          val origins = Seq(HttpOrigin("http", Host("origin.com")), HttpOrigin("http", Host("otherorigin.com", 8080)))
          val request = HttpRequest().withHeaders(`Origin`(origins))
          val cookieConfig =
            CookieConfig(true, "name", 5.seconds, Some(List("domain.com", "otherdomain.com")), None, false, false, None)
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
      }
      "return the fallback domain" in {
        "if a list of domains is not supplied in the config but a fallback domain is configured" in {
          val request      = HttpRequest()
          val cookieConfig = CookieConfig(true, "name", 5.seconds, None, Some("fallbackDomain"), false, false, None)
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
        "if the Origin header is empty and a fallback domain is configured" in {
          val request = HttpRequest()
          val cookieConfig =
            CookieConfig(true, "name", 5.seconds, Some(List("domain.com")), Some("fallbackDomain"), false, false, None)
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
        "if none of the domains in the request's Origin header has a match in the list of domains supplied with the config but a fallback domain is configured" in {
          val origins = Seq(HttpOrigin("http", Host("origin.com")), HttpOrigin("http", Host("otherorigin.com", 8080)))
          val request = HttpRequest().withHeaders(`Origin`(origins))
          val cookieConfig = CookieConfig(
            true,
            "name",
            5.seconds,
            Some(List("domain.com", "otherdomain.com")),
            Some("fallbackDomain"),
            false,
            false,
            None
          )
          service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
      }
      "return only the first match if multiple domains from the request's Origin header have matches in the list of domains supplied with the config" in {
        val origins =
          Seq(HttpOrigin("http", Host("www.domain.com")), HttpOrigin("http", Host("www.otherdomain.com", 8080)))
        val request = HttpRequest().withHeaders(`Origin`(origins))
        val cookieConfig = CookieConfig(
          true,
          "name",
          5.seconds,
          Some(List("domain.com", "otherdomain.com")),
          Some("fallbackDomain"),
          false,
          false,
          None
        )
        service.cookieDomain(request.headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
          "domain.com"
        )
      }
    }

    "extractHosts" in {
      "correctly extract the host names from a list of values in the request's Origin header" in {
        val origins =
          Seq(HttpOrigin("http", Host("origin.com")), HttpOrigin("http", Host("subdomain.otherorigin.gov.co.uk", 8080)))
        service.extractHosts(origins) shouldEqual Seq("origin.com", "subdomain.otherorigin.gov.co.uk")
      }
    }

    "validMatch" in {
      val domain = "snplow.com"
      "true for valid matches" in {
        val validHost1 = "snplow.com"
        val validHost2 = "blog.snplow.com"
        val validHost3 = "blog.snplow.com.snplow.com"
        service.validMatch(validHost1, domain) shouldEqual true
        service.validMatch(validHost2, domain) shouldEqual true
        service.validMatch(validHost3, domain) shouldEqual true
      }
      "false for invalid matches" in {
        val invalidHost1 = "notsnplow.com"
        val invalidHost2 = "blog.snplow.comsnplow.com"
        service.validMatch(invalidHost1, domain) shouldEqual false
        service.validMatch(invalidHost2, domain) shouldEqual false
      }
    }

    "determinePath" in {
      val vendor   = "com.acme"
      val version1 = "track"
      val version2 = "redirect"
      val version3 = "iglu"

      "should correctly replace the path in the request if a mapping is provided" in {
        val expected1 = "/com.snowplowanalytics.snowplow/tp2"
        val expected2 = "/r/tp2"
        val expected3 = "/com.snowplowanalytics.iglu/v1"

        service.determinePath(vendor, version1) shouldEqual expected1
        service.determinePath(vendor, version2) shouldEqual expected2
        service.determinePath(vendor, version3) shouldEqual expected3
      }

      "should pass on the original path if no mapping for it can be found" in {
        val service = new CollectorService(
          TestUtils.testConf.copy(paths = Map.empty[String, String]),
          CollectorSinks(new TestSink, new TestSink),
          "",
          ""
        )
        val expected1 = "/com.acme/track"
        val expected2 = "/com.acme/redirect"
        val expected3 = "/com.acme/iglu"

        service.determinePath(vendor, version1) shouldEqual expected1
        service.determinePath(vendor, version2) shouldEqual expected2
        service.determinePath(vendor, version3) shouldEqual expected3
      }
    }
  }

  "The collector service (analytics.js)" should {
    def runTest(body: io.circe.Json, path: String) = {
      val ProbeService(s, good, bad) = probeService()
      val response = s.cookie(
        queryString = None,
        body = Option(body.noSpaces),
        path = path,
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

      good.storedRawEvents must have size 1

      response.status.isSuccess() must beTrue

      // allow decoding the payload
      val decoded     = new CollectorPayload
      val decoder     = new org.apache.thrift.TDeserializer
      val actualEvent = good.storedRawEvents.head
      decoder.deserialize(decoded, actualEvent)
      decoded.body.isEmpty must beFalse

      bad.storedRawEvents must have size 0
    }

    "cookie" in {
      "reject a screen event (it does not have the required fields" in {
        val ProbeService(s, _, _) = probeService()

        try {
          val response = s.cookie(
            queryString = None,
            body = Option(AnalyticsJsFixture.screenPayload.noSpaces),
            path = "v1/s",
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
          // this will cause the test to fail as expected
          response.status.isFailure() must beTrue
        } catch {
          case ex: Throwable =>
            ex.getMessage mustEqual "context.library.version is required"
        }
      }

      "accepts a page payload" in {
        runTest(AnalyticsJsFixture.pagePayload, "v1/p")
      }

      "accept a group event" in {
        runTest(AnalyticsJsFixture.groupPayload, "v1/g")
      }

      "accept an alias event" in {
        runTest(AnalyticsJsFixture.aliasPayload, "v1/a")
      }

      "accept an identify event" in {
        runTest(AnalyticsJsFixture.identifyPayload, "v1/i")
      }

      "accept a track event" in {
        runTest(AnalyticsJsFixture.trackPayload, "v1/t")
      }
    }
  }
}
