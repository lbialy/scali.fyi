import besom.*
import besom.json.*
import besom.api.{cloudflare => cf}
import besom.api.command.local

// things that go in:
// 1. namespace of the access pod // stackref lbialy/scalify/site-prod
// 2. name of the access pod // stackref lbialy/scalify/site-prod
// 3. kubeconfig path // stackref lbialy/scalify/platform-prod
// 4. nodes // stackref lbialy/scalify/platform-prod
// 5. contents of the page // config: site_content
// 6. subdomain // config: site_subdomain
// 7. cloudflare token // config: cloudflare_token
// 8. cloudflare zone id // config: cloudflare_zone_id

@main def main = Pulumi.run {
  val ingressHost = "scali.fyi"

  val platformStackRef = StackReference("platform-stackRef", StackReferenceArgs("lbialy/scalify/platform-prod"))

  val kubeconfig = platformStackRef.flatMap(
    _.getOutput("kubeconfig")
      .flatMap {
        case Some(JsString(s)) => Output(s)
        case other             => Output.fail(RuntimeException(s"Expected string, got $other"))
      }
  )

  val nodes = platformStackRef.flatMap(
    _.getOutput("nodes")
      .flatMap {
        case Some(JsArray(arr)) => Output(arr.collect { case JsString(s) => s })
        case other              => Output.fail(RuntimeException(s"Expected array, got $other"))
      }
  )

  val siteStackRef = StackReference("site-stackRef", StackReferenceArgs("lbialy/scalify/site-prod"))

  val namespace = siteStackRef.flatMap(
    _.getOutput("appNamespace")
      .flatMap {
        case Some(JsString(s)) => Output(s)
        case other             => Output.fail(RuntimeException(s"Expected string, got $other"))
      }
  )

  val accessPodName = siteStackRef.flatMap(
    _.getOutput("accessPodName")
      .flatMap {
        case Some(JsString(s)) => Output(s)
        case other             => Output.fail(RuntimeException(s"Expected string, got $other"))
      }
  )

  val kubeconfigPath = kubeconfig.map { kubeconfig =>
    os.temp(kubeconfig).toString
  }

  val siteContent   = config.requireString("site_content")
  val siteSubdomain = config.requireString("site_subdomain")

  val siteDirectory = p"$siteSubdomain.$ingressHost"

  val createIndexDirectory = siteContent.zip(siteDirectory).flatMap { (content, siteDirectory) =>
    val tmpDir              = os.temp.dir()
    val pathToSiteDirectory = tmpDir / siteDirectory
    os.makeDir.all(pathToSiteDirectory)
    os.write.over(pathToSiteDirectory / "index.html", content)

    log.info(s"Created site directory at $pathToSiteDirectory").map(_ => pathToSiteDirectory)
  }

  val kubectlCpSiteDirectory = createIndexDirectory.flatMap { pathToSiteDirectory =>
    local.Command(
      "kubectlCpSiteDirectory",
      local.CommandArgs(
        create = p"kubectl --kubeconfig $kubeconfigPath cp $pathToSiteDirectory $namespace/$accessPodName:/sites/"
      )
    )
  }

  val cfProvider = cf.Provider(
    "cloudflare-provider",
    cf.ProviderArgs(
      apiToken = config.requireString("cloudflare_token")
    )
  )

  val aRecords = nodes
    .map(_.zipWithIndex)
    .zip(siteSubdomain)
    .flatMap { (vec, subdomain) =>
      vec.map { case (ipv4Address, idx) =>
        val recordIdx = idx + 1

        cf.Record(
          s"scali.fyi-a-record-$subdomain-node-$recordIdx",
          cf.RecordArgs(
            name = s"$subdomain.$ingressHost", // TODO this should be done in template stack
            `type` = "A",
            value = ipv4Address,
            zoneId = config.requireString("cloudflare_zone_id"),
            ttl = 1,
            proxied = true
          ),
          opts(provider = cfProvider)
        )
      }.parSequence
    }

  Stack(namespace, accessPodName, kubectlCpSiteDirectory, aRecords).exports(
    kubeconfigPath = kubeconfigPath,
    siteContent = siteContent,
    siteSubdomain = siteSubdomain
  )
}
