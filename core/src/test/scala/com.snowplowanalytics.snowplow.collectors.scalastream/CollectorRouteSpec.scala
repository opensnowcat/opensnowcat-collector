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

//import java.net.InetAddress

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
//import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.Specs2RouteTest

import com.snowplowanalytics.snowplow.collectors.scalastream.model.DntCookieMatcher

import org.specs2.mutable.Specification

class CollectorRouteSpec extends Specification with Specs2RouteTest {
  val mkRoute = (withRedirects: Boolean, spAnonymous: Option[String]) =>
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
        ): HttpResponse                                            = HttpResponse(200, entity = s"cookie")
        def cookieName: Option[String]                             = Some("name")
        def doNotTrackCookie: Option[DntCookieMatcher]             = None
        def determinePath(vendor: String, version: String): String = "/p1/p2"
        def enableDefaultRedirect: Boolean                         = withRedirects
        def sinksHealthy: Boolean                                  = true
      }
      override val healthService = new HealthService {
        def isHealthy: Boolean = true
      }
    }
  val route                      = mkRoute(true, None)
  val routeWithoutRedirects      = mkRoute(false, None)
  val routeWithAnonymousTracking = mkRoute(true, Some("*"))

  "The collector <> analytics.js bridge route" should {
    "accept a page view event" in {
      val event = """{"timestamp":"2024-04-14T20:36:53.131Z","integrations":{},"userId":"562927","anonymousId":"e09fac9f-16e4-43f1-8753-c55023820f81","type":"page","properties":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"context":{"page":{"path":"/","referrer":"","search":"","title":"SnowcatCloud: Cloud-Hosted Snowplow SOC2 Type 2 Certified","url":"https://www.snowcatcloud.com"},"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36","userAgentData":{"brands":[{"brand":"Google Chrome","version":"123"},{"brand":"Not:A-Brand","version":"8"},{"brand":"Chromium","version":"123"}],"mobile":false,"platform":"macOS"},"locale":"en-US","library":{"name":"analytics.js","version":"next-1.64.0"},"timezone":"Europe/Madrid"},"messageId":"ajs-next-eae00995851506a0b58be4e786deeec9","writeKey":"rWAfVSHRrcvxG0UH4vv3aFZ2dIPmv08c","sentAt":"2024-04-14T20:36:53.134Z","_metadata":{"bundled":["Segment.io"],"unbundled":[],"bundledIds":[]}}"""
      Post("/v1/p", event) ~> route.collectorRoute ~> check {
        println(s"ZZZ: response=${responseAs[String]}")
        responseAs[String] shouldEqual "cookie"
      }
    }
  }
