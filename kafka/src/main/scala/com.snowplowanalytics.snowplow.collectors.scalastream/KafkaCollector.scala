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
package com.snowplowanalytics.snowplow.collectors.scalastream

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KafkaSink
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryPekkoService
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo

object KafkaCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryPekkoService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks = {
      val goodStream = collectorConf.streams.good
      val badStream  = collectorConf.streams.bad
      val bufferConf = collectorConf.streams.buffer
      val (good, bad) = collectorConf.streams.sink match {
        case kc: Kafka =>
          (
            new KafkaSink(kc.maxBytes, kc, bufferConf, goodStream),
            new KafkaSink(kc.maxBytes, kc, bufferConf, badStream)
          )
        case _ => throw new IllegalArgumentException("Configured sink is not Kafka")
      }
      CollectorSinks(good, bad)
    }
    run(collectorConf, akkaConf, sinks, telemetry)
  }
}
