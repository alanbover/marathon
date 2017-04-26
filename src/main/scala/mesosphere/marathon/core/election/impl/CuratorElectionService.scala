package mesosphere.marathon
package core.election.impl

import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.ElectionService
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.framework.{ AuthInfo, CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.concurrent.Future
import scala.util.control.NonFatal

class CuratorElectionService(
  config: MarathonConf,
  hostPort: String,
  override protected val system: ActorSystem,
  override protected val eventStream: EventStream,
  override protected val lifecycleState: LifecycleState)
    extends ElectionService with ElectionServiceFSM {

  import ElectionServiceFSM._

  private lazy val client = provideCuratorClient()
  private var leaderLatch: Option[LeaderLatch] = None

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    synchronized {
      if (client.getState == CuratorFrameworkState.STOPPED) None
      else {
        try {
          leaderLatch.flatMap { latch =>
            val participant = latch.getLeader
            if (participant.isLeader) Some(participant.getId) else None
          }
        } catch {
          case NonFatal(ex) =>
            logger.error("Error while getting current leader", ex)
            None
        }
      }
    }
  }

  override def acquireLeadership(): Unit = synchronized {
    state match {
      case AcquiringLeadership(candidate) =>
        try {
          assert(leaderLatch.isEmpty)
          val latch = new LeaderLatch(
            client, config.zooKeeperLeaderPath + "-curator", hostPort)
          latch.addListener(LeaderChangeListener, threadExecutor)
          latch.start()
          leaderLatch = Some(latch)
        } catch {
          case NonFatal(ex) =>
            logger.error(
              s"Fatal error while trying to start leadership of $candidate and auxiliary services. Exiting now", ex)
            stop(exit = true)
        }
      case _ =>
        logger.warn(s"Ignoring the request because of being in state: $state")
    }
  }

  override protected def preStopLeadership(): Unit = synchronized {
    leaderLatch.foreach { latch =>
      try {
        if (client.getState != CuratorFrameworkState.STOPPED) latch.close()
      } catch {
        case NonFatal(ex) => logger.error("Could not close leader latch", ex)
      }
    }
    leaderLatch = None
  }

  private def provideCuratorClient(): CuratorFramework = {
    logger.info(s"Will do leader election through ${config.zkHosts}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val defaultAcl = new util.ArrayList[ACL]()
    defaultAcl.addAll(config.zkDefaultCreationACL)
    defaultAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val aclProvider = new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcl
      override def getAclForPath(path: String): util.List[ACL] = defaultAcl
    }

    val retryPolicy = new ExponentialBackoffRetry(1000, 10)
    val builder = CuratorFrameworkFactory.builder().
      connectString(config.zkHosts).
      sessionTimeoutMs(config.zooKeeperSessionTimeout().toInt).
      connectionTimeoutMs(config.zooKeeperConnectionTimeout().toInt).
      aclProvider(aclProvider).
      retryPolicy(retryPolicy)

    // optionally authenticate
    val client = (config.zkUsername, config.zkPassword) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Collections.singletonList(
          new AuthInfo("digest", (user + ":" + pass).getBytes("UTF-8"))))
          .build()
      case _ =>
        builder.build()
    }

    client.start()
    client.blockUntilConnected(config.zkTimeoutDuration.toMillis.toInt, TimeUnit.MILLISECONDS)
    client
  }

  /**
    * Listener which forwards leadership status events asynchronously via the provided function.
    *
    * We delegate the methods asynchronously so they are processed outside of the synchronized lock for LeaderLatch.setLeadership
    */
  private object LeaderChangeListener extends LeaderLatchListener {
    override def notLeader(): Unit = Future {
      logger.info(s"Leader defeated. New leader: ${leaderHostPort.getOrElse("-")}")
      stop(exit = true)
    }

    override def isLeader(): Unit = Future {
      logger.info("Leader elected")
      leadershipAcquired()
    }
  }
}
