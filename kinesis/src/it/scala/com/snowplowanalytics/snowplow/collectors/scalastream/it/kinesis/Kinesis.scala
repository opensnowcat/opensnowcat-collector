/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis

import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.BadRow
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorOutput
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers.Localstack
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.{
  DescribeStreamRequest,
  GetRecordsRequest,
  GetShardIteratorRequest,
  Record
}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object Kinesis {

  def readOutput(streamGood: String, streamBad: String): IO[CollectorOutput] =
    resourceClient.use { client =>
      for {
        good <- consumeGood(client, streamGood)
        bad  <- consumeBad(client, streamBad)
      } yield CollectorOutput(good, bad)
    }

  private def resourceClient: Resource[IO, KinesisClient] =
    Resource.make(
      IO(
        KinesisClient.builder()
          .credentialsProvider(
            StaticCredentialsProvider.create(
              AwsBasicCredentials.create("whatever", "whatever")
            )
          )
          .region(Region.of(Localstack.region))
          .endpointOverride(java.net.URI.create(Localstack.publicEndpoint))
          .build()
      )
    )(client => IO(client.close()))

  private def consumeGood(
    kinesis: KinesisClient,
    streamName: String
  ): IO[List[CollectorPayload]] =
    for {
      raw  <- consumeStream(kinesis, streamName)
      good <- IO(raw.map(parseCollectorPayload))
    } yield good

  private def consumeBad(
    kinesis: KinesisClient,
    streamName: String
  ): IO[List[BadRow]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      bad <- IO(raw.map(parseBadRow))
    } yield bad

  private def consumeStream(
    kinesis: KinesisClient,
    streamName: String
  ): IO[List[Array[Byte]]] = {
    val describeStreamRequest = DescribeStreamRequest.builder()
      .streamName(streamName)
      .build()
    val shardId = kinesis.describeStream(describeStreamRequest)
      .streamDescription().shards().get(0).shardId()

    val getShardIteratorRequest = GetShardIteratorRequest.builder()
      .streamName(streamName)
      .shardId(shardId)
      .shardIteratorType("TRIM_HORIZON")
      .build()
    val iterator = kinesis.getShardIterator(getShardIteratorRequest).shardIterator()

    val getRecordsRequest = GetRecordsRequest.builder()
      .shardIterator(iterator)
      .build()

    IO(kinesis.getRecords(getRecordsRequest).records().asScala.toList.map(getPayload))
  }

  def getPayload(record: Record): Array[Byte] = {
    val data   = record.data().asByteBuffer()
    val buffer = ArrayBuffer[Byte]()
    while (data.hasRemaining())
      buffer.append(data.get)
    buffer.toArray
  }
}
