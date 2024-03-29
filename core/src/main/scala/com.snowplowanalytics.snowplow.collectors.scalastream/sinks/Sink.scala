/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import org.slf4j.LoggerFactory

// Define an interface for all sinks to use to store events.
trait Sink {

  // Maximum number of bytes that a single record can contain.
  // If a record is bigger, a size violation bad row is emitted instead
  val maxBytes: Int

  lazy val log = LoggerFactory.getLogger(getClass())

  def isHealthy: Boolean = true
  def storeRawEvents(events: List[Array[Byte]], key: String): Unit
  def shutdown(): Unit
}
