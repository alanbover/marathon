package mesosphere.marathon
package core.election.impl

import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }

private[impl] trait ElectionServiceMetrics {
  protected val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"
  protected val leaderHostPortMetric: Timer = Metrics.timer(ServiceMetric, getClass, "current-leader-host-port")

  protected var metricsStarted = false

  protected def startMetrics(): Unit = synchronized {
    if (!metricsStarted) {
      val startedAt = System.currentTimeMillis()
      Kamon.metrics.gauge(leaderDurationMetric, Time.Milliseconds)(System.currentTimeMillis() - startedAt)
      metricsStarted = true
    }
  }

  protected def stopMetrics(): Unit = synchronized {
    if (metricsStarted) {
      Kamon.metrics.removeGauge(leaderDurationMetric)
      metricsStarted = false
    }
  }
}
