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

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import cats.effect.{IO, Resource}

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model.{GetRecordsRequest, Record}

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorOutput
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers.Localstack

object Kinesis {

  def readOutput(streamGood: String, streamBad: String): IO[CollectorOutput] =
    resourceClient.use { client =>
      for {
        good <- consumeGood(client, streamGood)
        bad  <- consumeBad(client, streamBad)
      } yield CollectorOutput(good, bad)
    }

  private def resourceClient: Resource[IO, AmazonKinesis] =
    Resource.make(
      IO(
        AmazonKinesisClientBuilder
          .standard()
          .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("whatever", "whatever")))
          .withEndpointConfiguration(new EndpointConfiguration(Localstack.publicEndpoint, Localstack.region))
          .build
      )
    )(client => IO(client.shutdown()))

  private def consumeGood(
    kinesis: AmazonKinesis,
    streamName: String
  ): IO[List[CollectorPayload]] =
    for {
      raw  <- consumeStream(kinesis, streamName)
      good <- IO(raw.map(parseCollectorPayload))
    } yield good

  private def consumeBad(
    kinesis: AmazonKinesis,
    streamName: String
  ): IO[List[BadRow]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      bad <- IO(raw.map(parseBadRow))
    } yield bad

  private def consumeStream(
    kinesis: AmazonKinesis,
    streamName: String
  ): IO[List[Array[Byte]]] = {
    val shardId           = kinesis.describeStream(streamName).getStreamDescription.getShards.get(0).getShardId
    val iterator          = kinesis.getShardIterator(streamName, shardId, "TRIM_HORIZON").getShardIterator
    val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)

    IO(kinesis.getRecords(getRecordsRequest).getRecords.asScala.toList.map(getPayload))
  }

  def getPayload(record: Record): Array[Byte] = {
    val data   = record.getData()
    val buffer = ArrayBuffer[Byte]()
    while (data.hasRemaining())
      buffer.append(data.get)
    buffer.toArray
  }
}
