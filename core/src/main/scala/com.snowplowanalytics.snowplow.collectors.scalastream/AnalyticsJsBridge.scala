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
    path(Vendor / Version / Segment) { segment =>
      // ideally, we should use /com.segment/v1 as the path but this requires a remote adapter on the enrich side
      // instead, we are reusing the snowplow event type while attaching the segment payload
      val path = "/com.snowplowanalytics.snowplow/tp2"

      // identify, track, page, screen, group, alias
      val validSegments = "itpsga"
      if (segment.length == 1 && validSegments.contains(segment))
        post {
          extractContentType { ct =>
            // analytics.js is sending "text/plain" content type which is not supported by the snowplow schema
            val normalizedContentType =
              ContentType.parse(ct.value.toLowerCase.replace("text/plain", "application/json")).toOption

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
                contentType = normalizedContentType,
                spAnonymous = spAnonymous,
                analyticsJsBridge = true
              )
              complete(r)
            }
          }
        }
      else complete(HttpResponse(StatusCodes.BadRequest))
    }

  def createSnowplowPayload(body: Json): Json = {
    import io.circe._
    import java.util.Base64
    import java.nio.charset.StandardCharsets

    def customContext = {
      val jsonObject = JsonObject(
        "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0"),
        "data" -> Json.fromValues(
          List(
            Json.fromJsonObject(
              JsonObject(
                "schema" -> Json.fromString("iglu:com.segment/analyticsjs/jsonschema/1-0-0"),
                "data"   -> Json.fromJsonObject(io.circe.JsonObject("payload" -> body))
              )
            )
          )
        )
      )
      Json.fromJsonObject(jsonObject)
    }

    val properties = body.hcursor.downField("properties")
    val context    = body.hcursor.downField("context")

    val url      = "url"  -> properties.get[String]("url")
    val page     = "page" -> properties.get[String]("page")
    val locale   = "lang" -> context.get[String]("locale")
    val timezone = "tz"   -> context.get[String]("locale")

    val trackerVersion = context
      .downField("library")
      .get[String]("version")
      .toOption
      .getOrElse(throw new RuntimeException("context.library.version is required"))

    val initialData = JsonObject(
      // page view
      "e" -> Json.fromString("pv"),
      // tracker version (required)
      "tv" -> Json.fromString(trackerVersion),
      // platform (required), must be web, mob, app. in this case, analytics.js is for the web only
      "p" -> Json.fromString("web"),
      // base64-encoded context
      "cx" -> Json.fromString(
        Base64.getEncoder.encodeToString(customContext.noSpaces.getBytes(StandardCharsets.UTF_8))
      )
    )

    // merge optional arguments
    val data = List(url, page, locale, timezone).map(x => x._1 -> x._2.toOption).foldLeft(initialData) {
      case (acc, (key, Some(value))) => acc.add(key, Json.fromString(value))
      case (acc, _)                  => acc
    }

    // TODO: Should we use a self-describing event instead?
    // https://docs.snowplow.io/docs/collecting-data/collecting-from-own-applications/snowplow-tracker-protocol/going-deeper/event-parameters/#self-describing-events
    val jsonObject = JsonObject(
      "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4"),
      "data" -> Json.fromValues(
        List(Json.fromJsonObject(data))
      )
    )

    Json.fromJsonObject(jsonObject)
  }
}
