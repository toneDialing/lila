package lila.hub

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.collection.immutable.Queue
import scala.concurrent.Promise

import lila.base.LilaException

/*
 * Sequential like an actor, but for async functions,
 * and using an atomic backend instead of akka actor.
 */
abstract class BoundedDuct(implicit ec: scala.concurrent.ExecutionContext) {

  import BoundedDuct._

  // implement async behaviour here
  protected val process: ReceiveAsync

  protected val maxSize = 100

  def !(msg: Any): Boolean =
    stateRef.getAndUpdate { state =>
      Some {
        state.fold(emptyQueue) { q =>
          if (q.size >= maxSize) q
          else q enqueue msg
        }
      }
    } match {
      case None => // previous state was idle, we can run immediately
        run(msg)
        true
      case Some(q) => // succeed if previous state was not a full queue
        q.size < maxSize
    }

  def ask[A](makeMsg: Promise[A] => Any): Fu[A] = {
    val promise = Promise[A]
    val success = this ! makeMsg(promise)
    if (!success) promise failure LilaException(s"The queue is full ($maxSize)")
    promise.future
  }

  def queueSize = stateRef.get().fold(0)(_.size + 1)

  /*
   * Idle: None
   * Busy: Some(Queue.empty)
   * Busy with backlog: Some(Queue.nonEmpty)
   */
  private[this] val stateRef: AtomicReference[State] = new AtomicReference(None)

  private[this] def run(msg: Any): Unit =
    process.applyOrElse(msg, BoundedDuct.fallback) onComplete postRun

  private[this] val postRun = (_: Any) =>
    stateRef.getAndUpdate(postRunUpdate) flatMap (_.headOption) foreach run
}

object BoundedDuct {

  type ReceiveAsync = PartialFunction[Any, Fu[Any]]

  case class SizedQueue(queue: Queue[Any], size: Int) {
    def enqueue(a: Any) = SizedQueue(queue enqueue a, size + 1)
    def isEmpty         = size == 0
    def tailOption      = !isEmpty option SizedQueue(queue.tail, size - 1)
    def headOption      = queue.headOption
  }
  val emptyQueue = SizedQueue(Queue.empty, 0)

  private type State = Option[SizedQueue]

  private val postRunUpdate = new UnaryOperator[State] {
    override def apply(state: State): State = state.flatMap(_.tailOption)
  }

  private val fallback = { msg: Any =>
    lila.log("Duct").warn(s"unhandled msg: $msg")
    funit
  }
}
