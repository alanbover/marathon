package mesosphere.marathon
package core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.ElectionService

import scala.concurrent.Future
import scala.util.control.NonFatal

class PseudoElectionService(
  hostPort: String,
  override protected val system: ActorSystem,
  override protected val eventStream: EventStream,
  override protected val lifecycleState: LifecycleState)
    extends ElectionService with ElectionServiceFSM {

  import ElectionServiceFSM._

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    if (isLeader) Some(hostPort) else None
  }

  override protected def acquireLeadership(): Unit = synchronized {
    state match {
      case AcquiringLeadership(candidate) =>
        Future {
          try {
            leadershipAcquired()
          } catch {
            case NonFatal(ex) =>
              logger.error(
                s"Fatal error while trying to start leadership of $candidate and auxiliary services. Exiting now", ex)
              stop(exit = true)
          }
        }
      case _ =>
        logger.warn(s"Ignoring the request because of being in state: $state")
    }
  }
}
