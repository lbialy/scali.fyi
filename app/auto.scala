package app

import besom.model.FullyQualifiedStackName
import besom.auto.internal.*
import org.checkerframework.checker.units.qual.m

class Auto(val pulumiAccessToken: String):
  def deploy(subdomain: String, content: String)(using cfg: Config): Either[Throwable, String] =
    val stackName = FullyQualifiedStackName("lbialy", "scalify", s"$subdomain-prod")

    for
      _ <- logout()
      _ <- login(LoginOption.PulumiAccessToken(pulumiAccessToken))
      workspace <- localWorkspace(
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
      )
      stack <- upsertStackRemoteSource(
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
      )
      config = Map[String, ConfigValue](
        "cloudflare_token" -> ConfigValue(cfg.cloudflareToken, secret = true),
        "cloudflare_zone_id" -> ConfigValue(cfg.cloudflareZoneId),
        "site_content" -> ConfigValue(content),
        "site_subdomain" -> ConfigValue(subdomain)
      )
      _ <- stack.setAllConfig(config)
      upRes <- stack.up()
      _ = println(upRes)
    yield subdomain
