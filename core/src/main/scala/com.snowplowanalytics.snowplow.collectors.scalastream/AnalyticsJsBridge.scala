package com.snowplowanalytics.snowplow.collectors.scalastream

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._

object AnalyticsJsBridge {

  import io.circe._

  val jsonResponse: Json = Json.fromJsonObject(JsonObject("success" -> Json.fromBoolean(true)))

  private val Vendor  = "com.segment"
  private val Version = "v1"

  def routes(
    queryString: Option[String],
    cookie: Option[HttpCookie],
    userAgent: Option[String],
    refererUri: Option[String],
    hostname: String,
    ip: RemoteAddress,
    doNotTrack: Boolean,
    request: HttpRequest,
    spAnonymous: Option[String],
    extractContentType: Directive1[ContentType],
    collectorService: Service
  ) =
    // TODO: Should we use a regex for the accepted segments?
    path(Vendor / Version / Segment) { segment =>
      val path = collectorService.determinePath(Vendor, segment)
      // identify, track, page, screen, group, alias
      val validSegments = "itpsga"
      if (segment.length == 1 && validSegments.contains(segment))
        post {
          extractContentType { ct =>
            entity(as[String]) { body =>
              val r = collectorService.cookie(
                queryString = queryString,
                body = Some(body),
                path = path,
                cookie = cookie,
                userAgent = userAgent,
                refererUri = refererUri,
                hostname = hostname,
                ip = ip,
                request = request,
                pixelExpected = false,
                doNotTrack = doNotTrack,
                contentType = Some(ct),
                spAnonymous = spAnonymous,
                analyticsJsBridge = true
              )
              complete(r)
            }
          }
        }
      else complete(HttpResponse(StatusCodes.BadRequest))
    }
}
