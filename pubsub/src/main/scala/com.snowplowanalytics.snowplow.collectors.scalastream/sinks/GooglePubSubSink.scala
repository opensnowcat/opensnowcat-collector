/*
 * Copyright (c) 2013-2023 Snowplow Analytics Ltd. All rights reserved.
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

import java.util.concurrent.Executors

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util._
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.duration._

import org.threeten.bp.Duration

import com.google.api.core.{ApiFutureCallback, ApiFutures}
import com.google.api.gax.batching.BatchingSettings
import com.google.api.gax.retrying.RetrySettings
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{
  ApiException,
  FixedHeaderProvider,
  FixedTransportChannelProvider,
  TransportChannelProvider
}
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminClient, TopicAdminSettings}
import com.google.pubsub.v1.{ProjectName, ProjectTopicName, PubsubMessage, TopicName}
import com.google.protobuf.ByteString

import io.grpc.ManagedChannelBuilder

import cats.syntax.either._

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

class GooglePubSubSink private (
  val maxBytes: Int,
  publisher: Publisher,
  projectId: String,
  topicName: String,
  retryInterval: FiniteDuration
) extends Sink {
  private val logExecutor = Executors.newSingleThreadExecutor()
  // 2 = 1 for health check + 1 for retrying failed inserts
  private val scheduledExecutor = Executors.newScheduledThreadPool(2)

  private val failedInsertsBuffer = ListBuffer.empty[Array[Byte]]

  @volatile private var pubsubHealthy: Boolean = false
  override def isHealthy: Boolean              = pubsubHealthy

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    if (events.nonEmpty) {
      log.debug(s"Writing ${events.size} records to PubSub topic $topicName")

      events.foreach { event =>
        publisher.asRight.map { p =>
          val future = p.publish(eventToPubsubMessage(event))
          ApiFutures.addCallback(
            future,
            new ApiFutureCallback[String]() {
              override def onSuccess(messageId: String): Unit = {
                pubsubHealthy = true
                log.debug(s"Successfully published event with id $messageId to $topicName")
              }

              override def onFailure(throwable: Throwable): Unit = {
                pubsubHealthy = false
                throwable match {
                  case apiEx: ApiException =>
                    val retryable = if (apiEx.isRetryable()) "retryable" else "non-retryable"
                    log.error(
                      s"Publishing message to $topicName failed with code ${apiEx.getStatusCode} and $retryable error: ${apiEx.getMessage}"
                    )
                  case t => log.error(s"Publishing message to $topicName failed with error: ${t.getMessage}")
                }
                failedInsertsBuffer.synchronized {
                  failedInsertsBuffer.prepend(event)
                }
              }
            },
            logExecutor
          )
        }
      }
    }

  override def shutdown(): Unit = {
    publisher.shutdown()
    scheduledExecutor.shutdown()
    scheduledExecutor.awaitTermination(10000, MILLISECONDS)
    ()
  }

  /** Convert event bytes to a PubsubMessage to be published
    * @param event Event to be converted
    * @return a PubsubMessage
    */
  private def eventToPubsubMessage(event: Array[Byte]): PubsubMessage =
    PubsubMessage.newBuilder.setData(ByteString.copyFrom(event)).build()

  private def retryRunnable: Runnable = new Runnable {
    override def run() {
      val failedInserts = failedInsertsBuffer.synchronized {
        val records = failedInsertsBuffer.toList
        failedInsertsBuffer.clear()
        records
      }
      if (failedInserts.nonEmpty) {
        log.info(s"Retrying to insert ${failedInserts.size} records into $topicName")
        storeRawEvents(failedInserts, "NOT USED")
      }
    }
  }
  scheduledExecutor.scheduleWithFixedDelay(retryRunnable, retryInterval.toMillis, retryInterval.toMillis, MILLISECONDS)

  private def checkPubsubHealth(
    customProviders: Option[(TransportChannelProvider, CredentialsProvider)],
    startupCheckInterval: FiniteDuration
  ): Unit = {
    val healthRunnable = new Runnable {
      override def run() {
        val topicAdmin = GooglePubSubSink.createTopicAdmin(customProviders)

        while (!pubsubHealthy)
          GooglePubSubSink.topicExists(topicAdmin, projectId, topicName) match {
            case Right(true) =>
              log.info(s"Topic $topicName exists")
              pubsubHealthy = true
            case Right(false) =>
              log.error(s"Topic $topicName doesn't exist")
              Thread.sleep(startupCheckInterval.toMillis)
            case Left(err) =>
              log.error(s"Error while checking if topic $topicName exists: ${err.getCause()}")
              Thread.sleep(startupCheckInterval.toMillis)
          }

        Either.catchNonFatal(topicAdmin.close()) match {
          case Right(_) =>
          case Left(err) =>
            log.error(s"Error when closing topicAdmin: ${err.getMessage()}")
        }
      }
    }
    scheduledExecutor.execute(healthRunnable)
  }
}

