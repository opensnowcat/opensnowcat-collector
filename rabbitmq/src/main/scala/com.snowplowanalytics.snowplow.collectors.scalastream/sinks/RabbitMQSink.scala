/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats.syntax.either._

import com.rabbitmq.client.Channel

import com.snowplowanalytics.snowplow.collectors.scalastream.model.RabbitMQBackoffPolicyConfig

class RabbitMQSink(
  val maxBytes: Int,
  channel: Channel,
  exchangeName: String,
  backoffPolicy: RabbitMQBackoffPolicyConfig,
  executionContext: ExecutionContext
) extends Sink {

  implicit val ec = executionContext

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    if (events.nonEmpty) {
      log.info(
        s"Sending ${events.size} Thrift records to exchange $exchangeName"
      )
      Future.sequence(events.map(e => sendOneEvent(e))).onComplete {
        case Success(_) =>
          log.debug(
            s"${events.size} events successfully sent to exchange $exchangeName"
          )
        // We should never reach this as the writing of each individual event is retried forever
        case Failure(e) =>
          throw new RuntimeException(s"Error happened during the sending of ${events.size} events: ${e.getMessage}")
      }
    }

  private def sendOneEvent(bytes: Array[Byte], currentBackOff: Option[FiniteDuration] = None): Future[Unit] =
    Future {
      if (currentBackOff.isDefined) Thread.sleep(currentBackOff.get.toMillis)
      channel.basicPublish(exchangeName, "", null, bytes)
    }.recoverWith { case e =>
      val nextBackOff =
        currentBackOff match {
          case Some(current) =>
            (backoffPolicy.multiplier * current.toMillis).toLong.min(backoffPolicy.maxBackoff).millis
          case None =>
            backoffPolicy.minBackoff.millis
        }
      log.error(s"Sending of event failed with error: ${e.getMessage}. Retrying in $nextBackOff")
      sendOneEvent(bytes, Some(nextBackOff))
    }

  override def shutdown(): Unit = ()
}

object RabbitMQSink {
  def init(
    maxBytes: Int,
    channel: Channel,
    exchangeName: String,
    backoffPolicy: RabbitMQBackoffPolicyConfig,
    executionContext: ExecutionContext
  ): Either[Throwable, RabbitMQSink] =
    for {
      _ <- Either.catchNonFatal(channel.exchangeDeclarePassive(exchangeName))
    } yield new RabbitMQSink(maxBytes, channel, exchangeName, backoffPolicy, executionContext)
}
