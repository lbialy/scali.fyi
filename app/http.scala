package app

import sttp.tapir.*
import sttp.tapir.files.*
import sttp.model.Part
import sttp.tapir.server.jdkhttp.*
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.generic.auto.*
import java.util.concurrent.Executors
import java.io.File

object Http:
  case class CreateSiteRequest(subdomain: String, content: String)

  private val index =
    endpoint.get
      .out(htmlBodyUtf8)
      .handle(_ => Right(Templates.index()))

  private def createSite(using cfg: Config) =
    endpoint.post
      .in("create-site")
      .in(formBody[CreateSiteRequest])
      .out(htmlBodyUtf8)
      .handle { form =>
        Auto(cfg.pulumiAccessToken)
          .deploy(form.subdomain, form.content)
          .map(sd => Templates.response(s"Site created at <a href=\"https://$sd.scali.fyi\">$sd.scali.fyi</a>!"))
          .tap(_ => scribe.info(s"Deployed ${form.subdomain}.scali.fyi"))
          .left
          .map(e => scribe.error(e))
      }

  def startServer()(using cfg: Config) =
    JdkHttpServer()
      .options(
        JdkHttpServerOptions.Default.copy(interceptors = List(CORSInterceptor.default[Id]))
      )
      .executor(Executors.newVirtualThreadPerTaskExecutor())
      .addEndpoint(
        staticResourcesGetServerEndpoint("static")(
          classOf[App].getClassLoader,
          "/"
        )
      )
      .addEndpoint(createSite)
      .addEndpoint(index)
      .host("0.0.0.0")
      .port(cfg.port)
      .start()
