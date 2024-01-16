/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.it

import cats.effect.IO

import org.http4s.{Method, Request, Uri}

object EventGenerator {

  def sendEvents(
    collectorHost: String,
    collectorPort: Int,
    nbGood: Int,
    nbBad: Int,
    maxBytes: Int
  ): IO[Unit] = {
    val requests = generateEvents(collectorHost, collectorPort, nbGood, nbBad, maxBytes)
    Http.statuses(requests).flatMap { responses =>
      responses.collect { case resp if resp.code != 200 => resp.reason } match {
        case Nil => IO.unit
        case errors =>
          IO.raiseError(
            new RuntimeException(s"${errors.size} requests were not successful. Example error: ${errors.head}")
          )
      }
    }
  }

  def generateEvents(
    collectorHost: String,
    collectorPort: Int,
    nbGood: Int,
    nbBad: Int,
    maxBytes: Int
  ): List[Request[IO]] = {
    val good = List.fill(nbGood)(mkTp2Event(collectorHost, collectorPort, valid = true, maxBytes))
    val bad  = List.fill(nbBad)(mkTp2Event(collectorHost, collectorPort, valid = false, maxBytes))
    good ++ bad
  }

  def mkTp2Event(
    collectorHost: String,
    collectorPort: Int,
    valid: Boolean = true,
    maxBytes: Int = 100
  ): Request[IO] = {
    val uri  = Uri.unsafeFromString(s"http://$collectorHost:$collectorPort/com.snowplowanalytics.snowplow/tp2")
    val body = if (valid) "foo" else "a" * (maxBytes + 1)
    Request[IO](Method.POST, uri).withEntity(body)
  }
}
