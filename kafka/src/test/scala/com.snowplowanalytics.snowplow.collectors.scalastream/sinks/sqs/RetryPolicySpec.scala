package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs
import org.specs2.mutable.Specification
class RetryPolicySpec extends Specification {
  "RetryPolicy" should {
    "return true when retries are available" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      policy.shouldRetry(5) must beTrue
      policy.shouldRetry(1) must beTrue
    }
    "return false when no retries left" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      policy.shouldRetry(0) must beFalse
    }
    "return initial backoff" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      policy.initialBackoff must beEqualTo(100)
    }
    "calculate next backoff within bounds" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      val nextBackoff = policy.nextBackoff(1000)
      nextBackoff must beGreaterThanOrEqualTo(100L)
      nextBackoff must beLessThanOrEqualTo(5000L)
    }
    "never decrease backoff too quickly" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      val currentBackoff = 3000L
      val nextBackoff    = policy.nextBackoff(currentBackoff)
      // Next backoff should be at least 2/3 of current
      nextBackoff must beGreaterThanOrEqualTo(currentBackoff * 2 / 3)
    }
    "add jitter to prevent thundering herd" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 5
      )
      // Calculate multiple backoffs - they should vary due to jitter
      val backoffs = (1 to 100).map(_ => policy.nextBackoff(1000)).toSet
      // Should have multiple different values (randomized)
      backoffs.size must beGreaterThan(1)
    }
    "respect minimum backoff boundary" in {
      val policy = new RetryPolicy(
        minBackoff = 1000,
        maxBackoff = 5000,
        maxRetries = 5
      )
      val nextBackoff = policy.nextBackoff(500)
      nextBackoff must beGreaterThanOrEqualTo(1000L)
    }
    "respect maximum backoff boundary" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 2000,
        maxRetries = 5
      )
      val nextBackoff = policy.nextBackoff(1000)
      nextBackoff must beLessThanOrEqualTo(2000L)
    }
    "handle edge case when min equals max" in {
      val policy = new RetryPolicy(
        minBackoff = 1000,
        maxBackoff = 1000,
        maxRetries = 5
      )
      val nextBackoff = policy.nextBackoff(500)
      nextBackoff must beEqualTo(1000L)
    }
    "be consistent with max retries configuration" in {
      val policy = new RetryPolicy(
        minBackoff = 100,
        maxBackoff = 5000,
        maxRetries = 3
      )
      policy.maxRetries must beEqualTo(3)
      policy.shouldRetry(3) must beTrue
      policy.shouldRetry(0) must beFalse
    }
  }
}
