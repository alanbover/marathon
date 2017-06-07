package mesosphere.marathon
package core.election.impl

import java.util.concurrent.{ ExecutorService, Executors }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.util.RichLock

import scala.async.Async
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * This is a somewhat dummy, but sane and defensive implementation of [[ElectionService]]. It is used when
  * the high-availability mode is disabled.
  *
  * It stops Marathon when leadership is abdicated.
  */
class PseudoElectionService(
  hostPort: String,
  system: ActorSystem,
  val eventStream: EventStream)
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  system.registerOnTermination({
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  })

  /**
    * This lock is used to linearize methods with side effects.
    * Performance impact doesn't matter here at all, methods of this class are called infrequently.
    */
  protected override def lock: RichLock = RichLock()

  private val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /** We re-use the single thread executor here because some methods of this class might get blocked for a long time. */
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  /* it is made `private[impl]` because it has to be accessible from inside the corresponding unit-tests. */
  private[impl] var leader: Option[ElectionCandidate] = None

  override def isLeader: Boolean = lock {
    leader.isDefined
  }

  override def localHostPort: String = hostPort

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    if (isLeader) Some(hostPort) else None
  }

  override def offerLeadership(candidate: ElectionCandidate): Unit = Async.async {
    lock {
      leader match {
        case None =>
          logger.info(s"$candidate offered leadership")
          leader = Option(candidate)
          try {
            startLeadership()
          } catch {
            case NonFatal(ex) =>
              logger.error("Fatal error while starting leadership", ex)
              stop(exit = true)
          }
        case _ =>
          logger.error(s"$candidate offered leadership though there is an existing leader")
          stop(exit = true)
      }
    }
  }

  override def abdicateLeadership(): Unit = lock {
    logger.info("Abdicating leadership")
    stop(exit = true)
  }

  private def stop(exit: Boolean): Unit = lock {
    logger.info("Stopping the election service")
    try {
      stopLeadership()
    } catch {
      case NonFatal(ex) =>
        logger.error("Fatal error while stopping", ex)
    } finally {
      leader = None
      if (exit) {
        system.scheduler.scheduleOnce(500.milliseconds) {
          Runtime.getRuntime.asyncExit()
        }
      }
    }
  }

  private def startLeadership(): Unit = lock {
    leader.foreach { candidate =>
      startCandidateLeadership(candidate)
      logger.info(s"$candidate has started")
    }
    startMetrics()
  }

  private def stopLeadership(): Unit = lock {
    stopMetrics()
    leader.foreach { candidate =>
      stopCandidateLeadership(candidate)
      logger.info(s"$candidate has stopped")
    }
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
