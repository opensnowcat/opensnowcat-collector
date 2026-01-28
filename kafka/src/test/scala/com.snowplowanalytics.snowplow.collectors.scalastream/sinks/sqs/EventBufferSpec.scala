package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs

import org.specs2.mutable.Specification

class EventBufferSpec extends Specification {

  "EventBuffer" should {

    "store events and report buffer status" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 1000,
        recordThreshold = 10
      )

      val payload = "test event".getBytes("UTF-8")
      val status  = buffer.store(payload, "key1")

      status.shouldFlush must beFalse
      status.overflowed must beFalse
      status.currentSize must beEqualTo(1)
      status.currentBytes must beGreaterThan(0L)
    }

    "trigger flush when record threshold is reached" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 10000,
        recordThreshold = 5
      )

      // Add 4 events - should not flush
      (1 to 4).foreach { i =>
        val status = buffer.store(s"event$i".getBytes("UTF-8"), "key1")
        status.shouldFlush must beFalse
      }

      // Add 5th event - should trigger flush
      val status5 = buffer.store("event5".getBytes("UTF-8"), "key1")
      status5.shouldFlush must beTrue
      status5.currentSize must beEqualTo(5)
    }

    "trigger flush when byte threshold is reached" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 50,
        recordThreshold = 100
      )

      val largePayload = new Array[Byte](60) // 60 bytes
      val status       = buffer.store(largePayload, "key1")

      status.shouldFlush must beTrue
    }

    "handle buffer overflow by dropping oldest events" in {
      val buffer = new EventBuffer(
        maxSize = 3,
        byteThreshold = 10000,
        recordThreshold = 100
      )

      // Fill buffer to capacity
      buffer.store("event1".getBytes("UTF-8"), "key1")
      buffer.store("event2".getBytes("UTF-8"), "key2")
      buffer.store("event3".getBytes("UTF-8"), "key3")

      // Add one more - should overflow
      val status = buffer.store("event4".getBytes("UTF-8"), "key4")

      status.overflowed must beTrue
      status.currentSize must beEqualTo(3) // Still at max size
    }

    "drain all events and clear buffer" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 10000,
        recordThreshold = 100
      )

      buffer.store("event1".getBytes("UTF-8"), "key1")
      buffer.store("event2".getBytes("UTF-8"), "key2")
      buffer.store("event3".getBytes("UTF-8"), "key3")

      val drained = buffer.drain()

      drained.size must beEqualTo(3)
      drained(0).key must beEqualTo("key1")
      drained(1).key must beEqualTo("key2")
      drained(2).key must beEqualTo("key3")

      // Buffer should be empty after drain
      val stats = buffer.stats
      stats.size must beEqualTo(0)
      stats.bytes must beEqualTo(0L)
    }

    "return empty list when draining empty buffer" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 10000,
        recordThreshold = 100
      )

      val drained = buffer.drain()

      drained must beEmpty
    }

    "provide accurate buffer stats" in {
      val buffer = new EventBuffer(
        maxSize = 100,
        byteThreshold = 10000,
        recordThreshold = 100
      )

      val payload1 = "event1".getBytes("UTF-8")
      val payload2 = "event2".getBytes("UTF-8")

      buffer.store(payload1, "key1")
      buffer.store(payload2, "key2")

      val stats = buffer.stats

      stats.size must beEqualTo(2)
      stats.bytes must beGreaterThan(0L)
      stats.totalDropped must beEqualTo(0L)
    }

    "track total dropped events on buffer overflow" in {
      val buffer = new EventBuffer(
        maxSize = 3,
        byteThreshold = 10000,
        recordThreshold = 100
      )

      // Fill a buffer to capacity
      buffer.store("event1".getBytes("UTF-8"), "key1")
      buffer.store("event2".getBytes("UTF-8"), "key2")
      buffer.store("event3".getBytes("UTF-8"), "key3")

      // Add more events causing overflow
      buffer.store("event4".getBytes("UTF-8"), "key4")
      buffer.store("event5".getBytes("UTF-8"), "key5")

      val stats = buffer.stats
      stats.totalDropped must beEqualTo(2L)
      stats.size must beEqualTo(3)
    }

    "be thread-safe" in {
      val buffer = new EventBuffer(
        maxSize = 1000,
        byteThreshold = 100000,
        recordThreshold = 1000
      )

      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.{Await, Future}
      import scala.concurrent.duration._

      // Write from multiple threads concurrently
      val futures = (1 to 100).map { i =>
        Future {
          (1 to 10).foreach { j =>
            buffer.store(s"event-$i-$j".getBytes("UTF-8"), s"key-$i")
          }
        }
      }

      Await.result(Future.sequence(futures), 10.seconds)

      val stats = buffer.stats
      stats.size must beEqualTo(1000)
    }
  }
}
