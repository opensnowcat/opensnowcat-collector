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
  /** The first path segment used for dispatch */
  def vendor: String

  /** The second path segment used for dispatch */
  def version: String

  /** Handle the request after vendor/version has already been consumed from the path.
    * Only the remaining path segments need to be matched here.
    */
  def route(ctx: BridgeContext): Route
}
