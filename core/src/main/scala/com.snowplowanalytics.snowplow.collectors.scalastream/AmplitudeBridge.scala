package com.snowplowanalytics.snowplow.collectors.scalastream

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.HttpCookie
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._

object AmplitudeBridge {

  import io.circe._

  import java.nio.charset.StandardCharsets
  import java.util.Base64

  val jsonResponse: Json = Json.fromJsonObject(JsonObject("success" -> Json.fromBoolean(true)))

  private val Vendor  = "com.amplitude"
  private val Version = "v1"

  case class AmplitudeEvent(
    deviceId: Option[String],
    userId: Option[String],
    time: Option[Long],
    eventData: Json
  )

  case class BatchRequest(
    apiKey: String,
    events: List[AmplitudeEvent]
  )

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
  ): Route =
    pathPrefix(Vendor / Version) {
      path("httpapi" | "batch") {
        post {
          extractContentType { ct =>
            entity(as[String]) { body =>
              handleAmplitudeRequest(
                body,
                ct,
                queryString,
                cookie,
                userAgent,
                refererUri,
                hostname,
                ip,
                doNotTrack,
                request,
                spAnonymous,
                collectorService
              )
            }
          }
        }
      }
    }

  private def handleAmplitudeRequest(
    body: String,
    ct: ContentType,
    queryString: Option[String],
    cookie: Option[HttpCookie],
    userAgent: Option[String],
    refererUri: Option[String],
    hostname: String,
    ip: RemoteAddress,
    doNotTrack: Boolean,
    request: HttpRequest,
    spAnonymous: Option[String],
    collectorService: Service
  ): Route =
    // Parse the JSON body
    io.circe.parser.parse(body) match {
      case Right(parsedBody) =>
        // Extract api_key
        val apiKeyOpt = parsedBody.hcursor.get[String]("api_key").toOption
        apiKeyOpt match {
          case Some(apiKey) =>
            // Extract events array
            parsedBody.hcursor.get[List[Json]]("events") match {
              case Right(eventsJson) if eventsJson.nonEmpty =>
                // Parse Amplitude cookies
                val ampCookieName     = s"AMP_$apiKey"
                val ampMktgCookieName = s"AMP_MKTG_$apiKey"

                optionalCookie(ampCookieName) { ampCookie =>
                  optionalCookie(ampMktgCookieName) { _ =>
                    // Decode device_id from cookie if present
                    val cookieDeviceId = ampCookie.flatMap { c =>
                      try {
                        val decoded = new String(Base64.getDecoder.decode(c.value), StandardCharsets.UTF_8)
                        io.circe.parser.parse(decoded).toOption.flatMap(_.hcursor.get[String]("deviceId").toOption)
                      } catch {
                        case _: Exception => None
                      }
                    }

                    // Path for Snowplow event
                    val path = "/com.snowplowanalytics.snowplow/tp2"

                    // Process each event in the batch
                    eventsJson.foreach { eventJson =>
                      val cursor = eventJson.hcursor

                      // Extract fields from event
                      val deviceId = cursor.get[String]("device_id").toOption.orElse(cookieDeviceId)
                      val userId   = cursor.get[String]("user_id").toOption
                      val time     = cursor.get[Long]("time").toOption

                      val amplitudeEvent = AmplitudeEvent(
                        deviceId = deviceId,
                        userId = userId,
                        time = time,
                        eventData = eventJson
                      )

                      // Call collector service for each event
                      collectorService.cookie(
                        queryString = queryString,
                        body = Some(eventJson.noSpaces),
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
                        amplitudeEvent = Some(amplitudeEvent)
                      )
                    }

                    // Return success response
                    // Set Amplitude cookies in response
                    val responseCookies = List(
                      cookieDeviceId
                        .orElse(eventsJson.headOption.flatMap(_.hcursor.get[String]("device_id").toOption))
                        .map { did =>
                          val cookieData = Json.obj("deviceId" -> Json.fromString(did))
                          val encoded =
                            Base64.getEncoder.encodeToString(cookieData.noSpaces.getBytes(StandardCharsets.UTF_8))
                          HttpCookie(ampCookieName, encoded, path = Some("/"), maxAge = Some(31536000))
                        }
                    ).flatten

                    val response = HttpResponse(
                      StatusCodes.OK,
                      entity = HttpEntity(ContentTypes.`application/json`, jsonResponse.noSpaces)
                    )

                    val responseWithCookies = if (responseCookies.nonEmpty) {
                      response.withHeaders(responseCookies.map(c => headers.`Set-Cookie`(c)))
                    } else {
                      response
                    }

                    complete(responseWithCookies)
                  }
                }
              case Right(_) =>
                complete(HttpResponse(StatusCodes.BadRequest, entity = "Events array is empty"))
              case Left(_) =>
                complete(HttpResponse(StatusCodes.BadRequest, entity = "Missing or invalid events array"))
            }
          case None =>
            complete(HttpResponse(StatusCodes.BadRequest, entity = "Missing api_key"))
        }
      case Left(error) =>
        complete(HttpResponse(StatusCodes.BadRequest, entity = s"Invalid JSON: ${error.getMessage}"))
    }

  def createSnowplowPayload(eventData: Json, event: AmplitudeEvent, networkUserId: String): Json = {
    import java.nio.charset.StandardCharsets
    import java.util.Base64

    val eventSchema = "iglu:com.amplitude/payload/jsonschema/1-0-0"

    def eventPayload = {
      val jsonObject = JsonObject(
        "schema" -> Json.fromString("iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0"),
        "data" -> Json.fromJsonObject(
          JsonObject(
            "schema" -> Json.fromString(eventSchema),
            "data"   -> Json.fromJsonObject(JsonObject("data" -> eventData))
          )
        )
      )
      Json.fromJsonObject(jsonObject)
    }

    val initialData = JsonObject(
      "aid" -> Json.fromString("amplitude_bridge"),
      // self-describing event
      "e" -> Json.fromString("ue"),
      // platform
      "p" -> Json.fromString("web"),
      // base64-encoded event
      "ue_px" -> Json.fromString(
        Base64.getEncoder.encodeToString(eventPayload.noSpaces.getBytes(StandardCharsets.UTF_8))
      ),
      // network_userid
      "tnuid" -> Json.fromString(networkUserId)
    )

    // Add optional fields
    val userId          = "uid"  -> event.userId
    val domainUserId    = "duid" -> event.deviceId
    val deviceTimestamp = "dtm"  -> event.time.map(t => Json.fromLong(t))

    // Merge optional entries
    val optionalEntries = List(userId, domainUserId, deviceTimestamp)
    val data = optionalEntries.foldLeft(initialData) {
      case (acc, (key, Some(value: String))) => acc.add(key, Json.fromString(value))
      case (acc, (key, Some(value: Json)))   => acc.add(key, value)
      case (acc, _)                          => acc
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