//
//  "The collector route" should {
//    "respond to the cors route with a preflight response" in {
//      Options() ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "preflight response"
//      }
//    }
//    "respond to the health route with an ok response" in {
//      Get("/health") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "OK"
//      }
//    }
//    "respond to the cross domain route with the cross domain policy" in {
//      Get("/crossdomain.xml") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "flash cross domain"
//      }
//    }
//    "respond to the post cookie route with the cookie response" in {
//      Post("/p1/p2") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//    }
//    "respond to the get cookie route with the cookie response" in {
//      Get("/p1/p2") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//    }
//    "respond to the head cookie route with the cookie response" in {
//      Head("/p1/p2") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//    }
//    "respond to the get pixel route with the cookie response" in {
//      Get("/ice.png") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//      Get("/i") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//    }
//    "respond to the head pixel route with the cookie response" in {
//      Head("/ice.png") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//      Head("/i") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "cookie"
//      }
//    }
//    "respond to customizable root requests" in {
//      Get("/") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "200 collector root"
//      }
//    }
//    "disallow redirect routes when redirects disabled" in {
//      Get("/r/abc") ~> routeWithoutRedirects.collectorRoute ~> check {
//        responseAs[String] shouldEqual "redirects disabled"
//      }
//    }
//    "respond to anything else with a not found" in {
//      Get("/something") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "404 not found"
//      }
//    }
//    "respond to robots.txt with a disallow rule" in {
//      Get("/robots.txt") ~> route.collectorRoute ~> check {
//        responseAs[String] shouldEqual "User-agent: *\nDisallow: /"
//      }
//    }
//
//    "extract a query string" in {
//      "produce the query string if present" in {
//        route.queryString(Some("/abc/def?a=12&b=13#frg")) shouldEqual Some("a=12&b=13")
//      }
//      "produce an empty string if the extractor doesn't match" in {
//        route.queryString(Some("/abc/def#frg")) shouldEqual None
//      }
//      "produce an empty string if the argument is None" in {
//        route.queryString(None) shouldEqual None
//      }
//      "produce the query string when some of the values are URL encoded" in {
//        route.queryString(
//          Some("/abc/def?schema=iglu%3Acom.acme%2Fcampaign%2Fjsonschema%2F1-0-0&aid=test")
//        ) shouldEqual Some(
//          "schema=iglu%3Acom.acme%2Fcampaign%2Fjsonschema%2F1-0-0&aid=test"
//        )
//      }
//    }
//
//    "have a directive extracting a cookie" in {
//      "return the cookie if some cookie name is given" in {
//        Get() ~> Cookie("abc" -> "123") ~>
//          route.cookieIfWanted(Some("abc")) { c =>
//            complete(HttpResponse(200, entity = c.toString))
//          } ~> check {
//            responseAs[String] shouldEqual "Some(abc=123)"
//          }
//      }
//      "return none if no cookie name is given" in {
//        Get() ~> Cookie("abc" -> "123") ~>
//          route.cookieIfWanted(None) { c =>
//            complete(HttpResponse(200, entity = c.toString))
//          } ~> check {
//            responseAs[String] shouldEqual "None"
//          }
//      }
//    }
//
//    "have a directive checking for a do not track cookie" in {
//      "return false if the dnt cookie is not setup" in {
//        Get() ~> Cookie("abc" -> "123") ~> route.doNotTrack(None) { dnt =>
//          complete(dnt.toString)
//        } ~> check {
//          responseAs[String] shouldEqual "false"
//        }
//      }
//      "return false if the dnt cookie doesn't have the same value compared to configuration" in {
//        Get() ~> Cookie("abc" -> "123") ~>
//          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "345"))) { dnt =>
//            complete(dnt.toString)
//          } ~> check {
//            responseAs[String] shouldEqual "false"
//          }
//      }
//      "return true if there is a properly-valued dnt cookie" in {
//        Get() ~> Cookie("abc" -> "123") ~>
//          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "123"))) { dnt =>
//            complete(dnt.toString)
//          } ~> check {
//            responseAs[String] shouldEqual "true"
//          }
//      }
//      "return true if there is a properly-valued dnt cookie that matches a regex value" in {
//        Get() ~> Cookie("abc" -> s"deleted-${System.currentTimeMillis()}") ~>
//          route.doNotTrack(Some(DntCookieMatcher(name = "abc", value = "deleted-[0-9]+"))) { dnt =>
//            complete(dnt.toString)
//          } ~> check {
//            responseAs[String] shouldEqual "true"
//          }
//      }
//    }
//
//    "have a directive to handle the IP address depending on whether SP-Anonymous header is present or not" in {
//      "SP-Anonymous present should obfuscate the IP address" in {
//        Get() ~> `X-Forwarded-For`(RemoteAddress.IP(InetAddress.getByName("127.0.0.1"))) ~> route.extractors(
//          Some("*")
//        ) { (_, ip, _) =>
//          complete(ip.toString)
//        } ~> check(responseAs[String] shouldEqual "unknown")
//      }
//      "no SP-Anonymous present should extract the IP address" in {
//        Get().withAttributes(
//          Map(AttributeKeys.remoteAddress -> RemoteAddress.IP(InetAddress.getByName("127.0.0.1")))
//        ) ~> route.extractors(
//          None
//        ) { (_, ip, _) =>
//          complete(ip.toString)
//        } ~> check(responseAs[String] shouldEqual "127.0.0.1")
//      }
//    }
//  }
}
