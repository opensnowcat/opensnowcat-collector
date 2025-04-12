package com.snowplowanalytics.snowplow.collectors.scalastream

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.HttpCookie
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._

object AnalyticsJsBridge {

  import io.circe._

  val jsonResponse: Json = Json.fromJsonObject(JsonObject("success" -> Json.fromBoolean(true)))

  private val Vendor  = "com.segment"
  private val Version = "v1"

  case class Event(eventType: EventType, anonymousUserId: Option[String], userId: Option[String])

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
      optionalCookie("ajs_anonymous_id") { ajsAnonymousUserIdCookie =>
        optionalCookie("ajs_user_id") { ajsUserIdCookie =>
          val anonymousUserId = ajsAnonymousUserIdCookie.map(_.toCookie().value)
          val userId          = ajsUserIdCookie.map(_.toCookie().value)

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
                    analyticsJsEvent = eventType.map(t => Event(t, anonymousUserId = anonymousUserId, userId = userId))
                  )
                  complete(r)
                }
              }
            }
          else complete(HttpResponse(StatusCodes.BadRequest))
        }
      }

    }

  def createSnowplowPayload(body: Json, event: Event, networkUserId: String): Json = {
    import io.circe._

    import java.nio.charset.StandardCharsets
    import java.util.Base64

    val appId = "ajs_bridge"

    val eventSchema = event.eventType match {
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

    val url      = "url"  -> properties.get[String]("url").toOption
    val page     = "page" -> properties.get[String]("page").toOption
    val locale   = "lang" -> context.get[String]("locale").toOption
    val timezone = "tz"   -> context.get[String]("timezone").toOption
    // user_id
    val userId = "uid" -> event.userId.orElse {
      body.hcursor.get[String]("userId").toOption
    }
    // domain_userid
    val domainUserId = "duid" -> event.anonymousUserId

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
      ),
      // network_userid
      "tnuid" -> Json.fromString(networkUserId)
    )

    // merge optional arguments
    val optionalEntries = List(url, page, locale, timezone, userId, domainUserId)
    val data = optionalEntries.foldLeft(initialData) {
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
