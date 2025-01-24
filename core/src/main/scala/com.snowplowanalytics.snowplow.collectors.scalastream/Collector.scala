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

import java.io.File
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import org.apache.pekko.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.ConfigSource
import com.timgroup.statsd.NonBlockingStatsDClientBuilder
import fr.davit.akka.http.metrics.core.HttpMetricsRegistry
import fr.davit.akka.http.metrics.core.HttpMetrics._
import fr.davit.akka.http.metrics.datadog.{DatadogRegistry, DatadogSettings}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.Sink
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryPekkoService

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

// Main entry point of the Scala collector.
trait Collector {

  def appName: String

  def scalaVersion: String

  def appVersion: String

  lazy val log = LoggerFactory.getLogger(getClass())

  // Used as an option prefix when reading system properties.
  val Namespace = "collector"

  /** Optionally give precedence to configs wrapped in a "snowplow" block. To help avoid polluting system namespace */
  private def namespaced(config: Config): Config =
    if (config.hasPath(Namespace))
      config.getConfig(Namespace).withFallback(config.withoutPath(Namespace))
    else
      config

  def parseConfig(args: Array[String]): (CollectorConfig, Config) = {
    case class FileConfig(config: File = new File("."))

    val parser = new scopt.OptionParser[FileConfig](appName) {
      head(appName, appVersion)
      help("help")
      version("version")
      opt[File]("config")
        .optional()
        .valueName("<filename>")
        .action((f: File, c: FileConfig) => c.copy(f))
        .validate(f =>
          if (f.exists) success
          else failure(s"Configuration file $f does not exist")
        )
    }

    val resolved = parser.parse(args, FileConfig()) match {
      case Some(c) => ConfigFactory.parseFile(c.config).resolve()
      case None    => ConfigFactory.empty()
    }

    val conf = namespaced(ConfigFactory.load(namespaced(resolved.withFallback(namespaced(ConfigFactory.load())))))

    (ConfigSource.fromConfig(conf).loadOrThrow[CollectorConfig], conf)
  }

