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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success, Try}
import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.concurrent.duration.MILLISECONDS
import scala.collection.JavaConverters._

import cats.syntax.either._

import com.amazonaws.auth.{
  AWSCredentialsProvider,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials,
  DefaultAWSCredentialsProviderChain,
  EnvironmentVariableCredentialsProvider,
  InstanceProfileCredentialsProvider
}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.amazonaws.services.sqs.model.{MessageAttributeValue, SendMessageBatchRequest, SendMessageBatchRequestEntry}

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

class SqsSink private (
  val maxBytes: Int,
  client: AmazonSQS,
  sqsConfig: Sqs,
  bufferConfig: BufferConfig,
  queueName: String,
  executorService: ScheduledExecutorService
) extends Sink {
  import SqsSink._

  private val ByteThreshold: Long   = bufferConfig.byteLimit
  private val RecordThreshold: Long = bufferConfig.recordLimit
  private val TimeThreshold: Long   = bufferConfig.timeLimit

  private val maxBackoff: Long        = sqsConfig.backoffPolicy.maxBackoff
  private val minBackoff: Long        = sqsConfig.backoffPolicy.minBackoff
  private val maxRetries: Int         = sqsConfig.backoffPolicy.maxRetries
  private val randomGenerator: Random = new java.util.Random()

  private val MaxSqsBatchSizeN = 10

  implicit lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  @volatile private var sqsHealthy: Boolean = false
  override def isHealthy: Boolean           = sqsHealthy

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    events.foreach(e => EventStorage.store(e, key))

  object EventStorage {
    private val storedEvents              = ListBuffer.empty[Events]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity

      synchronized {
        if (storedEvents.size + 1 > RecordThreshold || byteCount + eventSize > ByteThreshold) {
          flush()
        }
        storedEvents += Events(eventBytes.array(), key)
        byteCount += eventSize
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.result
        storedEvents.clear()
        byteCount = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sinkBatch(eventsToSend, minBackoff, maxRetries)
    }

    def getLastFlushTime: Long = lastFlushedTime

    /** Recursively schedule a task to send everything in EventStorage.
      * Even if the incoming event flow dries up, all stored events will eventually get sent.
      * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
      * @param interval When to schedule the next flush
      */
    def scheduleFlush(interval: Long = TimeThreshold): Unit = {
      executorService.schedule(
        new Runnable {
          override def run(): Unit = {
            val lastFlushed = getLastFlushTime
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlushed >= TimeThreshold) {
              flush()
              scheduleFlush(TimeThreshold)
            } else {
              scheduleFlush(TimeThreshold + lastFlushed - currentTime)
            }
          }
        },
        interval,
        MILLISECONDS
      )
      ()
    }
  }

  def sinkBatch(batch: List[Events], nextBackoff: Long, retriesLeft: Int): Unit =
    if (batch.nonEmpty) {
      log.info(s"Writing ${batch.size} records to SQS queue $queueName")

      writeBatchToSqs(batch).onComplete {
        case Success(s) =>
          sqsHealthy = true
          log.info(s"Successfully wrote ${batch.size - s.size} out of ${batch.size} records to SQS queue $queueName")

          if (s.nonEmpty) {
            s.groupBy(_._2.code).foreach { case (errorCode, items) =>
              val exampleMsg = items.map(_._2.message).find(_.nonEmpty).getOrElse("")
              log.error(
                s"Writing ${items.size} records to SQS queue $queueName failed with error code [$errorCode] and example message: $exampleMsg"
              )
            }
            val failedRecords = s.map(_._1)
            handleError(failedRecords, nextBackoff, retriesLeft)
          }
        case Failure(f) =>
          log.error(
            s"Writing ${batch.size} records to SQS queue $queueName failed with error: ${f.getMessage()}"
          )
          handleError(batch, nextBackoff, retriesLeft)
      }
    }

  def handleError(failedRecords: List[Events], nextBackoff: Long, retriesLeft: Int): Unit =
    if (retriesLeft > 0) {
      log.error(
        s"$retriesLeft retries left. Retrying to write ${failedRecords.size} records to SQS queue $queueName in $nextBackoff milliseconds"
      )
      scheduleWrite(failedRecords, nextBackoff, retriesLeft - 1)
    } else {
      sqsHealthy = false
      checkSqsHealth()
      log.error(
        s"Maximum number of retries reached for SQS queue $queueName for ${failedRecords.size} records"
      )
      scheduleWrite(failedRecords, maxBackoff, maxRetries)
    }

  /** @return Empty list if all events were successfully inserted;
    *         otherwise a non-empty list of Events to be retried and the reasons why they failed.
    */
  def writeBatchToSqs(batch: List[Events]): Future[List[(Events, BatchResultErrorInfo)]] =
    Future {
      toSqsMessages(batch)
        .grouped(MaxSqsBatchSizeN)
        .flatMap { msgGroup =>
          val entries = msgGroup.map(_._2)
          val batchRequest =
            new SendMessageBatchRequest().withQueueUrl(queueName).withEntries(entries.asJava)
          val response = client.sendMessageBatch(batchRequest)
          val failures = response
            .getFailed
            .asScala
            .toList
            .map { bree =>
              (bree.getId, BatchResultErrorInfo(bree.getCode, bree.getMessage))
            }
            .toMap
          // Events to retry and reasons for failure
          msgGroup.collect {
            case (e, m) if failures.contains(m.getId) =>
              (e, failures(m.getId))
          }
        }
        .toList
    }

  def toSqsMessages(events: List[Events]): List[(Events, SendMessageBatchRequestEntry)] =
    events.map(e =>
      (
        e,
        new SendMessageBatchRequestEntry(UUID.randomUUID.toString, b64Encode(e.payloads)).withMessageAttributes(
          Map(
            "kinesisKey" ->
              new MessageAttributeValue().withDataType("String").withStringValue(e.key)
          ).asJava
        )
      )
    )

  def b64Encode(e: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(e)
    new String(buffer)
  }

  def scheduleWrite(batch: List[Events], lastBackoff: Long, retriesLeft: Int): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = sinkBatch(batch, nextBackoff, retriesLeft)
      },
      lastBackoff,
      MILLISECONDS
    )
    ()
  }

  /** How long to wait before sending the next request
    * @param lastBackoff The previous backoff time
    * @return Maximum of two-thirds of lastBackoff and a random number between minBackoff and maxBackoff
    */
  private def getNextBackoff(lastBackoff: Long): Long = {
    val diff = (maxBackoff - minBackoff + 1).toInt
    (minBackoff + randomGenerator.nextInt(diff)).max(lastBackoff / 3 * 2)
  }

  def shutdown(): Unit = {
    EventStorage.flush()
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }

  private def checkSqsHealth(): Unit = {
    val healthRunnable = new Runnable {
      override def run() {
        while (!sqsHealthy)
          Try {
            client.getQueueUrl(queueName)
          } match {
            case Success(_) =>
              log.info(s"SQS queue $queueName exists")
              sqsHealthy = true
            case Failure(err) =>
              log.error(s"SQS queue $queueName doesn't exist. Error: ${err.getMessage()}")
              Thread.sleep(1000L)
          }
      }
    }
    executorService.execute(healthRunnable)
  }
}