/** GooglePubSubSink companion object with factory method */
object GooglePubSubSink {
  def createAndInitialize(
    maxBytes: Int,
    googlePubSubConfig: GooglePubSub,
    bufferConfig: BufferConfig,
    topicName: String
  ): Either[Throwable, GooglePubSubSink] =
    for {
      batching <- batchingSettings(bufferConfig).asRight
      retry = retrySettings(googlePubSubConfig.backoffPolicy)
      customProviders = sys.env.get("PUBSUB_EMULATOR_HOST").map { hostPort =>
        val channel             = ManagedChannelBuilder.forTarget(hostPort).usePlaintext().build()
        val channelProvider     = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider = NoCredentialsProvider.create()
        (channelProvider, credentialsProvider)
      }
      publisher <- createPublisher(
        googlePubSubConfig.googleProjectId,
        topicName,
        batching,
        retry,
        customProviders,
        googlePubSubConfig.gcpUserAgent
      )
      sink = new GooglePubSubSink(
        maxBytes,
        publisher,
        googlePubSubConfig.googleProjectId,
        topicName,
        googlePubSubConfig.retryInterval
      )
      _ = sink.checkPubsubHealth(customProviders, googlePubSubConfig.startupCheckInterval)
    } yield sink

  /** Instantiates a Publisher on a topic with the given configuration options.
    * This can fail if the publisher can't be created.
    * @return a PubSub publisher or an error
    */
  private def createPublisher(
    projectId: String,
    topicName: String,
    batchingSettings: BatchingSettings,
    retrySettings: RetrySettings,
    customProviders: Option[(TransportChannelProvider, CredentialsProvider)],
    gcpUserAgent: GcpUserAgent
  ): Either[Throwable, Publisher] = {
    val builder = Publisher
      .newBuilder(ProjectTopicName.of(projectId, topicName))
      .setBatchingSettings(batchingSettings)
      .setRetrySettings(retrySettings)
      .setHeaderProvider(FixedHeaderProvider.create("User-Agent", createUserAgent(gcpUserAgent)))
    customProviders.foreach { case (channelProvider, credentialsProvider) =>
      builder.setChannelProvider(channelProvider).setCredentialsProvider(credentialsProvider)
    }
    Either.catchNonFatal(builder.build()).leftMap(e => new RuntimeException("Couldn't build PubSub publisher", e))
  }

  private[sinks] def createUserAgent(gcpUserAgent: GcpUserAgent): String =
    s"${gcpUserAgent.productName}/collector (GPN:Snowplow;)"

  private def batchingSettings(bufferConfig: BufferConfig): BatchingSettings =
    BatchingSettings
      .newBuilder()
      .setElementCountThreshold(bufferConfig.recordLimit)
      .setRequestByteThreshold(bufferConfig.byteLimit)
      .setDelayThreshold(Duration.ofMillis(bufferConfig.timeLimit))
      .build()

  /** Defaults are used for the rpc configuration, see Publisher.java */
  private def retrySettings(backoffPolicy: GooglePubSubBackoffPolicyConfig): RetrySettings =
    RetrySettings
      .newBuilder()
      .setInitialRetryDelay(Duration.ofMillis(backoffPolicy.minBackoff))
      .setMaxRetryDelay(Duration.ofMillis(backoffPolicy.maxBackoff))
      .setRetryDelayMultiplier(backoffPolicy.multiplier)
      .setTotalTimeout(Duration.ofMillis(backoffPolicy.totalBackoff))
      .setInitialRpcTimeout(Duration.ofMillis(backoffPolicy.initialRpcTimeout))
      .setRpcTimeoutMultiplier(backoffPolicy.rpcTimeoutMultiplier)
      .setMaxRpcTimeout(Duration.ofMillis(backoffPolicy.maxRpcTimeout))
      .build()

  private def createTopicAdmin(
    customProviders: Option[(TransportChannelProvider, CredentialsProvider)]
  ): TopicAdminClient =
    customProviders match {
      case Some((channelProvider, credentialsProvider)) =>
        TopicAdminClient.create(
          TopicAdminSettings
            .newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        )
      case None =>
        TopicAdminClient.create()
    }

  private def topicExists(
    topicAdmin: TopicAdminClient,
    projectId: String,
    topicName: String
  ): Either[Throwable, Boolean] =
    Either
      .catchNonFatal(topicAdmin.listTopics(ProjectName.of(projectId)))
      .leftMap(new RuntimeException(s"Can't list topics", _))
      .map(_.iterateAll.asScala.toList.map(_.getName()))
      .flatMap { topics =>
        topics.contains(TopicName.of(projectId, topicName).toString()).asRight
      }
}
