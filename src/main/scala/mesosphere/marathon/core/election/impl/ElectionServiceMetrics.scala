package mesosphere.marathon
package core.election.impl

import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }
import mesosphere.marathon.util.RichLock

private[impl] trait ElectionServiceMetrics {
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
