/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.telemetry

import cats.data.NonEmptyList

import org.apache.pekko.actor.ActorSystem

import io.circe.Json

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.snowplow.scalatracker.Emitter.EndpointParams
import com.snowplowanalytics.snowplow.scalatracker.Tracker
import com.snowplowanalytics.snowplow.scalatracker.idimplicits._
import com.snowplowanalytics.snowplow.scalatracker.emitters.id.SyncEmitter
import com.snowplowanalytics.snowplow.scalatracker.Emitter._
import com.snowplowanalytics.snowplow.collectors.scalastream.model.{
  CollectorConfig,
  GooglePubSub,
  Kinesis,
  Sqs,
  TelemetryConfig
}

/** Pekko implementation of telemetry service.
  *
  * @param teleCfg - telemetry configuration
  * @param cloud - cloud vendor
  * @param region - deployment region
  * @param appName - application name as defined during build (take it from BuildInfo)
  * @param appVersion - application name as defined during build (take it from BuildInfo)
  */
case class TelemetryPekkoService(
  teleCfg: TelemetryConfig,
  cloud: Option[CloudVendor],
  region: Option[String],
  appName: String,
  appVersion: String
) {
  private lazy val log: Logger = LoggerFactory.getLogger(getClass)

  private lazy val payload: SelfDescribingData[Json] = makeHeartbeatEvent(teleCfg, cloud, region, appName, appVersion)

  def start()(implicit actorSystem: ActorSystem): Unit =
    if (teleCfg.disable) {
      log.info(s"Telemetry disabled")
    } else {
      log.info(s"Telemetry enabled")
      val scheduler                                   = actorSystem.scheduler
      implicit val executor: ExecutionContextExecutor = actorSystem.dispatcher

      def emitterCallback(params: EndpointParams, req: Request, res: Result): Unit =
        res match {
          case Result.Success(_) => log.debug(s"telemetry send successfully")
          case Result.Failure(code) =>
            log.warn(s"Scala telemetry tracker got unexpected HTTP code $code from ${params.getUri}")
          case Result.TrackerFailure(exception) =>
            log.warn(
              s"Scala telemetry tracker failed to reach ${params.getUri} with following exception $exception" +
                s" after ${req.attempt} attempt"
            )
          case Result.RetriesExceeded(failure) =>
            log.warn(s"Scala telemetry tracker has stopped trying to deliver payload after following failure: $failure")
        }

      val emitter: SyncEmitter = SyncEmitter(
        EndpointParams(teleCfg.url, port = teleCfg.port, https = teleCfg.secure),
        callback = Some(emitterCallback)
      )
      // telemetry - Unique identifier for website / application (aid)
      // root - The tracker namespace (tna)
      val tracker = new Tracker(NonEmptyList.of(emitter), "telemetry", appName)

      // discarding cancellation handle
      val _ = scheduler.scheduleAtFixedRate(
        initialDelay = Duration(0, TimeUnit.SECONDS),
        interval = teleCfg.interval
      ) { () =>
        tracker.trackSelfDescribingEvent(unstructEvent = payload)
        tracker.flushEmitters() // this is important!
      }
    }
}

object TelemetryPekkoService {

  /** Specialized version of [[TelemetryPekkoService]] for collector. That takes CollectorConfig as an input.
    *
    * @param collectorConfig - Top level collector configuration
    * @param appName - application name as defined during build (take it from BuildInfo)
    * @param appVersion - application name as defined during build (take it from BuildInfo)
    * @return heartbeat event. Same event should be used for all heartbeats.
    */
  def initWithCollector(
    collectorConfig: CollectorConfig,
    appName: String,
    appVersion: String
  ): TelemetryPekkoService = {

    val (cloud, region) = collectorConfig.streams.sink match {
      case k: Kinesis      => (Some(CloudVendor.Aws), Some(k.region))
      case s: Sqs          => (Some(CloudVendor.Aws), Some(s.region))
      case _: GooglePubSub => (Some(CloudVendor.Gcp), None)
      case _               => (None, None)
    }

    TelemetryPekkoService(
      collectorConfig.telemetry.getOrElse(TelemetryConfig()),
      cloud,
      region,
      appName,
      appVersion
    )
  }
}
