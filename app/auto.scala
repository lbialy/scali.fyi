package app

import besom.model.FullyQualifiedStackName
import besom.auto.internal.*
import org.checkerframework.checker.units.qual.m

class Auto(val pulumiAccessToken: String):
  def deploy(subdomain: String, content: String)(using cfg: Config): Either[Throwable, String] =
    val stackName = FullyQualifiedStackName("lbialy", "scalify", s"$subdomain-prod")

    try
      logout().orThrow

      login(LoginOption.PulumiAccessToken(pulumiAccessToken)).orThrow

      val workspace = localWorkspace(
        LocalWorkspaceOption.Repo(
          GitRepo(
            url = "https://github.com/lbialy/scali.fyi.git",
            projectPath = "template"
          )
        ),
        LocalWorkspaceOption.Project(
          Project(
            name = "scalify",
            runtime = ProjectRuntimeInfo(
              name = "scala"
            )
          )
        ),
        LocalWorkspaceOption.EnvVars(
          shell.pulumi.env.PulumiConfigPassphraseEnv -> "pass",
          "BESOM_LANGHOST_SCALA_CLI_OPTS" -> "--server=false"
        )
      ).orThrow

      val stack = upsertStackRemoteSource(
        stackName,
        GitRepo(
          url = "https://github.com/lbialy/scali.fyi.git",
          projectPath = "template"
        ),
        LocalWorkspaceOption.Project(
          Project(
            name = "scalify",
            runtime = ProjectRuntimeInfo(
              name = "scala"
            )
          )
        ),
        LocalWorkspaceOption.EnvVars(shell.pulumi.env.PulumiConfigPassphraseEnv -> "pass")
      ).orThrow

      val config = Map[String, ConfigValue](
        "cloudflare_token" -> ConfigValue(cfg.cloudflareToken, secret = true),
        "cloudflare_zone_id" -> ConfigValue(cfg.cloudflareZoneId),
        "site_content" -> ConfigValue(content),
        "site_subdomain" -> ConfigValue(subdomain)
      )

      stack.setAllConfig(config).orThrow

      val upRes = stack.up().orThrow
      println(upRes)

      Right(subdomain)
    catch
      case e: Throwable => Left(e)

      // val stack = Stack(stackName)
      // stack.create(content)
      // Right(stackName.value)
