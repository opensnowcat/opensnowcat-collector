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

import cats.syntax.either._
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink.SqsClientAndName
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.{
  DescribeStreamSummaryRequest,
  PutRecordsRequest,
  PutRecordsRequestEntry,
  PutRecordsResponse
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  GetQueueUrlRequest,
  MessageAttributeValue,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.util.{Failure, Success, Try}

class KinesisSink private (
  val maxBytes: Int,
  client: KinesisClient,
  kinesisConfig: Kinesis,
  bufferConfig: BufferConfig,
  streamName: String,
  executorService: ScheduledExecutorService,
  maybeSqs: Option[SqsClientAndName]
) extends Sink {
  import KinesisSink._

  maybeSqs match {
    case Some(sqs) =>
      log.info(s"SQS buffer for Kinesis stream $streamName is defined with name ${sqs.sqsBufferName}")
    case None =>
      log.warn(
        s"No SQS buffer for surge protection set up for stream $streamName (consider setting it via the config file)"
      )
  }

  private val ByteThreshold   = bufferConfig.byteLimit
  private val RecordThreshold = bufferConfig.recordLimit
  private val TimeThreshold   = bufferConfig.timeLimit

  private val maxBackoff      = kinesisConfig.backoffPolicy.maxBackoff
  private val minBackoff      = kinesisConfig.backoffPolicy.minBackoff
  private val maxRetries      = kinesisConfig.backoffPolicy.maxRetries
  private val randomGenerator = new java.util.Random()

  private val MaxSqsBatchSizeN = 10

  implicit lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  @volatile private var kinesisHealthy: Boolean = false
  @volatile private var sqsHealthy: Boolean     = false
  override def isHealthy: Boolean               = kinesisHealthy || sqsHealthy

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
      sinkBatch(eventsToSend)
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

  def sinkBatch(batch: List[Events]): Unit =
    if (batch.nonEmpty) maybeSqs match {
      // Kinesis healthy
      case _ if kinesisHealthy =>
        writeBatchToKinesisWithRetries(batch, minBackoff, maxRetries)
      // No SQS buffer
      case None =>
        writeBatchToKinesisWithRetries(batch, minBackoff, maxRetries)
      // Kinesis not healthy and SQS buffer defined
      case Some(sqs) =>
        writeBatchToSqsWithRetries(batch, sqs, minBackoff, maxRetries)
    }

  def writeBatchToKinesisWithRetries(batch: List[Events], nextBackoff: Long, retriesLeft: Int): Unit = {
    log.info(s"Writing ${batch.size} records to Kinesis stream $streamName")
    writeBatchToKinesis(batch).onComplete {
      case Success(s) =>
        kinesisHealthy = true
        val results      = s.records().asScala.toList
        val failurePairs = batch.zip(results).filter(_._2.errorMessage() != null)
        log.info(
          s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records to Kinesis stream $streamName"
        )
        if (failurePairs.nonEmpty) {
          failurePairs.groupBy(_._2.errorCode()).foreach { case (errorCode, items) =>
            val exampleMsg = items.map(_._2.errorMessage()).find(_.nonEmpty).getOrElse("")
            log.error(
              s"Writing ${items.size} records to Kinesis stream $streamName failed with error code [$errorCode] and example message: $exampleMsg"
            )
          }
          val failedRecords = failurePairs.map(_._1)
          handleKinesisError(failedRecords, nextBackoff, retriesLeft)
        }
      case Failure(f) =>
        log.error(s"Writing ${batch.size} records to Kinesis stream $streamName failed with error: ${f.getMessage()}")
        handleKinesisError(batch, nextBackoff, retriesLeft)
    }
  }

  def writeBatchToSqsWithRetries(
    batch: List[Events],
    sqs: SqsClientAndName,
    nextBackoff: Long,
    retriesLeft: Int
  ): Unit = {
    log.info(s"Writing ${batch.size} records to SQS buffer ${sqs.sqsBufferName}")
    writeBatchToSqs(batch, sqs).onComplete {
      case Success(s) =>
        sqsHealthy = true
        log.info(
          s"Successfully wrote ${batch.size - s.size} out of ${batch.size} records to SQS buffer ${sqs.sqsBufferName}"
        )
        if (s.nonEmpty) {
          s.groupBy(_._2.code).foreach { case (errorCode, items) =>
            val exampleMsg = items.map(_._2.message).find(_.nonEmpty).getOrElse("")
            log.error(
              s"Writing ${items.size} records to SQS buffer ${sqs.sqsBufferName} failed with error code [$errorCode] and example message: $exampleMsg"
            )
          }
          val failedRecords = s.map(_._1)
          handleSqsError(failedRecords, sqs, nextBackoff, retriesLeft)
        }
      case Failure(f) =>
        log.error(
          s"Writing ${batch.size} records to SQS buffer ${sqs.sqsBufferName} failed with error: ${f.getMessage()}"
        )
        handleSqsError(batch, sqs, nextBackoff, retriesLeft)
    }
  }

  def handleKinesisError(failedRecords: List[Events], nextBackoff: Long, retriesLeft: Int): Unit =
    if (retriesLeft > 0) {
      log.error(
        s"$retriesLeft retries left. Retrying to write ${failedRecords.size} records to Kinesis stream $streamName in $nextBackoff milliseconds"
      )
      scheduleRetryToKinesis(failedRecords, nextBackoff, retriesLeft - 1)
    } else {
      log.error(s"Maximum number of retries reached for Kinesis stream $streamName for ${failedRecords.size} records")
      maybeSqs match {
        case Some(sqs) =>
          log.error(
            s"SQS buffer ${sqs.sqsBufferName} defined for stream $streamName. Retrying to send the events to SQS"
          )
          // If Kinesis was already unhealthy, the background check is already running.
          // It can happen when the collector switches back and forth between Kinesis and SQS.
          if (kinesisHealthy) {
            this.synchronized {
              if (kinesisHealthy) {
                kinesisHealthy = false
                checkKinesisHealth()
              }
            }
          }
          writeBatchToSqsWithRetries(failedRecords, sqs, minBackoff, maxRetries)
        case None =>
          log.error(s"No SQS buffer defined for stream $streamName. Retrying to send the events to Kinesis")
          kinesisHealthy = false
          writeBatchToKinesisWithRetries(failedRecords, maxBackoff, maxRetries)
      }
    }

  def handleSqsError(failedRecords: List[Events], sqs: SqsClientAndName, nextBackoff: Long, retriesLeft: Int): Unit =
    if (retriesLeft > 0) {
      log.error(
        s"$retriesLeft retries left. Retrying to write ${failedRecords.size} records to SQS buffer ${sqs.sqsBufferName} in $nextBackoff milliseconds"
      )
      scheduleRetryToSqs(failedRecords, sqs, nextBackoff, retriesLeft - 1)
    } else {
      // If SQS was already unhealthy, the background check is already running.
      // It can happen when the collector switches back and forth between Kinesis and SQS.
      if (sqsHealthy) {
        this.synchronized {
          if (sqsHealthy) {
            sqsHealthy = false
            checkSqsHealth()
          }
        }
      }
      log.error(
        s"Maximum number of retries reached for SQS buffer ${sqs.sqsBufferName} for ${failedRecords.size} records. Retrying in Kinesis"
      )
      writeBatchToKinesisWithRetries(failedRecords, minBackoff, maxRetries)
    }

  def writeBatchToKinesis(batch: List[Events]): Future[PutRecordsResponse] =
    Future {
      val putRecordsRequestEntryList = batch.map { event =>
        PutRecordsRequestEntry.builder().partitionKey(event.key).data(SdkBytes.fromByteArray(event.payloads)).build()
      }
      val putRecordsRequest =
        PutRecordsRequest.builder().streamName(streamName).records(putRecordsRequestEntryList.asJava).build()
      client.putRecords(putRecordsRequest)
    }

  /** @return Empty list if all events were successfully inserted;
    *         otherwise a non-empty list of Events to be retried and the reasons why they failed.
    */
  def writeBatchToSqs(batch: List[Events], sqs: SqsClientAndName): Future[List[(Events, BatchResultErrorInfo)]] =
    Future {
      val splitBatch = split(batch, getByteSize, MaxSqsBatchSizeN, maxBytes)
      splitBatch.map(toSqsMessages).flatMap { msgGroup =>
        val entries      = msgGroup.map(_._2)
        val batchRequest = SendMessageBatchRequest.builder().queueUrl(sqs.sqsBufferName).entries(entries.asJava).build()
        val response     = sqs.sqsClient.sendMessageBatch(batchRequest)
        val failures = response
          .failed()
          .asScala
          .toList
          .map { bree =>
            (bree.id(), BatchResultErrorInfo(bree.code(), bree.message()))
          }
          .toMap
        // Events to retry and reasons for failure
        msgGroup.collect {
          case (e, m) if failures.contains(m.id()) =>
            (e, failures(m.id()))
        }
      }
    }

  def toSqsMessages(events: List[Events]): List[(Events, SendMessageBatchRequestEntry)] =
    events.map(e =>
      (
        e,
        SendMessageBatchRequestEntry
          .builder()
          .id(UUID.randomUUID.toString)
          .messageBody(b64Encode(e.payloads))
          .messageAttributes(
            Map(
              "kinesisKey" ->
                MessageAttributeValue.builder().dataType("String").stringValue(e.key).build()
            ).asJava
          )
          .build()
      )
    )

  def b64Encode(msg: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(msg)
    new String(buffer)
  }

  def scheduleRetryToKinesis(failedRecords: List[Events], currentBackoff: Long, retriesLeft: Int): Unit = {
    val nextBackoff = getNextBackoff(currentBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = writeBatchToKinesisWithRetries(failedRecords, nextBackoff, retriesLeft)
      },
      currentBackoff,
      MILLISECONDS
    )
    ()
  }

  def scheduleRetryToSqs(
    failedRecords: List[Events],
    sqs: SqsClientAndName,
    currentBackoff: Long,
    retriesLeft: Int
  ): Unit = {
    val nextBackoff = getNextBackoff(currentBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = writeBatchToSqsWithRetries(failedRecords, sqs, nextBackoff, retriesLeft)
      },
      currentBackoff,
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

  private def checkKinesisHealth(): Unit = {
    val healthRunnable = new Runnable {
      override def run() {
        log.info(s"Starting background check for Kinesis stream $streamName")
        while (!kinesisHealthy)
          Try {
            val describeRequest = DescribeStreamSummaryRequest.builder().streamName(streamName).build()
            val describeResult  = client.describeStreamSummary(describeRequest)
            describeResult.streamDescriptionSummary().streamStatus().toString
          } match {
            case Success("ACTIVE") =>
              log.info(s"Stream $streamName ACTIVE")
              kinesisHealthy = true
            case Success(other) =>
              log.warn(s"Stream $streamName not ACTIVE but $other")
              Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
            case Failure(err) =>
              log.error(s"Error while checking status of stream $streamName: ${err.getMessage()}")
              Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
          }
      }
    }
    executorService.execute(healthRunnable)
  }

  private def checkSqsHealth(): Unit = maybeSqs.foreach { sqs =>
    val healthRunnable = new Runnable {
      override def run() {
        log.info(s"Starting background check for SQS buffer ${sqs.sqsBufferName}")
        while (!sqsHealthy) {
          Try {
            sqs
              .sqsClient
              .getQueueUrl(
                GetQueueUrlRequest.builder().queueName(sqs.sqsBufferName).build()
              )
          } match {
            case Success(_) =>
              log.info(s"SQS buffer ${sqs.sqsBufferName} exists")
              sqsHealthy = true
            case Failure(err) =>
              log.error(s"SQS buffer ${sqs.sqsBufferName} doesn't exist. Error: ${err.getMessage()}")
          }
          Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
        }
      }
    }
    executorService.execute(healthRunnable)
  }
}

/** KinesisSink companion object with factory method */
object KinesisSink {

  sealed trait Target
  final case object Kinesis extends Target
  final case class Sqs(sqs: SqsClientAndName) extends Target

  /** Events to be written to Kinesis or SQS.
    * @param payloads Serialized events extracted from a CollectorPayload.
    *                 The size of this collection is limited by MaxBytes.
    *                 Not to be confused with a 'batch' events to sink.
    * @param key Partition key for Kinesis
    */
  final case class Events(payloads: Array[Byte], key: String)

  // Details about why messages failed to be written to SQS.
  final case class BatchResultErrorInfo(code: String, message: String)

  final case class SqsClientAndName(sqsClient: SqsClient, sqsBufferName: String)

  /** Create a KinesisSink and schedule a task to flush its EventStorage.
    * Exists so that no threads can get a reference to the KinesisSink
    * during its construction.
    */
  def createAndInitialize(
    kinesisMaxBytes: Int,
    kinesisConfig: Kinesis,
    bufferConfig: BufferConfig,
    streamName: String,
    sqsBufferName: Option[String],
    executorService: ScheduledExecutorService
  ): Either[Throwable, KinesisSink] = {
    val clients = for {
      provider         <- getProvider(kinesisConfig.aws)
      kinesisClient    <- createKinesisClient(provider, kinesisConfig.endpoint, kinesisConfig.region)
      sqsClientAndName <- sqsBuffer(sqsBufferName, provider, kinesisConfig.region)
    } yield (kinesisClient, sqsClientAndName)

    clients.map { case (kinesisClient, sqsClientAndName) =>
      val maxBytes =
        if (sqsClientAndName.isDefined) kinesisConfig.sqsMaxBytes else kinesisMaxBytes
      val ks =
        new KinesisSink(
          maxBytes,
          kinesisClient,
          kinesisConfig,
          bufferConfig,
          streamName,
          executorService,
          sqsClientAndName
        )
      ks.checkKinesisHealth()
      ks.checkSqsHealth()
      ks.EventStorage.scheduleFlush()
      ks
    }
  }

  /** Create an aws credentials provider through env variables and iam. */
  private def getProvider(awsConfig: AWSConfig): Either[Throwable, AwsCredentialsProvider] = {
    def isDefault(key: String): Boolean = key == "default"
    def isIam(key: String): Boolean     = key == "iam"
    def isEnv(key: String): Boolean     = key == "env"

    ((awsConfig.accessKey, awsConfig.secretKey) match {
      case (a, s) if isDefault(a) && isDefault(s) =>
        DefaultCredentialsProvider.builder().build().asRight
      case (a, s) if isDefault(a) || isDefault(s) =>
        "accessKey and secretKey must both be set to 'default' or neither".asLeft
      case (a, s) if isIam(a) && isIam(s) =>
        InstanceProfileCredentialsProvider.builder().build().asRight
      case (a, s) if isIam(a) || isIam(s) =>
        "accessKey and secretKey must both be set to 'iam' or neither".asLeft
      case (a, s) if isEnv(a) && isEnv(s) =>
        EnvironmentVariableCredentialsProvider.create().asRight
      case (a, s) if isEnv(a) || isEnv(s) =>
        "accessKey and secretKey must both be set to 'env' or neither".asLeft
      case _ =>
        StaticCredentialsProvider
          .create(
            AwsBasicCredentials.create(awsConfig.accessKey, awsConfig.secretKey)
          )
          .asRight
    }).leftMap(new IllegalArgumentException(_))
  }

  /** Creates a new Kinesis client.
    * @param provider aws credentials provider
    * @param endpoint kinesis endpoint where the stream resides
    * @param region aws region where the stream resides
    * @return the initialized KinesisClient
    */
  private def createKinesisClient(
    provider: AwsCredentialsProvider,
    endpoint: String,
    region: String
  ): Either[Throwable, KinesisClient] =
    Either.catchNonFatal(
      KinesisClient
        .builder()
        .credentialsProvider(provider)
        .region(Region.of(region))
        .endpointOverride(java.net.URI.create(endpoint))
        .build()
    )

  private def sqsBuffer(
    sqsBufferName: Option[String],
    provider: AwsCredentialsProvider,
    region: String
  ): Either[Throwable, Option[SqsClientAndName]] =
    sqsBufferName match {
      case Some(name) =>
        createSqsClient(provider, region).map(amazonSqs => Some(SqsClientAndName(amazonSqs, name)))
      case None => None.asRight
    }

  private def createSqsClient(provider: AwsCredentialsProvider, region: String): Either[Throwable, SqsClient] =
    Either.catchNonFatal(
      SqsClient.builder().region(Region.of(region)).credentialsProvider(provider).build()
    )

  /** Splits a Kinesis-sized batch of `Events` into smaller batches that meet the SQS limit.
    * @param batch A batch of up to `KinesisLimit` that must be split into smaller batches.
    * @param getByteSize How to get the size of a batch.
    * @param maxRecords Max records for the smaller batches.
    * @param maxBytes Max byte size for the smaller batches.
    * @return A batch of smaller batches, each one of which meets the limits.
    */
  def split(
    batch: List[Events],
    getByteSize: Events => Int,
    maxRecords: Int,
    maxBytes: Int
  ): List[List[Events]] = {
    var bytes = 0L
    @scala.annotation.tailrec
    def go(originalBatch: List[Events], tmpBatch: List[Events], newBatch: List[List[Events]]): List[List[Events]] =
      (originalBatch, tmpBatch) match {
        case (Nil, Nil) => newBatch
        case (Nil, acc) => acc :: newBatch
        case (h :: t, acc) if acc.size + 1 > maxRecords || getByteSize(h) + bytes > maxBytes =>
          bytes = getByteSize(h).toLong
          go(t, h :: Nil, acc :: newBatch)
        case (h :: t, acc) =>
          bytes += getByteSize(h)
          go(t, h :: acc, newBatch)
      }
    go(batch, Nil, Nil).map(_.reverse).reverse.filter(_.nonEmpty)
  }

  def getByteSize(events: Events): Int = ByteBuffer.wrap(events.payloads).capacity
}
