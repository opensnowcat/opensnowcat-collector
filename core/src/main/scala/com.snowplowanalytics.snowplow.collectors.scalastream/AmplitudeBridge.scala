package com.snowplowanalytics.snowplow.collectors.scalastream

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._

import com.snowplowanalytics.snowplow.collectors.scalastream.model.CrossDomainConfig

object AmplitudeBridge {

  import io.circe._

  val jsonResponse: Json = Json.fromJsonObject(JsonObject("success" -> Json.fromBoolean(true)))

  private val Vendor  = "com.amplitude"
  private val Version = "2"

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

  /** Check if a host matches one of the allowed domains.
    * Supports wildcard matching: *.example.com matches sub.example.com
    * Also matches exact domain: example.com matches example.com
    */
  private def isOriginAllowed(originHost: String, domains: List[String]): Boolean =
    domains.exists { domain =>
      if (domain == "*") true
      else if (domain.startsWith("*.")) {
        val suffix = domain.substring(1) // Remove the "*", keep ".example.com"
        originHost.endsWith(suffix) || originHost == domain.substring(2)
      } else {
        originHost == domain || originHost.endsWith("." + domain)
      }
    }

  /** Extract the host from an Origin header */
  private def extractOriginHost(request: HttpRequest): Option[String] =
    request.headers.collectFirst {
      case Origin(origins) if origins.nonEmpty => origins.head.host.host.address()
    }

  /** Build CORS headers based on crossDomain config.
    * - If crossDomain.enabled = false, allow all origins (like Amplitude)
    * - If crossDomain.enabled = true AND domains = ["*"], allow all origins
    * - If crossDomain.enabled = true AND specific domains listed, whitelist those
    */
  private def buildCorsHeaders(request: HttpRequest, crossDomainConfig: CrossDomainConfig): List[HttpHeader] = {
    val originHeader = extractOriginHost(request) match {
      case Some(originHost) =>
        if (crossDomainConfig.enabled && !crossDomainConfig.domains.contains("*")) {
          // Whitelisting enabled with specific domains
          if (isOriginAllowed(originHost, crossDomainConfig.domains)) {
            // Origin is in whitelist - echo it back
            request.headers.collectFirst { case Origin(origins) =>
              `Access-Control-Allow-Origin`(origins.head)
            }
          } else {
            // Origin not in whitelist - don't set CORS header (browser will block)
            None
          }
        } else {
          // No whitelisting (disabled or domains = ["*"]) - allow all (like Amplitude)
          request.headers.collectFirst { case Origin(origins) =>
            `Access-Control-Allow-Origin`(HttpOriginRange.Default(origins))
          }
        }
      case None =>
        // No Origin header - allow all
        Some(`Access-Control-Allow-Origin`(HttpOriginRange.`*`))
    }

    originHeader.toList ++ List(
      `Access-Control-Allow-Methods`(HttpMethods.POST, HttpMethods.OPTIONS),
      `Access-Control-Allow-Headers`("Content-Type"),
      `Access-Control-Max-Age`(3600)
    )
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
  ): Route = {
    val crossDomainConfig = collectorService.crossDomainConfig
    pathPrefix(Vendor / Version) {
      path("httpapi" | "batch") {
        // Handle CORS preflight
        options {
          val corsHeaders = buildCorsHeaders(request, crossDomainConfig)
          if (corsHeaders.exists(_.isInstanceOf[`Access-Control-Allow-Origin`])) {
            complete(HttpResponse(StatusCodes.OK).withHeaders(corsHeaders))
          } else {
            // Origin not allowed - return 403
            complete(HttpResponse(StatusCodes.Forbidden, entity = "Origin not allowed"))
          }
        } ~
          // Handle POST requests
          post {
            val corsHeaders = buildCorsHeaders(request, crossDomainConfig)
            // Check if origin is allowed before processing
            if (corsHeaders.exists(_.isInstanceOf[`Access-Control-Allow-Origin`])) {
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
                    collectorService,
                    corsHeaders
                  )
                }
              }
            } else {
              complete(HttpResponse(StatusCodes.Forbidden, entity = "Origin not allowed"))
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
    collectorService: Service,
    corsHeaders: List[HttpHeader]
  ): Route =
    // Parse the JSON body
    io.circe.parser.parse(body) match {
      case Right(parsedBody) =>
        // Extract api_key
        val apiKeyOpt = parsedBody.hcursor.get[String]("api_key").toOption
        apiKeyOpt match {
          case Some(_) =>
            // Extract events array
            parsedBody.hcursor.get[List[Json]]("events") match {
              case Right(eventsJson) if eventsJson.nonEmpty =>
                // Path for Snowplow event
                val path = "/com.snowplowanalytics.snowplow/tp2"

                // Get the actual IP address from RemoteAddress
                val actualIp = ip.toOption.map(_.getHostAddress).getOrElse("unknown")

                // Process each event in the batch
                eventsJson.foreach { eventJson =>
                  val cursor = eventJson.hcursor

                  // Replace $remote placeholder with actual IP address
                  val eventWithIp = cursor.get[String]("ip").toOption match {
                    case Some("$remote") =>
                      eventJson.hcursor.downField("ip").set(Json.fromString(actualIp)).top.getOrElse(eventJson)
                    case _ => eventJson
                  }
                  val updatedCursor = eventWithIp.hcursor

                  // Extract fields from event
                  val deviceId = updatedCursor.get[String]("device_id").toOption
                  val userId   = updatedCursor.get[String]("user_id").toOption
                  val time     = updatedCursor.get[Long]("time").toOption

                  val amplitudeEvent = AmplitudeEvent(
                    deviceId = deviceId,
                    userId = userId,
                    time = time,
                    eventData = eventWithIp
                  )

                  // Call collector service for each event
                  collectorService.cookie(
                    queryString = queryString,
                    body = Some(eventWithIp.noSpaces),
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

                // Return success response with CORS headers
                // Note: We don't set Amplitude cookies - the client SDK handles that
                val response = HttpResponse(
                  StatusCodes.OK,
                  entity = HttpEntity(ContentTypes.`application/json`, jsonResponse.noSpaces)
                ).withHeaders(corsHeaders)

                complete(response)

              case Right(_) =>
                complete(
                  HttpResponse(StatusCodes.BadRequest, entity = "Events array is empty").withHeaders(corsHeaders)
                )
              case Left(_) =>
                complete(
                  HttpResponse(StatusCodes.BadRequest, entity = "Missing or invalid events array")
                    .withHeaders(corsHeaders)
                )
            }
          case None =>
            complete(HttpResponse(StatusCodes.BadRequest, entity = "Missing api_key").withHeaders(corsHeaders))
        }
      case Left(error) =>
        complete(
          HttpResponse(StatusCodes.BadRequest, entity = s"Invalid JSON: ${error.getMessage}").withHeaders(corsHeaders)
        )
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

    // Extract tracker version from Amplitude library field (required)
    val trackerVersion = eventData.hcursor.get[String]("library").toOption.getOrElse("amplitude-unknown")

    val initialData = JsonObject(
      "aid" -> Json.fromString("amp_bridge"),
      // self-describing event
      "e" -> Json.fromString("ue"),
      // tracker version (required)
      "tv" -> Json.fromString(trackerVersion),
      // platform
      "p" -> Json.fromString("app"),
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
    val deviceTimestamp = "dtm"  -> event.time.map(t => Json.fromString(t.toString))

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
