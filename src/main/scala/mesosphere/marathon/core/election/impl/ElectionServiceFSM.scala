package mesosphere.marathon
package core.election.impl

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, LocalLeadershipEvent }

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
    extends ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  import ElectionServiceFSM._

  protected def system: ActorSystem
  protected def lifecycleState: LifecycleState
  protected var state: State = Idle

  protected val threadExecutor = Executors.newSingleThreadExecutor()
  /* We re-use the single thread executor here because code locks (via synchronized) frequently */
  protected implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  protected def acquireLeadership(): Unit

  protected def preStartLeadership(): Unit = ()
  protected def postStartLeadership(): Unit = ()
  protected def preStopLeadership(): Unit = ()
  protected def postStopLeadership(): Unit = ()

  system.registerOnTermination(synchronized {
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  })

  def isLeader: Boolean = synchronized {
    state match {
      case Leading(_) => true
      case _ => false
    }
  }

  def offerLeadership(candidate: ElectionCandidate): Unit = synchronized {
    logger.info(s"$candidate offered leadership (state = $state)")
    if (!lifecycleState.isRunning) {
      logger.info("Not accepting the offer since Marathon is shutting down")
    } else {
      state match {
        case Idle =>
          val newState = AcquiringLeadership(candidate)
          logStateTransition(state, newState)
          state = newState

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

  protected def leadershipAcquired(): Unit = synchronized {
    state match {
      case AcquiringLeadership(candidate) =>
        val newState = Leading(candidate)
        logStateTransition(state, newState)
        state = newState

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

  def abdicateLeadership(): Unit = synchronized {
    logger.info(s"Abdicating leadership while being in state: $state")
    stop(exit = true)
  }

  protected def stop(exit: Boolean): Unit = synchronized {
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
          val newState = Stopped
          logStateTransition(state, newState)
          state = newState
        }
        if (exit) Runtime.getRuntime.asyncExit()
    }
  }

  protected def logStateTransition(oldState: State, newState: State): Unit = synchronized {
    logger.info(s"State transition: $oldState -> $newState")
  }

  private def startLeadership(): Unit = synchronized {
    state.getCandidate.foreach(startCandidateLeadership)
    startMetrics()
    eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)
  }

  private def stopLeadership(): Unit = synchronized {
    eventStream.publish(LocalLeadershipEvent.Standby)
    stopMetrics()
    state.getCandidate.foreach(stopCandidateLeadership)
  }

  private var candidateLeadershipStarted = false
  private def startCandidateLeadership(candidate: ElectionCandidate): Unit = synchronized {
    if (!candidateLeadershipStarted) {
      logger.info(s"Starting $candidate's leadership")
      candidate.startLeadership()
      logger.info(s"Started $candidate's leadership")
      candidateLeadershipStarted = true
    }
  }

  private def stopCandidateLeadership(candidate: ElectionCandidate): Unit = synchronized {
    if (candidateLeadershipStarted) {
      logger.info(s"Stopping $candidate's leadership")
      candidate.stopLeadership()
      logger.info(s"Stopped $candidate's leadership")
      candidateLeadershipStarted = false
    }
  }
}
