package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Request, Response, Variant }

import akka.actor.ActorSystem
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import com.google.inject.Inject
import com.typesafe.config.{ Config, ConfigRenderOptions }
import com.typesafe.scalalogging.StrictLogging
import com.wix.accord.Validator
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.AuthorizedResource.{ SystemConfig, SystemMetrics }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateResource, ViewResource }
import mesosphere.marathon.raml.{ LoggerChange, Raml }
import mesosphere.marathon.raml.MetricsConversion._
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import stream.Implicits._
import com.wix.accord.dsl._

import scala.concurrent.duration._

/**
  * System Resource gives access to system level functionality.
  * All system resources can be protected via ACLs.
  */
@Path("")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class SystemResource @Inject() (val config: MarathonConf, cfg: Config)(implicit
  val authenticator: Authenticator,
    val authorizer: Authorizer,
    actorSystem: ActorSystem) extends RestResource with AuthResource with StrictLogging {

  /**
    * ping sends a pong to a client.
    *
    * ping doesn't use the `Produces` or `Consumes` tags because those do specific checking for a client
    * Accept header that may or may not exist. In the interest of compatibility we dynamically generate
    * a "pong" content object depending on the client's preferred Content-Type, or else assume `text/plain`
    * if the client either doesn't specify an Accept header, or else specifies one that we don't support.
    */
  @GET
  @Path("ping")
  def ping(@Context req: Request): Response = {
    import MediaType._

    val v = Variant.mediaTypes(
      TEXT_PLAIN_TYPE,
      APPLICATION_JSON_TYPE
    ).add.build

    Option[Variant](req.selectVariant(v)).filter(_.getMediaType.isCompatible(APPLICATION_JSON_TYPE)).map { rv =>
      // return a properly formatted JSON object
      Response.ok("\"pong\"").`type`(rv.getMediaType.toString).build
    }.getOrElse {
      // otherwise we send back plain text
      Response.ok("pong").`type`(TEXT_PLAIN).build
    }
  }

  @GET
  @Path("metrics")
  def metrics(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemMetrics){
      ok(jsonString(Raml.toRaml(Metrics.snapshot())))
    }
  }

  @GET
  @Path("config")
  def config(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      ok(cfg.root().render(ConfigRenderOptions.defaults().setJson(true)))
    }
  }

  @GET
  @Path("logging")
  def showLoggers(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      LoggerFactory.getILoggerFactory match {
        case lc: LoggerContext =>
          ok(lc.getLoggerList.map { logger =>
            logger.getName -> Option(logger.getLevel).map(_.levelStr).getOrElse(logger.getEffectiveLevel.levelStr + " (inherited)")
          }.toMap)
      }
    }
  }

  @POST
  @Path("logging")
  def changeLogger(body: Array[Byte], @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(UpdateResource, SystemConfig) {
      withValid(Json.parse(body).as[LoggerChange]) { change =>
        LoggerFactory.getILoggerFactory.getLogger(change.logger) match {
          case logger: Logger =>
            val level = Level.valueOf(change.level.value.toUpperCase)

            // current level can be null, which means: use the parent level
            // the current level should be preserved, no matter what the effective level is
            val currentLevel = logger.getLevel
            val currentEffectiveLevel = logger.getEffectiveLevel
            logger.info(s"Set logger ${logger.getName} to $level current: $currentEffectiveLevel")
            logger.setLevel(level)

            // if a duration is given, we schedule a timer to reset to the current level
            import mesosphere.marathon.core.async.ExecutionContexts.global
            change.durationSeconds.foreach(duration => actorSystem.scheduler.scheduleOnce(duration.seconds, new Runnable {
              override def run(): Unit = {
                logger.info(s"Duration expired. Reset Logger ${logger.getName} back to $currentEffectiveLevel")
                logger.setLevel(currentLevel)
              }
            }))
            ok(change)
        }
      }
    }
  }

  implicit lazy val validLoggerChange: Validator[LoggerChange] = validator[LoggerChange] { change =>
    change.logger is notEmpty
  }
}