  def run(
    collectorConf: CollectorConfig,
    akkaConf: Config,
    sinks: CollectorSinks,
    telemetry: TelemetryPekkoService
  ): Unit = {

    implicit val system           = ActorSystem.create("scala-stream-collector", akkaConf)
    implicit val executionContext = system.dispatcher

    telemetry.start()

    val health = new HealthService.Settable

    val collectorRoute = new CollectorRoute {
      override def collectorService = new CollectorService(collectorConf, sinks, appName, appVersion)
      override def healthService    = health
    }

    lazy val redirectRoutes =
      scheme("http") {
        redirectToHttps(collectorConf.ssl.port)
      }

    def redirectToHttps(port: Int) =
      extract(_.request.uri) { uri =>
        redirect(
          uri.withScheme("https").withPort(port),
          StatusCodes.MovedPermanently
        )
      }

    def xForwardedProto(routes: Route): Route =
      if (collectorConf.ssl.redirect)
        optionalHeaderValueByName("X-Forwarded-Proto") {
          case Some(clientProtocol) if clientProtocol == "http" =>
            redirectToHttps(0)
          case _ => routes
        }
      else
        routes

    def shutdownHook(binding: Http.ServerBinding): Http.ServerBinding =
      binding.addToCoordinatedShutdown(collectorConf.terminationDeadline)
    def startupHook(binding: Http.ServerBinding): Unit = log.info(s"REST interface bound to ${binding.localAddress}")
    def errorHook(ex: Throwable): Unit = log.error(
      "REST interface could not be bound to " +
        s"${collectorConf.interface}:${collectorConf.port}",
      ex.getMessage
    )

    lazy val metricRegistry: Option[HttpMetricsRegistry] = collectorConf.monitoring.metrics.statsd match {
      case StatsdConfig(true, hostname, port, period, prefix, tags) =>
        val constantTags = tags.map { case (k: String, v: String) => s"${k}:${v}" }

        Some(
          DatadogRegistry(
            client = new NonBlockingStatsDClientBuilder()
              .hostname(hostname)
              .port(port)
              .enableAggregation(true)
              .aggregationFlushInterval(period.toMillis.toInt)
              .enableTelemetry(false)
              .constantTags(constantTags.toArray: _*)
              .build(),
            DatadogSettings
              .default
              .withNamespace(prefix)
              .withIncludeMethodDimension(true)
              .withIncludeStatusDimension(true)
          )
        )
      case _ => None
    }

    def secureEndpoint(metricRegistry: Option[HttpMetricsRegistry]): Future[Unit] =
      endpoint(xForwardedProto(collectorRoute.collectorRoute), collectorConf.ssl.port, true, metricRegistry)

    def unsecureEndpoint(routes: Route, metricRegistry: Option[HttpMetricsRegistry]): Future[Unit] =
      endpoint(xForwardedProto(routes), collectorConf.port, false, metricRegistry).flatMap { _ =>
        Warmup.run(collectorConf.interface, collectorConf.port, collectorConf.experimental.warmup)
      }

    def endpoint(
      routes: Route,
      port: Int,
      secure: Boolean,
      metricRegistry: Option[HttpMetricsRegistry]
    ): Future[Unit] =
      metricRegistry match {
        case Some(r) =>
          val builder = Http().newMeteredServerAt(collectorConf.interface, port, r)
          val stage   = if (secure) builder.enableHttps(ConnectionContext.httpsServer(SSLContext.getDefault)) else builder
          stage.bind(routes).map(shutdownHook).map(startupHook).recover { case ex =>
            errorHook(ex)
          }
        case None =>
          val builder = Http().newServerAt(collectorConf.interface, port)
          val stage   = if (secure) builder.enableHttps(ConnectionContext.httpsServer(SSLContext.getDefault)) else builder
          stage.bind(routes).map(shutdownHook).map(startupHook).recover { case ex =>
            errorHook(ex)
          }
      }

    val binds = collectorConf.ssl match {
      case SSLConfig(true, true, _) =>
        List(
          unsecureEndpoint(redirectRoutes, metricRegistry),
          secureEndpoint(metricRegistry)
        )
      case SSLConfig(true, false, _) =>
        List(
          unsecureEndpoint(collectorRoute.collectorRoute, metricRegistry),
          secureEndpoint(metricRegistry)
        )
      case _ =>
        List(unsecureEndpoint(collectorRoute.collectorRoute, metricRegistry))
    }

    Future.sequence(binds).foreach { _ =>
      Runtime
        .getRuntime
        .addShutdownHook(new Thread(() => {
          log.warn("Received shutdown signal")
          if (collectorConf.preTerminationUnhealthy) {
            log.warn("Setting health endpoint to unhealthy")
            health.toUnhealthy()
          }
          log.warn(s"Sleeping for ${collectorConf.preTerminationPeriod}")
          Thread.sleep(collectorConf.preTerminationPeriod.toMillis)
          log.warn("Initiating http server termination")
          try {
            // The actor system is already configured to shutdown within `terminationDeadline` so we await for double that.
            Await.result(system.terminate(), collectorConf.terminationDeadline * 2)
            log.warn("Server terminated")
          } catch {
            case NonFatal(t) =>
              log.error("Caught exception awaiting server termination", t)
          }
          val shutdowns = List(shutdownSink(sinks.good, "good"), shutdownSink(sinks.bad, "bad"))
          Await.result(Future.sequence(shutdowns), Duration.Inf)
          ()
        }))

      log.info("Setting health endpoint to healthy")
      health.toHealthy()
    }
  }

  private def shutdownSink(sink: Sink, label: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      log.warn(s"Initiating $label sink shutdown")
      sink.shutdown()
      log.warn(s"Completed $label sink shutdown")
    }.recover { case NonFatal(t) =>
      log.error(s"Caught exception shutting down $label sink", t)
    }
}