/** SqsSink companion object with factory method */
object SqsSink {

  /** Events to be written to SQS.
    * @param payloads Serialized events extracted from a CollectorPayload.
    *                 The size of this collection is limited by MaxBytes.
    *                 Not to be confused with a 'batch' events to sink.
    * @param key Partition key for Kinesis, when events are ultimately re-routed there
    */
  final case class Events(payloads: Array[Byte], key: String)

  // Details about why messages failed to be written to SQS.
  final case class BatchResultErrorInfo(code: String, message: String)

  /** Create an SqsSink and schedule a task to flush its EventStorage.
    * Exists so that no threads can get a reference to the SqsSink
    * during its construction.
    */
  def createAndInitialize(
    maxBytes: Int,
    sqsConfig: Sqs,
    bufferConfig: BufferConfig,
    queueName: String,
    executorService: ScheduledExecutorService
  ): Either[Throwable, SqsSink] = {
    val client = for {
      provider <- getProvider(sqsConfig.aws)
      client   <- createSqsClient(provider, sqsConfig.region)
    } yield client

    client.map { c =>
      val sqsSink = new SqsSink(maxBytes, c, sqsConfig, bufferConfig, queueName, executorService)
      sqsSink.EventStorage.scheduleFlush()
      sqsSink.checkSqsHealth()
      sqsSink
    }
  }

  /** Create an aws credentials provider through env variables and iam. */
  private def getProvider(awsConfig: AWSConfig): Either[Throwable, AWSCredentialsProvider] = {
    def isDefault(key: String): Boolean = key == "default"
    def isIam(key: String): Boolean     = key == "iam"
    def isEnv(key: String): Boolean     = key == "env"

    ((awsConfig.accessKey, awsConfig.secretKey) match {
      case (a, s) if isDefault(a) && isDefault(s) =>
        new DefaultAWSCredentialsProviderChain().asRight
      case (a, s) if isDefault(a) || isDefault(s) =>
        "accessKey and secretKey must both be set to 'default' or neither".asLeft
      case (a, s) if isIam(a) && isIam(s) =>
        InstanceProfileCredentialsProvider.getInstance().asRight
      case (a, s) if isIam(a) && isIam(s) =>
        "accessKey and secretKey must both be set to 'iam' or neither".asLeft
      case (a, s) if isEnv(a) && isEnv(s) =>
        new EnvironmentVariableCredentialsProvider().asRight
      case (a, s) if isEnv(a) || isEnv(s) =>
        "accessKey and secretKey must both be set to 'env' or neither".asLeft
      case _ =>
        new AWSStaticCredentialsProvider(
          new BasicAWSCredentials(awsConfig.accessKey, awsConfig.secretKey)
        ).asRight
    }).leftMap(new IllegalArgumentException(_))
  }

  private def createSqsClient(provider: AWSCredentialsProvider, region: String): Either[Throwable, AmazonSQS] =
    Either.catchNonFatal(
      AmazonSQSClientBuilder.standard().withRegion(region).withCredentials(provider).build
    )
}
