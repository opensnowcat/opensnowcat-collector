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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import com.snowplowanalytics.snowplow.collectors.scalastream.fixtures.AnalyticsJsFixture
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.Specs2RouteTest

import com.snowplowanalytics.snowplow.collectors.scalastream.model.DntCookieMatcher

import org.specs2.mutable.Specification

class CollectorRouteSpec extends Specification with Specs2RouteTest {
  val mkRoute = (withRedirects: Boolean, spAnonymous: Option[String], analyticsJsBridge: Boolean) =>
    new CollectorRoute {
      override val collectorService = new Service {
        def preflightResponse(req: HttpRequest): HttpResponse =
          HttpResponse(200, entity = "preflight response")
        def flashCrossDomainPolicy: HttpResponse = HttpResponse(200, entity = "flash cross domain")
        def rootResponse: HttpResponse           = HttpResponse(200, entity = "200 collector root")
        def cookie(
          queryString: Option[String],
          body: Option[String],
          path: String,
          cookie: Option[HttpCookie],
          userAgent: Option[String],
          refererUri: Option[String],
          hostname: String,
          ip: RemoteAddress,
          request: HttpRequest,
          pixelExpected: Boolean,
          doNotTrack: Boolean,
          contentType: Option[ContentType] = None,
          spAnonymous: Option[String] = spAnonymous,
          analyticsJsBridge: Boolean = false
        ): HttpResponse =
          if (analyticsJsBridge) {
            HttpResponse(200, entity = AnalyticsJsBridge.jsonResponse.noSpaces)
          } else {
            HttpResponse(200, entity = s"cookie")
          }

        def cookieName: Option[String]                             = Some("name")
        def doNotTrackCookie: Option[DntCookieMatcher]             = None
        def determinePath(vendor: String, version: String): String = "/p1/p2"
        def enableDefaultRedirect: Boolean                         = withRedirects

        def enableAnalyticsJsBridge: Boolean = analyticsJsBridge
        def sinksHealthy: Boolean            = true
      }
      override val healthService = new HealthService {
        def isHealthy: Boolean = true
      }
    }
  val route                      = mkRoute(true, None, false)
  val routeWithoutRedirects      = mkRoute(false, None, false)
  val routeWithAnonymousTracking = mkRoute(true, Some("*"), false)
  val routeWithAnalyticsJs       = mkRoute(true, None, true)

