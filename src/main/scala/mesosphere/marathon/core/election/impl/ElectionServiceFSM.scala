package mesosphere.marathon
package core.election.impl

import java.util.concurrent.{ ExecutorService, Executors }

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }
import mesosphere.marathon.util.RichLock

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[impl] object ElectionServiceFSM {
  /* The State and its descendants are defined in the companion object because access to them in tests is required. */
  sealed trait State {
    def getCandidate: Option[ElectionCandidate] = this match {
      case Idle => None
      case AcquiringLeadership(c) => Some(c)
      case Leading(c) => Some(c)
      case Stopped => None
    }
  }

  case object Idle extends State
  case class AcquiringLeadership(candidate: ElectionCandidate) extends State
  case class Leading(candidate: ElectionCandidate) extends State
  case object Stopped extends State
}

/**
  * This trait represents a simple state machine to get elected as a leader at most once. If leadership is lost,
  * Marathon gets shut down.
  *
  * If `abdicateLeadership` is called while being a leader, obviously, the leadership gets abdicated. In any case,
  * Marathon shutdown gets scheduled to take place shortly.
  *
  * Users of this trait are supposed to implement `leaderHostPortImpl` and `acquireLeadership` methods. Their bodies
  * are expected to be enclosed with `lock` defined in this trait.
  */
private[impl] trait ElectionServiceFSM
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  import ElectionServiceFSM._

  protected def system: ActorSystem
  protected def lifecycleState: LifecycleState
  protected[impl] var state: State = Idle

  /**
    * All methods of this trait and must use this re-entrant lock in order ease reasoning about the code.
    * Performance impact doesn't matter here at all, because state transitions are not meant to happen often.
    */
  protected def lock: RichLock = RichLock()

  protected val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /* We re-use the single thread executor here because code locks (via RichLock) frequently */
  protected implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  /* This method is wrapped into `leaderHostPort` which handles non-fatal exceptions. */
  protected def leaderHostPortImpl(): Option[String]

  /* If an error occurs while doing something asynchronously, it must call `stop(exit = true)` in such a case,
   * in order to shutdown Marathon. */
  protected def acquireLeadership(): Unit

  /* If something needs to be done before/after a candidate's leadership is started/stopped, some or all of these
   * hooks should be overridden. */
  protected def preStartLeadership(): Unit = ()
  protected def postStartLeadership(): Unit = ()
  protected def preStopLeadership(): Unit = ()
  protected def postStopLeadership(): Unit = ()

  system.registerOnTermination(lock {
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  })

  override def isLeader: Boolean = lock {
    state match {
      case Leading(_) => true
      case _ => false
    }
  }

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    lock {
      try {
        leaderHostPortImpl()
      } catch {
        case NonFatal(ex) =>
          logger.error("Could not get the current leader", ex)
          None
      }
    }
  }

  override def offerLeadership(candidate: ElectionCandidate): Unit = lock {
    logger.info(s"$candidate offered leadership (state = $state)")
    if (!lifecycleState.isRunning) {
      logger.info("Not accepting the offer since Marathon is shutting down")
    } else {
      state match {
        case Idle =>
          updateState(AcquiringLeadership(candidate))

          Future {
            try {
              logger.info("Going to acquire leadership")
              acquireLeadership()
            } catch {
              case NonFatal(ex) =>
                logger.error(s"Fatal error while acquiring leadership for $candidate. Exiting now", ex)
                stop(exit = true)
            }
          }
        case _ =>
          logger.warn(s"Ignoring the request from $candidate because of being in state: $state")
      }
    }
  }

  /**
    * This method is called when leadership is granted to a candidate. It starts the candidate's leadership,
    * creates some metrics, lets the world know about it. Prefer to implement the pre/post hooks to overriding
    * this method if something else needs to be done upon having elected as a leader.
    */
  protected def leadershipAcquired(): Unit = lock {
    state match {
      case AcquiringLeadership(candidate) =>
        updateState(Leading(candidate))

        Future {
          try {
            preStartLeadership()
            startLeadership()
            postStartLeadership()
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Fatal error while starting leadership of $candidate. Exiting now", ex)
              stop(exit = true)
          }
        }
      case _ =>
        logger.warn(s"Ignoring the request because of being in state: $state")
    }
  }

  override def abdicateLeadership(): Unit = lock {
    logger.info(s"Abdicating leadership while being in state: $state")
    stop(exit = true)
  }

  /**
    * This method stops a candidate's leadership if it has been started prior to that. After that it makes
    * Marathon's shutdown happen shortly.
    */
  protected def stop(exit: Boolean): Unit = lock {
    logger.info("Stopping the election service")

    state match {
      case Stopped => ()
      case _ =>
        try {
          preStopLeadership()
          stopLeadership()
          postStopLeadership()
        } catch {
          case NonFatal(ex) =>
            logger.error("Fatal error while stopping", ex)
        } finally {
          updateState(Stopped)
          if (exit) {
            system.scheduler.scheduleOnce(500.milliseconds) {
              Runtime.getRuntime.asyncExit()
            }
          }
        }
    }
  }

  protected def updateState(newState: State): Unit = lock {
    logger.info(s"State transition: $state -> $newState")
    state = newState
  }

  private def startLeadership(): Unit = lock {
    state.getCandidate.foreach(startCandidateLeadership)
    startMetrics()
  }

  private def stopLeadership(): Unit = lock {
    stopMetrics()
    state.getCandidate.foreach(stopCandidateLeadership)
  }

  private var candidateLeadershipStarted = false
  private def startCandidateLeadership(candidate: ElectionCandidate): Unit = lock {
    if (!candidateLeadershipStarted) {
      logger.info(s"Starting $candidate's leadership")
      candidate.startLeadership()
      candidateLeadershipStarted = true
      logger.info(s"Started $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)
    }
  }

  private def stopCandidateLeadership(candidate: ElectionCandidate): Unit = lock {
    if (candidateLeadershipStarted) {
      logger.info(s"Stopping $candidate's leadership")
      candidate.stopLeadership()
      candidateLeadershipStarted = false
      logger.info(s"Stopped $candidate's leadership")
      eventStream.publish(LocalLeadershipEvent.Standby)
    }
  }
}

private trait ElectionServiceEventStream {
  protected def eventStream: EventStream
  protected def lock: RichLock

  def isLeader: Boolean

  def subscribe(subscriber: ActorRef): Unit = lock {
    eventStream.subscribe(subscriber, classOf[LocalLeadershipEvent])
    val currentState = if (isLeader) LocalLeadershipEvent.ElectedAsLeader else LocalLeadershipEvent.Standby
    subscriber ! currentState
  }

  def unsubscribe(subscriber: ActorRef): Unit = lock {
    eventStream.unsubscribe(subscriber, classOf[LocalLeadershipEvent])
  }
}

private trait ElectionServiceMetrics {
  protected val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"
  protected val leaderHostPortMetric: Timer = Metrics.timer(ServiceMetric, getClass, "current-leader-host-port")

  protected def lock: RichLock
  protected var metricsStarted = false

  protected def startMetrics(): Unit = lock {
    if (!metricsStarted) {
      val startedAt = System.currentTimeMillis()
      Kamon.metrics.gauge(leaderDurationMetric, Time.Milliseconds)(System.currentTimeMillis() - startedAt)
      metricsStarted = true
    }
  }

  protected def stopMetrics(): Unit = lock {
    if (metricsStarted) {
      Kamon.metrics.removeGauge(leaderDurationMetric)
      metricsStarted = false
    }
  }
}
