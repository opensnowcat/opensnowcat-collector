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
//      val path = collectorService.determinePath(Vendor, Version)
      val path = "/com.snowplowanalytics.snowplow/tp2"
      // identify, track, page, screen, group, alias
      val validSegments = "itpsga"
      if (segment.length == 1 && validSegments.contains(segment))
        post {
          extractContentType { ct =>
            val normalizedContentType =
              ContentType.parse(ct.value.toLowerCase.replace("text/plain", "application/json")).toOption

            entity(as[String]) { body =>
              println("ZZZ: new data?")
              println(s"ZZZ: body=$body")

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

  // TODO: Improve this
  def createSnowplowPayload(body: Json) = {
    import io.circe._
    import java.util.Base64
    import java.nio.charset.StandardCharsets

    // TODO: This is base64-encoded, then, named cx
    def createCX = {
      val jsonObject = JsonObject(
        "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0"),
        "data" -> Json.fromValues(
          // TODO: This is what Joao sets in the js side
          List(
            Json.fromJsonObject(
              JsonObject(
                "schema" -> Json.fromString("iglu:com.segment/analyticsjs/jsonschema/1-0-0"),
                "data"   -> Json.fromJsonObject(io.circe.JsonObject("payload" -> body))
              )
            )
            // TODO: this is in the base64 cx value, is it necessary? I tested without it and it works
//            Json.fromJsonObject(
//              JsonObject(
//                "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/web_page/jsonschema/1-0-0"),
//                // TODO: Shall this be a random UUID?
//                "data" -> Json.fromJsonObject(
//                  JsonObject("id" -> Json.fromString("3b5e4f02-ccfc-49a5-8579-1541cd626f05"))
//                )
//              )
//            )
          )
        )
      )
      Json.fromJsonObject(jsonObject)
    }

    // TODO: This should not be hardcoded
    val jsonObject = JsonObject(
      "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4"),
      "data" -> Json.fromValues(
        List(
          Json.fromJsonObject(
            JsonObject(
              // TODO: From the url, p is pageview (pv), we need a mapper
              // we can use a self-describing event instead
              // https://docs.snowplow.io/docs/collecting-data/collecting-from-own-applications/snowplow-tracker-protocol/going-deeper/event-parameters/#self-describing-events
              "e" -> Json.fromString("pv"),
              // properties.url
              "url" -> Json.fromString(body.hcursor.downField("properties").get[String]("url").toOption.get),
//              "url" -> Json.fromString("https://547f-85-152-106-87.ngrok-free.app/snowplow-segment-valid.html"),
              // properties.title
//              "page" -> Json.fromString("Fingerprint Inspection"),
              "page" -> Json.fromString(body.hcursor.downField("properties").get[String]("title").toOption.get),
              // required, tracker version
              "tv" -> Json.fromString(
                body.hcursor.downField("context").downField("library").get[String]("version").toOption.get
              ),
//              "tv" -> Json.fromString("js-3.4.0"),
              // required, must be web, mob, app. in this case, analytics.js is for the web only
              "p" -> Json.fromString("web"),
//              "p" -> Json.fromString(
//                body.hcursor.downField("context").downField("userAgentData").get[String]("platform").toOption.get
//              ),

              // context.locale
              "lang" -> Json.fromString(body.hcursor.downField("context").get[String]("locale").toOption.get),
//              "lang" -> Json.fromString("en-US"),

              // context.timezone
              "tz" -> Json.fromString(body.hcursor.downField("context").get[String]("timezone").toOption.get),
//              "tz" -> Json.fromString("Europe/Madrid"),
              "cx" -> Json.fromString(
                Base64.getEncoder.encodeToString(createCX.noSpaces.getBytes(StandardCharsets.UTF_8))
              )
              // optional, shall we fill any of these?
//              "eid"    -> Json.fromString("28523013-5f07-41c8-ad65-3ddf4bf41f2c"),
//              "tna"    -> Json.fromString("sp"),
//              "aid"    -> Json.fromString("yourappid"),
//              "cookie" -> Json.fromString("1"),
//              "cs"     -> Json.fromString("windows-1252"),
//              "res"    -> Json.fromString("1512x982"),
//              "cd"     -> Json.fromString("30"),
//              "dtm"    -> Json.fromString("1722786322181"),
//              "vp"     -> Json.fromString("1512x422"),
//              "ds"     -> Json.fromString("1512x422"),
//              "vid"    -> Json.fromString("3"),
//              "sid"    -> Json.fromString("2a5aada5-0a27-4a64-814f-f515c78b5295"),
//              "duid"   -> Json.fromString("a0f80d37-e410-40ec-bfa0-6a90be01aa9b"),
//              "stm"    -> Json.fromString("1722786322184")
            )
          )
        )
      )
    )

    // TODO: Remove me
    println("createSnowplowPayload")
    println(Json.fromJsonObject(jsonObject).spaces2SortKeys)
    io.circe.Json.fromJsonObject(jsonObject)
  }
}
