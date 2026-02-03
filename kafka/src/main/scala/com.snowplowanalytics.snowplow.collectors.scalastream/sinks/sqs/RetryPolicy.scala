package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs

import scala.util.Random

/** Retry policy with randomized backoff and jitter.
  *
  * Uses randomized delays within configured bounds to prevent thundering herd,
  * with a floor to ensure backoff doesn't decrease too rapidly between retries.
  *
  * @param minBackoff Minimum backoff delay in milliseconds
  * @param maxBackoff Maximum backoff delay in milliseconds
  * @param maxRetries Maximum number of retry attempts
  */
private[sinks] class RetryPolicy(
  val minBackoff: Long,
  val maxBackoff: Long,
  val maxRetries: Int
) {
  private val randomGenerator = new Random()

  /** Check if another retry should be attempted.
    *
    * @param retriesLeft Number of retries remaining
    * @return true if should retry, false if max retries exhausted
    */
  def shouldRetry(retriesLeft: Int): Boolean = retriesLeft > 0

  /** Calculate next backoff delay with jitter.
    *
    * Picks a random delay between minBackoff and maxBackoff, with a floor
    * of 2/3 of the current backoff to prevent rapid decrease between retries.
    *
    * @param currentBackoff Current backoff delay in milliseconds
    * @return Next backoff delay in milliseconds
    */
  def nextBackoff(currentBackoff: Long): Long = {
    val diff        = (maxBackoff - minBackoff + 1).toInt
    val randomDelay = minBackoff               + randomGenerator.nextInt(diff)
    // Ensure backoff doesn't decrease too quickly
    randomDelay.max(currentBackoff * 2 / 3)
  }

  /** Get initial backoff delay.
    *
    * @return Minimum backoff delay
    */
  def initialBackoff: Long = minBackoff
}
