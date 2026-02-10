package com.snowplowanalytics.snowplow.collectors.scalastream

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.HttpCookie
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}

case class BridgeContext(
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
)

trait Bridge {
  def route(ctx: BridgeContext): Route
}
