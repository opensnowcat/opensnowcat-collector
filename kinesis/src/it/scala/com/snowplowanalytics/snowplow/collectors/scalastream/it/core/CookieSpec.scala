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

import cats.effect.testing.specs2.CatsIO

import org.http4s.{Header, SameSite}

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http
import com.snowplowanalytics.snowplow.collectors.scalastream.it.EventGenerator

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._

class CookieSpec extends Specification with Localstack with CatsIO {

  override protected val Timeout = 5.minutes

  "collector" should {

    "set cookie attributes according to configuration" in {

      "name, expiration, secure true, httpOnly true, SameSite" in {
        val testName   = "cookie-attributes-1"
        val streamGood = s"${testName}-raw"
        val streamBad  = s"${testName}-bad-1"

        val name       = "greatName"
        val expiration = 42 days
        val secure     = true
        val httpOnly   = true
        val sameSite   = SameSite.Strict

        Collector
          .container(
            "kinesis/src/it/resources/collector.hocon",
            testName,
            streamGood,
            streamBad,
            additionalConfig = Some(
              mkConfig(
                name = name,
                expiration = expiration,
                secure = secure,
                httpOnly = httpOnly,
                sameSite = sameSite.toString()
              )
            )
          )
          .use { collector =>
            val request = EventGenerator.mkTp2Event(collector.host, collector.port)

            for {
              resp <- Http.response(request)
              now  <- ioTimer.clock.realTime(SECONDS)
            } yield resp.cookies match {
              case List(cookie) =>
                cookie.name must beEqualTo(name)
                cookie.expires match {
                  case Some(expiry) =>
                    expiry.epochSecond should beCloseTo(now + expiration.toSeconds, 10)
                  case None =>
                    ko(s"Cookie [$cookie] doesn't contain the expiry date")
                }
                cookie.secure should beTrue
                cookie.httpOnly should beTrue
                cookie.sameSite should beEqualTo(sameSite)
              case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
            }
          }
      }

      "secure false, httpOnly false" in {
        val testName   = "cookie-attributes-2"
        val streamGood = s"${testName}-raw"
        val streamBad  = s"${testName}-bad-1"

        val httpOnly = false
        val secure   = false

        Collector
          .container(
            "kinesis/src/it/resources/collector.hocon",
            testName,
            streamGood,
            streamBad,
            additionalConfig = Some(
              mkConfig(
                secure = secure,
                httpOnly = httpOnly
              )
            )
          )
          .use { collector =>
            val request = EventGenerator.mkTp2Event(collector.host, collector.port)

            for {
              resp <- Http.response(request)
            } yield resp.cookies match {
              case List(cookie) =>
                cookie.secure should beFalse
                cookie.httpOnly should beFalse
              case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
            }
          }
      }
    }

    "not set cookie if the request sets SP-Anonymous header" in {
      val testName   = "cookie-anonymous"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(mkConfig())
        )
        .use { collector =>
          val request =
            EventGenerator.mkTp2Event(collector.host, collector.port).withHeaders(Header("SP-Anonymous", "*"))

          for {
            resp <- Http.response(request)
          } yield resp.cookies should beEmpty
        }
    }

    "not set the domain property of the cookie if collector.cookie.domains and collector.cookie.fallbackDomain are empty" in {
      val testName   = "cookie-no-domain"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(mkConfig())
        )
        .use { collector =>
          val request =
            EventGenerator.mkTp2Event(collector.host, collector.port).withHeaders(Header("Origin", "http://my.domain"))

          for {
            resp <- Http.response(request)
          } yield resp.cookies.map(cookie => cookie.domain should beNone)
        }
    }

    "set the domain property of the cookie to the first domain of collector.cookie.domains that matches Origin, even with fallbackDomain enabled" in {
      val testName   = "cookie-domain"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      val domain         = "foo.bar"
      val subDomain      = s"sub.$domain"
      val fallbackDomain = "fallback.domain"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(
            mkConfig(
              domains = Some(List(domain, subDomain)),
              fallbackDomain = Some(fallbackDomain)
            )
          )
        )
        .use { collector =>
          val request = EventGenerator
            .mkTp2Event(collector.host, collector.port)
            .withHeaders(Header("Origin", s"http://$subDomain"))

          for {
            resp <- Http.response(request)
          } yield resp.cookies match {
            case List(cookie) =>
              cookie.domain should beSome(domain)
            case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
          }
        }
    }

    "set the domain property of the cookie to collector.cookie.fallbackDomain if there is no Origin header in the request or if it contains no host that is in collector.cookie.domains" in {
      val testName   = "cookie-fallback"
      val streamGood = s"${testName}-raw"
      val streamBad  = s"${testName}-bad-1"

      val domain         = "foo.bar"
      val fallbackDomain = "fallback.domain"

      Collector
        .container(
          "kinesis/src/it/resources/collector.hocon",
          testName,
          streamGood,
          streamBad,
          additionalConfig = Some(
            mkConfig(
              domains = Some(List(domain)),
              fallbackDomain = Some(fallbackDomain)
            )
          )
        )
        .use { collector =>
          val request1 = EventGenerator
            .mkTp2Event(collector.host, collector.port)
            .withHeaders(Header("Origin", s"http://other.domain"))
          val request2 = EventGenerator.mkTp2Event(collector.host, collector.port)

          for {
            responses <- Http.responses(List(request1, request2))
          } yield responses.flatMap(r => r.cookies.map(c => c.domain must beSome(fallbackDomain)))
        }
    }
  }

  private def mkConfig(
    enabled: Boolean = true,
    name: String = "sp",
    expiration: FiniteDuration = 365 days,
    domains: Option[List[String]] = None,
    fallbackDomain: Option[String] = None,
    secure: Boolean = false,
    httpOnly: Boolean = false,
    sameSite: String = "None"
  ): String =
    s"""
      {
        "collector": {
          "cookie": {
            "enabled": $enabled,
            "name": "$name",
            "expiration": "${expiration.toString()}",
            ${domains.fold("")(l => s""""domains": [${l.map(d => s""""$d"""").mkString(",")} ],""")}
            ${fallbackDomain.fold("")(d => s""""fallbackDomain": "$d",""")}
            "secure": $secure,
            "httpOnly": $httpOnly,
            "sameSite": "$sameSite"
          }
        }
      }
    """
}
