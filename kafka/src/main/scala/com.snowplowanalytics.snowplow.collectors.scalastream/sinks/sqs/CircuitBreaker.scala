package com.snowplowanalytics.snowplow.collectors.scalastream.sinks.sqs

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Simple circuit breaker for SQS operations.
  *
  * States:
  * - Closed: Normal operation, requests pass through
  * - Open: Failure threshold exceeded, requests fail fast
  * - HalfOpen: Testing if service recovered, single test request allowed
  *
  * @param maxFailures Number of consecutive failures before opening circuit
  * @param resetTimeout How long to wait before testing recovery (half-open)
  */
private[sinks] class CircuitBreaker(
  maxFailures: Int,
  resetTimeout: FiniteDuration
) {
  import CircuitBreaker._

  private val state           = new AtomicReference[State](Closed)
  private val failureCount    = new AtomicInteger(0)
  private val lastFailureTime = new AtomicLong(0L)

  /** Execute operation with circuit breaker protection.
    *
    * @param operation The operation to protect
    * @param ec Execution context for async operations
    * @tparam T Result type
    * @return Future that fails fast if circuit is open
    */
  def protect[T](operation: => Future[T])(implicit ec: ExecutionContext): Future[T] =
    state.get() match {
      case Closed =>
        executeAndTrack(operation)

      case Open =>
        // Check if we should transition to half-open
        val elapsed = System.currentTimeMillis() - lastFailureTime.get()
        if (elapsed >= resetTimeout.toMillis) {
          // Transition to half-open and try one request
          if (state.compareAndSet(Open, HalfOpen)) {
            executeAndTrack(operation)
          } else {
            Future.failed(CircuitBreakerOpenException("Circuit breaker is open"))
          }
        } else {
          Future.failed(CircuitBreakerOpenException("Circuit breaker is open"))
        }

      case HalfOpen =>
        // Only one request allowed in half-open state
        Future.failed(CircuitBreakerOpenException("Circuit breaker is half-open, test in progress"))
    }

  private def executeAndTrack[T](operation: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()

    try operation.onComplete {
      case Success(result) =>
        onSuccess()
        promise.success(result)

      case Failure(ex) =>
        onFailure()
        promise.failure(ex)
    } catch {
      case ex: Throwable =>
        onFailure()
        promise.failure(ex)
    }

    promise.future
  }

  private def onSuccess(): Unit =
    state.get() match {
      case HalfOpen =>
        // Successful test request, close the circuit
        state.set(Closed)
        failureCount.set(0)

      case Closed =>
        // Reset failure count on success
        failureCount.set(0)

      case Open =>
        // Shouldn't happen, but reset anyway
        state.set(Closed)
        failureCount.set(0)
    }

  private def onFailure(): Unit = {
    lastFailureTime.set(System.currentTimeMillis())

    state.get() match {
      case HalfOpen =>
        // Test request failed, reopen circuit
        state.set(Open)
        val _ = failureCount.incrementAndGet()

      case Closed =>
        val failures = failureCount.incrementAndGet()
        if (failures >= maxFailures) {
          state.set(Open)
        }

      case Open =>
        // Already open, increment counter
        val _ = failureCount.incrementAndGet()
    }
  }

  /** Get current circuit breaker state.
    *
    * @return Current state (Closed, Open, or HalfOpen)
    */
  def currentState: State = state.get()

  /** Check if circuit breaker is currently open.
    *
    * @return true if open or half-open, false if closed
    */
  def isOpen: Boolean = state.get() != Closed

  /** Get current failure count.
    *
    * @return Number of failures recorded
    */
  def failures: Int = failureCount.get()
}

private[sinks] object CircuitBreaker {
  sealed trait State
  case object Closed extends State
  case object Open extends State
  case object HalfOpen extends State

  case class CircuitBreakerOpenException(message: String) extends Exception(message)
}
