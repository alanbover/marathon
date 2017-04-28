package mesosphere.marathon
package core.election.impl

import java.util.concurrent.{ ExecutorService, Executors }

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.util.RichLock

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[impl] object ElectionServiceFSM {
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

private[impl] trait ElectionServiceFSM
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  import ElectionServiceFSM._

  protected def system: ActorSystem
  protected def lifecycleState: LifecycleState
  protected[impl] var state: State = Idle
  protected def lock: RichLock = RichLock()

  protected val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /* We re-use the single thread executor here because code locks (via RichLock) frequently */
  protected implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  protected def leaderHostPortImpl(): Option[String]
  protected def acquireLeadership(): Unit

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
          logger.error("Could not get current leader", ex)
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
        }
        if (exit) Runtime.getRuntime.asyncExit()
    }
  }

  protected def updateState(newState: State): Unit = lock {
    logger.info(s"State transition: $state -> $newState")
    state = newState
  }

  private def startLeadership(): Unit = lock {
    state.getCandidate.foreach(startCandidateLeadership)
    eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)
    startMetrics()
  }

  private def stopLeadership(): Unit = lock {
    stopMetrics()
    state.getCandidate.foreach(stopCandidateLeadership)
    eventStream.publish(LocalLeadershipEvent.Standby)
  }

  private var candidateLeadershipStarted = false
  private def startCandidateLeadership(candidate: ElectionCandidate): Unit = lock {
    if (!candidateLeadershipStarted) {
      logger.info(s"Starting $candidate's leadership")
      candidate.startLeadership()
      logger.info(s"Started $candidate's leadership")
      candidateLeadershipStarted = true
    }
  }

  private def stopCandidateLeadership(candidate: ElectionCandidate): Unit = lock {
    if (candidateLeadershipStarted) {
      logger.info(s"Stopping $candidate's leadership")
      candidate.stopLeadership()
      logger.info(s"Stopped $candidate's leadership")
      candidateLeadershipStarted = false
    }
  }
}
