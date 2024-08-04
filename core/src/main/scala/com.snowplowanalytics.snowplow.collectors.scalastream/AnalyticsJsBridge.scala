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
    // TODO: This is base64-encoded, then, named cx2
    def createCX2 = {
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
            ),
            // TODO: this is in the base64 cx value, is it necessary?
            Json.fromJsonObject(
              JsonObject(
                "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/web_page/jsonschema/1-0-0"),
                // TODO: Shall this be a random UUID?
                "data" -> Json.fromJsonObject(
                  JsonObject("id" -> Json.fromString("3b5e4f02-ccfc-49a5-8579-1541cd626f05"))
                )
              )
            )
          )
        )
      )
      java
        .util
        .Base64
        .getEncoder
        .encodeToString(Json.fromJsonObject(jsonObject).noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
//    val cx2 = createCX2
    // TODO: This should not be hardcoded
    val jsonObject = JsonObject(
      "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4"),
      "data" -> Json.fromValues(
        List(
          Json.fromJsonObject(
            JsonObject(
              "e"      -> Json.fromString("pv"),
              "url"    -> Json.fromString("https://547f-85-152-106-87.ngrok-free.app/snowplow-segment-valid.html"),
              "page"   -> Json.fromString("Fingerprint Inspection"),
              "eid"    -> Json.fromString("28523013-5f07-41c8-ad65-3ddf4bf41f2c"),
              "tv"     -> Json.fromString("js-3.4.0"),
              "tna"    -> Json.fromString("sp"),
              "aid"    -> Json.fromString("yourappid"),
              "p"      -> Json.fromString("web"),
              "cookie" -> Json.fromString("1"),
              "cs"     -> Json.fromString("windows-1252"),
              "lang"   -> Json.fromString("en-US"),
              "res"    -> Json.fromString("1512x982"),
              "cd"     -> Json.fromString("30"),
              "tz"     -> Json.fromString("Europe/Madrid"),
              "dtm"    -> Json.fromString("1722786322181"),
              "vp"     -> Json.fromString("1512x422"),
              "ds"     -> Json.fromString("1512x422"),
              "vid"    -> Json.fromString("3"),
              "sid"    -> Json.fromString("2a5aada5-0a27-4a64-814f-f515c78b5295"),
              "duid"   -> Json.fromString("a0f80d37-e410-40ec-bfa0-6a90be01aa9b"),
              "cx" -> Json.fromString(
                "eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9jb250ZXh0cy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6W3sic2NoZW1hIjoiaWdsdTpjb20uc2VnbWVudC9hbmFseXRpY3Nqcy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJwYXlsb2FkIjp7InRpbWVzdGFtcCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTA5WiIsImludGVncmF0aW9ucyI6e30sInR5cGUiOiJwYWdlIiwicHJvcGVydGllcyI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sImNvbnRleHQiOnsicGFnZSI6eyJwYXRoIjoiL3NlZ21lbnQuaHRtbCIsInJlZmVycmVyIjoiIiwic2VhcmNoIjoiIiwidGl0bGUiOiJGaW5nZXJwcmludCBJbnNwZWN0aW9uIiwidXJsIjoiaHR0cHM6Ly9wcmV2aWV3LnNub3djYXRjbG91ZC5jb20vc2VnbWVudC5odG1sIn0sInVzZXJBZ2VudCI6Ik1vemlsbGEvNS4wIChNYWNpbnRvc2g7IEludGVsIE1hYyBPUyBYIDEwXzE1XzcpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIENocm9tZS8xMjUuMC4wLjAgU2FmYXJpLzUzNy4zNiIsInVzZXJBZ2VudERhdGEiOnsiYnJhbmRzIjpbeyJicmFuZCI6Ikdvb2dsZSBDaHJvbWUiLCJ2ZXJzaW9uIjoiMTI1In0seyJicmFuZCI6IkNocm9taXVtIiwidmVyc2lvbiI6IjEyNSJ9LHsiYnJhbmQiOiJOb3QuQS9CcmFuZCIsInZlcnNpb24iOiIyNCJ9XSwibW9iaWxlIjpmYWxzZSwicGxhdGZvcm0iOiJtYWNPUyJ9LCJsb2NhbGUiOiJlbi1VUyIsImxpYnJhcnkiOnsibmFtZSI6ImFuYWx5dGljcy5qcyIsInZlcnNpb24iOiJuZXh0LTEuNzAuMCJ9LCJ0aW1lem9uZSI6IkFtZXJpY2EvTG9zX0FuZ2VsZXMifSwibWVzc2FnZUlkIjoiYWpzLW5leHQtMTcxNzU2NDUxODEwOS03MWNkOGRjNS00ZTM1LTQ1MjAtODgxOC03ODRmZDU0YmJlODQiLCJhbm9ueW1vdXNJZCI6IjUwZGQ3ZmZmLWE1MTMtNDYzZi1hNTJkLTgzZmZmNDhmMDVjYSIsIndyaXRlS2V5IjoicldBZlZTSFJyY3Z4RzBVSDR2djNhRloyZElQbXYwOGMiLCJ1c2VySWQiOm51bGwsInNlbnRBdCI6IjIwMjQtMDYtMDVUMDU6MTU6MTguMTE0WiIsIl9tZXRhZGF0YSI6eyJidW5kbGVkIjpbIlNlZ21lbnQuaW8iXSwidW5idW5kbGVkIjpbXSwiYnVuZGxlZElkcyI6W119fX19LHsic2NoZW1hIjoiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvd2ViX3BhZ2UvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiaWQiOiIzYjVlNGYwMi1jY2ZjLTQ5YTUtODU3OS0xNTQxY2Q2MjZmMDUifX1dfQ"
              ),
              "stm" -> Json.fromString("1722786322184")
            )
          )
        )
      )
    )
    val _ = createCX2

    // TODO: Remove me
    println("createSnowplowPayload")
    println(Json.fromJsonObject(jsonObject).spaces2SortKeys)
    io.circe.Json.fromJsonObject(jsonObject)
  }
}
