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

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.HttpCookiePair
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import org.apache.pekko.http.scaladsl.server.Directives._

import com.snowplowanalytics.snowplow.collectors.scalastream.model.DntCookieMatcher

trait CollectorRoute {
  def collectorService: Service
  def healthService: HealthService

  private val headers = optionalHeaderValueByName("User-Agent") &
    optionalHeaderValueByName("Referer") &
    optionalHeaderValueByName("Raw-Request-URI") &
    optionalHeaderValueByName("SP-Anonymous")

  private[scalastream] def extractors(spAnonymous: Option[String]) =
    spAnonymous match {
      case Some(_) =>
        extractHost & extractClientIP.map[RemoteAddress](_ => RemoteAddress.Unknown) & extractRequest
      case _ => extractHost & extractClientIP & extractRequest
    }

  def extractContentType: Directive1[ContentType] =
    extractRequestContext.map(_.request.entity.contentType)

  def collectorRoute =
    if (collectorService.enableDefaultRedirect) routes else rejectRedirect ~ routes

  def rejectRedirect: Route =
    path("r" / Segment) { _ =>
      complete(StatusCodes.NotFound -> "redirects disabled")
    }

  def routes: Route =
    doNotTrack(collectorService.doNotTrackCookie) { dnt =>
      cookieIfWanted(collectorService.cookieName) { reqCookie =>
        val cookie = reqCookie.map(_.toCookie)
        headers { (userAgent, refererURI, rawRequestURI, spAnonymous) =>
          val qs = queryString(rawRequestURI)
          extractors(spAnonymous) { (host, ip, request) =>
            val analyticsJsRoutes = AnalyticsJsBridge.routes(
              queryString = qs,
              cookie = cookie,
              userAgent = userAgent,
              refererUri = refererURI,
              hostname = host,
              ip = ip,
              doNotTrack = dnt,
              request = request,
              spAnonymous = spAnonymous,
              extractContentType = extractContentType,
              collectorService = collectorService
            )

            // get the adapter vendor and version from the path
            val collectorRoutes = path(Segment / Segment) { (vendor, version) =>
              val path = collectorService.determinePath(vendor, version)
              post {
                extractContentType { ct =>
                  entity(as[String]) { body =>
                    val r = collectorService.cookie(
                      qs,
                      Some(body),
                      path,
                      cookie,
                      userAgent,
                      refererURI,
                      host,
                      ip,
                      request,
                      pixelExpected = false,
                      doNotTrack = dnt,
                      Some(ct),
                      spAnonymous
                    )
                    complete(r)
                  }
                }
              } ~
                (get | head) {
                  val r = collectorService.cookie(
                    qs,
                    None,
                    path,
                    cookie,
                    userAgent,
                    refererURI,
                    host,
                    ip,
                    request,
                    pixelExpected = true,
                    doNotTrack = dnt,
                    None,
                    spAnonymous
                  )
                  complete(r)
                }
            } ~
              path("""ice\.png""".r | "i".r) { path =>
                (get | head) {
                  val r = collectorService.cookie(
                    qs,
                    None,
                    "/" + path,
                    cookie,
                    userAgent,
                    refererURI,
                    host,
                    ip,
                    request,
                    pixelExpected = true,
                    doNotTrack = dnt,
                    None,
                    spAnonymous
                  )
                  complete(r)
                }
              }

            if (collectorService.enableAnalyticsJsBridge) {
              analyticsJsRoutes ~ collectorRoutes
            } else {
              collectorRoutes
            }
          }
        }
      }
    } ~ corsRoute ~ healthRoute ~ sinkHealthRoute ~ crossDomainRoute ~ rootRoute ~ robotsRoute ~ {
      complete(HttpResponse(404, entity = "404 not found"))
    }

  /** Extract the query string from a request URI
    * @param rawRequestURI URI optionally extracted from the Raw-Request-URI header
    * @return the extracted query string or an empty string
    */
  def queryString(rawRequestURI: Option[String]): Option[String] = {
    val querystringExtractor = "^[^?]*\\?([^#]*)(?:#.*)?$".r
    rawRequestURI.flatMap {
      case querystringExtractor(qs) => Some(qs)
      case _                        => None
    }
  }

  /** Directive to extract a cookie if a cookie name is specified and if such a cookie exists
    * @param name Optionally configured cookie name
    */
  def cookieIfWanted(name: Option[String]): Directive1[Option[HttpCookiePair]] = name match {
    case Some(n) => optionalCookie(n)
    case None    => optionalHeaderValue(_ => None)
  }

  /** Directive to filter requests which contain a do not track cookie
    * @param cookieMatcher the configured do not track cookie to check against
    */
  def doNotTrack(cookieMatcher: Option[DntCookieMatcher]): Directive1[Boolean] =
    cookieIfWanted(cookieMatcher.map(_.name)).map { c =>
      (c, cookieMatcher) match {
        case (Some(actual), Some(config)) => config.matches(actual)
        case _                            => false
      }
    }

  private def crossDomainRoute: Route = get {
    path("""crossdomain\.xml""".r) { _ =>
      complete(collectorService.flashCrossDomainPolicy)
    }
  }

  private def healthRoute: Route = get {
    path("health".r) { _ =>
      if (healthService.isHealthy)
        complete(HttpResponse(200, entity = "OK"))
      else
        complete(HttpResponse(503, entity = "Service Unavailable"))
    }
  }

  private def sinkHealthRoute: Route = get {
    path("sink-health".r) { _ =>
      complete(
        if (collectorService.sinksHealthy) {
          HttpResponse(200, entity = "OK")
        } else {
          HttpResponse(503, entity = "Service Unavailable")
        }
      )
    }
  }

  private def corsRoute: Route = options {
    extractRequest { request =>
      complete(collectorService.preflightResponse(request))
    }
  }

  private def rootRoute: Route = get {
    pathSingleSlash {
      complete(collectorService.rootResponse)
    }
  }

  private def robotsRoute: Route = get {
    path("robots.txt".r) { _ =>
      complete(HttpResponse(200, entity = "User-agent: *\nDisallow: /"))
    }
  }
}