  "The collector <> analytics.js bridge route" should {
    def assertResponse(response: String) = {
      val jsonE = io.circe.parser.parse(response)
      jsonE.isRight shouldEqual true
      val json     = jsonE.right.get
      val expected = AnalyticsJsBridge.jsonResponse
      json shouldEqual expected
    }

    "accept an identify event" in {
      val event = AnalyticsJsFixture.identifyPayload.noSpaces
      Post("/com.segment/v1/i", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "accept a track event" in {
      val event = AnalyticsJsFixture.trackPayload.noSpaces
      Post("/com.segment/v1/t", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "accept a page event" in {
      val event = AnalyticsJsFixture.pagePayload.noSpaces
      Post("/com.segment/v1/p", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "accept a screen event" in {
      val event = AnalyticsJsFixture.screenPayload.noSpaces
      Post("/com.segment/v1/s", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "accept a group event" in {
      val event = AnalyticsJsFixture.groupPayload.noSpaces
      Post("/com.segment/v1/g", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "accept an alias event" in {
      val event = AnalyticsJsFixture.aliasPayload.noSpaces
      Post("/com.segment/v1/a", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        assertResponse(responseAs[String])
      }
    }

    "reject non analytics.js path" in {
      val event = AnalyticsJsFixture.aliasPayload.noSpaces
      Post("/com.segment/v1/x", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        status.isFailure() must beTrue
      }
    }

    "reject an unsupported version from analytics.js" in {
      val event = AnalyticsJsFixture.aliasPayload.noSpaces
      Post("/com.segment/v2/p", event) ~> routeWithAnalyticsJs.collectorRoute ~> check {
        status.isFailure() must beTrue
      }
    }
  }

  "The collector route" should {
    "respond to the cors route with a preflight response" in {
      Options() ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "preflight response"
      }
    }
    "respond to the health route with an ok response" in {
      Get("/health") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "OK"
      }
    }
    "respond to the cross domain route with the cross domain policy" in {
      Get("/crossdomain.xml") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "flash cross domain"
      }
    }
    "respond to the post cookie route with the cookie response" in {
      Post("/p1/p2") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
    }
    "respond to the get cookie route with the cookie response" in {
      Get("/p1/p2") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
    }
    "respond to the head cookie route with the cookie response" in {
      Head("/p1/p2") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
    }
    "respond to the get pixel route with the cookie response" in {
      Get("/ice.png") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
      Get("/i") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
    }
    "respond to the head pixel route with the cookie response" in {
      Head("/ice.png") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
      Head("/i") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "cookie"
      }
    }
    "respond to customizable root requests" in {
      Get("/") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "200 collector root"
      }
    }
    "disallow redirect routes when redirects disabled" in {
      Get("/r/abc") ~> routeWithoutRedirects.collectorRoute ~> check {
        responseAs[String] shouldEqual "redirects disabled"
      }
    }
    "respond to anything else with a not found" in {
      Get("/something") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "404 not found"
      }
    }
    "respond to robots.txt with a disallow rule" in {
      Get("/robots.txt") ~> route.collectorRoute ~> check {
        responseAs[String] shouldEqual "User-agent: *\nDisallow: /"
      }
    }

    "extract a query string" in {
      "produce the query string if present" in {
        route.queryString(Some("/abc/def?a=12&b=13#frg")) shouldEqual Some("a=12&b=13")
      }
      "produce an empty string if the extractor doesn't match" in {
        route.queryString(Some("/abc/def#frg")) shouldEqual None
      }
      "produce an empty string if the argument is None" in {
        route.queryString(None) shouldEqual None
      }
      "produce the query string when some of the values are URL encoded" in {
        route.queryString(
          Some("/abc/def?schema=iglu%3Acom.acme%2Fcampaign%2Fjsonschema%2F1-0-0&aid=test")
        ) shouldEqual Some(
          "schema=iglu%3Acom.acme%2Fcampaign%2Fjsonschema%2F1-0-0&aid=test"
        )
      }
    }

    "have a directive extracting a cookie" in {
      "return the cookie if some cookie name is given" in {
        Get() ~> Cookie("abc" -> "123") ~>
          route.cookieIfWanted(Some("abc")) { c =>
            complete(HttpResponse(200, entity = c.toString))
          } ~> check {
            responseAs[String] shouldEqual "Some(abc=123)"
          }
      }
      "return none if no cookie name is given" in {
        Get() ~> Cookie("abc" -> "123") ~>
          route.cookieIfWanted(None) { c =>
            complete(HttpResponse(200, entity = c.toString))
          } ~> check {
            responseAs[String] shouldEqual "None"
          }
      }
    }

    "have a directive checking for a do not track cookie" in {
      "return false if the dnt cookie is not setup" in {
        Get() ~> Cookie("abc" -> "123") ~> route.doNotTrack(None) { dnt =>
          complete(dnt.toString)
        } ~> check {
          responseAs[String] shouldEqual "false"
        }
      }
      "return false if the dnt cookie doesn't have the same value compared to configuration" in {
        Get() ~> Cookie("abc" -> "123") ~>
          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "345"))) { dnt =>
            complete(dnt.toString)
          } ~> check {
            responseAs[String] shouldEqual "false"
          }
      }
      "return true if there is a properly-valued dnt cookie" in {
        Get() ~> Cookie("abc" -> "123") ~>
          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "123"))) { dnt =>
            complete(dnt.toString)
          } ~> check {
            responseAs[String] shouldEqual "true"
          }
      }
      "return true if there is a properly-valued dnt cookie that matches a regex value" in {
        Get() ~> Cookie("abc" -> s"deleted-${System.currentTimeMillis()}") ~>
          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "deleted-[0-9]+"))) { dnt =>
            complete(dnt.toString)
          } ~> check {
            responseAs[String] shouldEqual "true"
          }
      }
    }

    "have a directive to handle the IP address depending on whether SP-Anonymous header is present or not" in {
      "SP-Anonymous present should obfuscate the IP address" in {
        Get() ~> `X-Forwarded-For`(RemoteAddress.IP(InetAddress.getByName("127.0.0.1"))) ~> route.extractors(
          Some("*")
        ) { (_, ip, _) =>
          complete(ip.toString)
        } ~> check(responseAs[String] shouldEqual "unknown")
      }
      "no SP-Anonymous present should extract the IP address" in {
        Get().withAttributes(
          Map(AttributeKeys.remoteAddress -> RemoteAddress.IP(InetAddress.getByName("127.0.0.1")))
        ) ~> route.extractors(
          None
        ) { (_, ip, _) =>
          complete(ip.toString)
        } ~> check(responseAs[String] shouldEqual "127.0.0.1")
      }
    }
  }
}
