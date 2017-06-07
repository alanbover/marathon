
package mesosphere.marathon
package core.election.impl

import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.base.RichRuntime
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService, LocalLeadershipEvent }
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }

class PseudoElectionServiceTest extends AkkaUnitTest with Eventually {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds))

  class Fixture {
    val hostPort: String = "unresolvable:2181"
    val httpConfig: HttpConf = mock[HttpConf]
    val electionService: ElectionService = mock[ElectionService]
    val events: EventStream = new EventStream(system)
    val candidate: ElectionCandidate = mock[ElectionCandidate]
  }

  "PseudoElectionService" should {
    "leader is not set initially" in {
      val f = new Fixture
      val electionService = new PseudoElectionService(f.hostPort, system, f.events)

      electionService.leader should be(None)
    }

    "leader is eventually set after offerLeadership is called" in {
      val f = new Fixture
      val electionService = new PseudoElectionService(f.hostPort, system, f.events)

      Given("leadership is offered")
      electionService.offerLeadership(f.candidate)
      Then("leader is set")
      eventually { electionService.leader should equal(Some(f.candidate)) }

      Given("leadership is offered again")
      electionService.offerLeadership(f.candidate)
      Then("leader is set to None and Marathon stops")
      eventually { electionService.leader should equal(None) }
      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }

    "Marathon stops after abdicateLeadership while being Idle" in {
      val f = new Fixture
      val electionService = new PseudoElectionService(f.hostPort, system, f.events)

      Given("leadership is abdicated while not being leader")
      electionService.abdicateLeadership()
      Then("leader is None and Marathon stops")
      eventually { electionService.leader should be(None) }
      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }

    "events are sent" in {
      val f = new Fixture
      val events = mock[EventStream]

      val electionService = new PseudoElectionService(f.hostPort, system, events)

      Given("this instance is becoming a leader")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.leader should equal(Some(f.candidate)) }

      Then("the candidate is called, then an event is published")
      val order = Mockito.inOrder(events, f.candidate)
      eventually { order.verify(f.candidate).startLeadership() }
      eventually { order.verify(events).publish(LocalLeadershipEvent.ElectedAsLeader) }

      Given("this instance is abdicating")
      electionService.abdicateLeadership()
      eventually { electionService.leader should be(None) }

      Then("the candidate is called, then an event is published")
      eventually { order.verify(f.candidate).stopLeadership() }
      eventually { order.verify(events).publish(LocalLeadershipEvent.Standby) }

      Then("then Marathon stops")
      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }

    "Marathon stops after leadership abdication while beinbg a leader" in {
      val f = new Fixture
      val electionService = new PseudoElectionService(f.hostPort, system, f.events)

      Given("this instance becomes leader and then abdicates leadership")
      electionService.offerLeadership(f.candidate)
      eventually { electionService.leader should equal(Some(f.candidate)) }
      electionService.abdicateLeadership()

      Then("then state is Stopped and Marathon stops")
      eventually { electionService.leader should be(None) }
      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }

    "Marathon stops if a candidate's startLeadership fails" in {
      val f = new Fixture

      val electionService = new PseudoElectionService(f.hostPort, system, f.events)

      Mockito.when(f.candidate.startLeadership()).thenAnswer(new Answer[Unit] {
        override def answer(invocation: InvocationOnMock): Unit = {
          throw new Exception("candidate.startLeadership exception")
        }
      })

      Given("this instance is offering leadership and candidate.startLeadership throws an exception")
      electionService.offerLeadership(f.candidate)

      Then("the instance is stopped")
      eventually { electionService.leader should be(None) }
      exitCalled(RichRuntime.FatalErrorSignal).futureValue should be(true)
    }
  }
}
