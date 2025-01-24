/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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

import cats.syntax.either._

import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors

import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}

import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryPekkoService
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.RabbitMQSink

object RabbitMQCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryPekkoService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks: Either[Throwable, CollectorSinks] =
      for {
        config <- collectorConf.streams.sink match {
          case rabbit: Rabbitmq => rabbit.asRight
          case _                => new IllegalArgumentException("Configured sink is not RabbitMQ").asLeft
        }
        rabbitMQ <- initRabbitMQ(config)
        (connection, channel) = rabbitMQ
        _                     = Runtime.getRuntime().addShutdownHook(shutdownHook(connection, channel))
        threadPool            = initThreadPool(config.threadPoolSize)
        goodSink <- RabbitMQSink.init(
          config.maxBytes,
          channel,
          collectorConf.streams.good,
          config.backoffPolicy,
          threadPool
        )
        badSink <- RabbitMQSink.init(
          config.maxBytes,
          channel,
          collectorConf.streams.bad,
          config.backoffPolicy,
          threadPool
        )
      } yield CollectorSinks(goodSink, badSink)

    sinks match {
      case Right(s) => run(collectorConf, akkaConf, s, telemetry)
      case Left(e) =>
        e.printStackTrace
        System.exit(1)
    }
  }

  private def initRabbitMQ(config: Rabbitmq): Either[Throwable, (Connection, Channel)] =
    Either.catchNonFatal {
      val factory = new ConnectionFactory()
      factory.setUsername(config.username)
      factory.setPassword(config.password)
      factory.setVirtualHost(config.virtualHost)
      factory.setHost(config.host)
      factory.setPort(config.port)
      val connection = factory.newConnection()
      val channel    = connection.createChannel()
      (connection, channel)
    }

  private def initThreadPool(size: Option[Int]): ExecutionContext =
    size match {
      case Some(s) => ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(s))
      case None    => ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
    }

  private def shutdownHook(connection: Connection, channel: Channel) =
    new Thread() {
      override def run() {
        if (channel.isOpen) channel.close()
        if (connection.isOpen) connection.close()
      }
    }
}
