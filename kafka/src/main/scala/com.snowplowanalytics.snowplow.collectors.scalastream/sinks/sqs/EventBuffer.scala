package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs

import java.nio.ByteBuffer
import scala.collection.mutable

/** Thread-safe event buffer with configurable size limits.
  *
  * @param maxSize Maximum number of events to buffer
  * @param byteThreshold Flush when buffer exceeds this many bytes
  * @param recordThreshold Flush when buffer exceeds this many records
  */
private[sinks] class EventBuffer(
  maxSize: Int,
  byteThreshold: Long,
  recordThreshold: Int
) {
  import EventBuffer._

  private val events    = mutable.Queue.empty[Event]
  private var byteCount = 0L

  /** Store an event in the buffer.
    *
    * @param payload Event payload bytes
    * @param key Event partition key
    * @return BufferStatus indicating if flush is needed and if overflow occurred
    */
  def store(payload: Array[Byte], key: String): BufferStatus = synchronized {
    val eventSize  = ByteBuffer.wrap(payload).capacity.toLong
    var overflowed = false

    // Handle buffer overflow
    if (events.size >= maxSize) {
      val dropped     = events.dequeue()
      val droppedSize = ByteBuffer.wrap(dropped.payload).capacity.toLong
      byteCount = Math.max(0, byteCount - droppedSize)
      overflowed = true
    }

    // Add new event
    events.enqueue(Event(payload, key))
    byteCount += eventSize

    // Determine if flush is needed
    val shouldFlush = events.size >= recordThreshold || byteCount >= byteThreshold

    BufferStatus(shouldFlush, overflowed, events.size, byteCount)
  }

  /** Drain all events from the buffer.
    *
    * @return List of all buffered events (buffer is cleared)
    */
  def drain(): List[Event] = synchronized {
    if (events.isEmpty) {
      List.empty
    } else {
      val result = events.dequeueAll(_ => true).toList
      byteCount = 0
      result
    }
  }

  /** Get current buffer statistics.
    *
    * @return Current size and byte count
    */
  def stats: BufferStats = synchronized {
    BufferStats(events.size, byteCount)
  }
}

private[sinks] object EventBuffer {
  final case class Event(payload: Array[Byte], key: String)

  final case class BufferStatus(
    shouldFlush: Boolean,
    overflowed: Boolean,
    currentSize: Int,
    currentBytes: Long
  )

  final case class BufferStats(size: Int, bytes: Long)
}
