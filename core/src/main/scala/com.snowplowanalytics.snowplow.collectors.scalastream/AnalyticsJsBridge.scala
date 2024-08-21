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

  sealed trait EventType extends Product with Serializable
  object EventType {
    case object Page extends EventType
    case object Identify extends EventType
    case object Track extends EventType
    case object Group extends EventType
    case object Alias extends EventType
    case object Screen extends EventType
  }

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
      //
      // consider using a path-mapping config instead.
      val path = "/com.snowplowanalytics.snowplow/tp2"

      // identify, track, page, screen, group, alias
      val eventType = segment match {
        case "i" => Some(AnalyticsJsBridge.EventType.Identify)
        case "t" => Some(AnalyticsJsBridge.EventType.Track)
        case "p" => Some(AnalyticsJsBridge.EventType.Page)
        case "s" => Some(AnalyticsJsBridge.EventType.Screen)
        case "g" => Some(AnalyticsJsBridge.EventType.Group)
        case "a" => Some(AnalyticsJsBridge.EventType.Alias)
        case _   => None
      }
      if (eventType.isDefined)
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
                analyticsJsEvent = eventType
              )
              complete(r)
            }
          }
        }
      else complete(HttpResponse(StatusCodes.BadRequest))
    }

  def createSnowplowPayload(body: Json, eventType: EventType): Json = {
    import io.circe._

    import java.nio.charset.StandardCharsets
    import java.util.Base64

    val appId = "ajs_bridge"

    val eventSchema = eventType match {
      case EventType.Page     => "iglu:com.segment/page/jsonschema/2-0-0"
      case EventType.Identify => "iglu:com.segment/identify/jsonschema/1-0-0"
      case EventType.Track    => "iglu:com.segment/track/jsonschema/1-0-0"
      case EventType.Group    => "iglu:com.segment/group/jsonschema/2-0-0"
      case EventType.Alias    => "iglu:com.segment/alias/jsonschema/2-0-0"
      case EventType.Screen   => "iglu:com.segment/screen/jsonschema/2-0-0"
    }

    def eventPayload = {
      val jsonObject = JsonObject(
        "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0"),
        "data" -> Json.fromJsonObject(
          JsonObject(
            "schema" -> Json.fromString(eventSchema),
            "data"   -> body
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
    val timezone = "tz"   -> context.get[String]("timezone")
    val userId   = "uid"  -> body.hcursor.get[String]("userId")

    val trackerVersion = context
      .downField("library")
      .get[String]("version")
      .toOption
      .getOrElse(throw new RuntimeException("context.library.version is required"))

    val initialData = JsonObject(
      "aid" -> Json.fromString(appId),
      // self-describing event
      "e" -> Json.fromString("ue"),
      // tracker version (required)
      "tv" -> Json.fromString(trackerVersion),
      // platform (required), must be web, mob, app. in this case, analytics.js is for the web only
      "p" -> Json.fromString("web"),
      // base64-encoded event
      "ue_px" -> Json.fromString(
        Base64.getEncoder.encodeToString(eventPayload.noSpaces.getBytes(StandardCharsets.UTF_8))
      )
    )

    // merge optional arguments
    val data = List(url, page, locale, timezone, userId).map(x => x._1 -> x._2.toOption).foldLeft(initialData) {
      case (acc, (key, Some(value))) => acc.add(key, Json.fromString(value))
      case (acc, _)                  => acc
    }

    val jsonObject = JsonObject(
      "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4"),
      "data" -> Json.fromValues(
        List(Json.fromJsonObject(data))
      )
    )

    Json.fromJsonObject(jsonObject)
  }
}
