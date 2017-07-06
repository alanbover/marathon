package mesosphere.marathon
package core.election.impl

import java.util
import java.util.Collections
import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }

import akka.actor.ActorSystem
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import mesosphere.marathon.util.RichLock
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.{ LeaderLatch, LeaderLatchListener }
import org.apache.curator.framework.{ AuthInfo, CuratorFramework, CuratorFrameworkFactory }
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.async.Async
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * This class implements leader election using Curator and (in turn) Zookeeper. It is used
  * when the high-availability mode is enabled.
  *
  * One can become a leader only. If the leadership is lost due to some reason, it shuts down Marathon.
  * Marathon gets stopped on leader abdication too.
  */

class CuratorElectionService(
  config: MarathonConf,
  hostPort: String,
  system: ActorSystem,
  override val eventStream: EventStream,
  lifecycleState: LifecycleState)
    extends ElectionService with ElectionServiceMetrics with ElectionServiceEventStream with StrictLogging {

  system.registerOnTermination(lock {
    logger.info("Stopping leadership on shutdown")
    stop(exit = false)
  })

  /**
    * This lock is used to linearize methods with side effects.
    * Performance impact doesn't matter here at all, methods of this class are called infrequently.
    */
  protected override val lock: RichLock = RichLock()

  private val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  /** We re-use the single thread executor here because some methods of this class might get blocked for a long time. */
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadExecutor)

  private var candidate: Option[ElectionCandidate] = None
  private var leader: Option[ElectionCandidate] = None

  private lazy val client = provideCuratorClient()
  private var leaderLatch: Option[LeaderLatch] = None

  override def isLeader: Boolean = lock {
    leader.isDefined
  }

  override def localHostPort: String = hostPort

  override def leaderHostPort: Option[String] = leaderHostPortMetric.blocking {
    lock {
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

  override def offerLeadership(candidate: ElectionCandidate): Unit = lock {
    logger.info(s"$candidate offered leadership")
    if (lifecycleState.isRunning) {
      (this.candidate, leader) match {
        case (None, None) =>
          this.candidate = Some(candidate)
          Async.async {
            logger.info("Going to acquire leadership")
            try {
              acquireLeadership()
            } catch {
              case NonFatal(ex) =>
                logger.error(s"Fatal error while acquiring leadership for $candidate. Exiting now", ex)
                stop(exit = true)
            }
          }
        case _ =>
          logger.error(s"Got another leadership offer (current candidate: $candidate, leader: $leader). Exiting now")
          stop(exit = true)
      }
    } else {
      logger.info("Not accepting the offer since Marathon is shutting down")
    }
  }

  private def acquireLeadership(): Unit = lock {
    (candidate, leader) match {
      case (Some(_), None) =>
        require(leaderLatch.isEmpty, "leaderLatch is not empty")

        val latch = new LeaderLatch(
          client, config.zooKeeperLeaderPath + "-curator", hostPort)
        latch.addListener(LeaderChangeListener, threadExecutor)
        latch.start()
        leaderLatch = Some(latch)
      case _ =>
        logger.error(
          s"Acquiring leadership in parallel to someone else (candidate: $candidate, leader: $leader). Exiting now")
        stop(exit = true)
    }
  }

  private def leadershipAcquired(): Unit = Async.async {
    lock {
      (candidate, leader) match {
        case (Some(_), None) =>
          try {
            startLeadership()
            leader = candidate
            candidate = None
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Fatal error while starting leadership of $candidate. Exiting now", ex)
              stop(exit = true)
          }
        case _ =>
          logger.error(s"Acquired leadership (candidate: $candidate, leader: $leader). Exiting now")
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
    candidate.foreach { candidate =>
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
    leaderLatch.foreach { latch =>
      try {
        if (client.getState != CuratorFrameworkState.STOPPED) latch.close()
      } catch {
        case NonFatal(ex) =>
          logger.error("Could not close leader latch", ex)
      }
    }
    leaderLatch = None
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

    val retryPolicy = new ExponentialBackoffRetry(1.second.toMillis.toInt, 10)
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
    * We delegate the methods asynchronously so they are processed outside of the synchronized lock
    * for LeaderLatch.setLeadership
    */
  private object LeaderChangeListener extends LeaderLatchListener {
    override def notLeader(): Unit = Async.async {
      logger.info(s"Leader defeated. New leader: ${leaderHostPort.getOrElse("-")}")
      stop(exit = true)
    }

    override def isLeader(): Unit = Async.async {
      logger.info("Leader elected")
      leadershipAcquired()
    }
  }
}
