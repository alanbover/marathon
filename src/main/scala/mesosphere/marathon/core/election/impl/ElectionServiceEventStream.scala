package mesosphere.marathon
package core.election.impl

import akka.actor.ActorRef
import akka.event.EventStream
import mesosphere.marathon.core.election.LocalLeadershipEvent
import mesosphere.marathon.util.RichLock

private[impl] trait ElectionServiceEventStream {
  protected val eventStream: EventStream
  protected val lock: RichLock

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
