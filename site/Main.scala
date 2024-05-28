import besom.*
import besom.json.*
import besom.api.{kubernetes => k8s}
import besom.api.{cloudflare => cf}

import k8s.core.v1.enums.*
import k8s.core.v1.inputs.*
import k8s.apps.v1.inputs.*
import k8s.meta.v1.inputs.*
import k8s.apps.v1.{Deployment, DeploymentArgs}
import k8s.core.v1.{Namespace, NamespaceArgs, Service, ServiceArgs, PersistentVolumeClaim => PVC, PersistentVolumeClaimArgs => PVCArgs}
import k8s.networking.v1.{Ingress, IngressArgs}
import k8s.networking.v1.inputs.{
  IngressSpecArgs,
  IngressRuleArgs,
  HttpIngressRuleValueArgs,
  HttpIngressPathArgs,
  IngressBackendArgs,
  IngressServiceBackendArgs,
  ServiceBackendPortArgs
}

import besom.cfg.k8s.ConfiguredContainerArgs
import besom.cfg.Struct

@main def main = Pulumi.run {
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

  val k3sProvider = k8s.Provider(
    "k8s",
    k8s.ProviderArgs(
      kubeconfig = kubeconfig
    )
  )

  val appNamespace = Namespace(
    "app",
    NamespaceArgs(
      metadata = ObjectMetaArgs(
        name = "app"
      )
    ),
    opts(provider = k3sProvider)
  )

  val cloudflareToken   = config.requireString("cloudflare_token")
  val cloudflareZoneId  = config.requireString("cloudflare_zone_id")
  val pulumiAccessToken = config.requireString("pulumi_access_token")

  val appLabels   = Map("app" -> "scalify")
  val nginxLabels = Map("app" -> "nginx")

  val appContainerPort    = 8080
  val appServicePort      = 8080
  val nginxPort           = 80
  val nginxServicePort    = 8080
  val ingressHost         = "scali.fyi"
  val nginxContentPVCName = "nginx-content-pvc"
  val configMapName       = "nginx-configuration"

  val nginxContentPVC = PVC(
    nginxContentPVCName,
    PVCArgs(
      spec = PersistentVolumeClaimSpecArgs(
        accessModes = List("ReadWriteOnce"),
        storageClassName = "local-path",
        resources = VolumeResourceRequirementsArgs(
          requests = Map("storage" -> "1Gi")
        )
      ),
      metadata = ObjectMetaArgs(
        name = nginxContentPVCName,
        namespace = appNamespace.metadata.name
      )
    ),
    opts(provider = k3sProvider)
  )

  val nginxConfigurationConfigMap = k8s.core.v1.ConfigMap(
    "nginx-configuration-config-map",
    k8s.core.v1.ConfigMapArgs(
      data = Map(
        "default.conf" -> """
          |server {
          |    listen       80;
          |    listen  [::]:80;
          |    server_name  localhost;
          |
          |    location / {
          |        root   /usr/share/nginx/html/sites/$host;
          |        index  index.html index.htm;
          |    }
          |
          |    error_page  404              /404.html;
          |    location = /404.html {
          |        root   /usr/share/nginx/html;
          |    }
          |
          |    error_page   500 502 503 504  /50x.html;
          |    location = /50x.html {
          |        root   /usr/share/nginx/html;
          |    }
          |}
          |""".stripMargin
      ),
      metadata = ObjectMetaArgs(
        namespace = appNamespace.metadata.name,
        name = configMapName
      )
    ),
    opts(provider = k3sProvider)
  )

  val nginxDeployment = Deployment(
    "nginx",
    DeploymentArgs(
      spec = DeploymentSpecArgs(
        selector = LabelSelectorArgs(matchLabels = nginxLabels),
        replicas = 1,
        template = PodTemplateSpecArgs(
          metadata = ObjectMetaArgs(
            labels = nginxLabels
          ),
          spec = PodSpecArgs(
            containers = ContainerArgs(
              name = "nginx",
              image = "nginx:1.25.5",
              ports = List(
                ContainerPortArgs(name = "http", containerPort = nginxPort)
              ),
              volumeMounts = List(
                VolumeMountArgs(name = "nginx-conf", mountPath = "/etc/nginx/conf.d/default.conf", subPath = "default.conf"),
                VolumeMountArgs(name = "nginx-content", mountPath = "/usr/share/nginx/html/sites")
              )
            ) :: Nil,
            volumes = List(
              VolumeArgs(
                name = "nginx-conf",
                configMap = ConfigMapVolumeSourceArgs(
                  name = configMapName,
                  defaultMode = 420 // 0644
                )
              ),
              VolumeArgs(
                name = "nginx-content",
                persistentVolumeClaim = PersistentVolumeClaimVolumeSourceArgs(
                  claimName = nginxContentPVCName
                )
              )
            )
          )
        )
      ),
      metadata = ObjectMetaArgs(
        namespace = appNamespace.metadata.name
      )
    ),
    opts(provider = k3sProvider)
  )

  val bashPodWithNginxContentVolume = k8s.core.v1.Pod(
    "bash-pod",
    k8s.core.v1.PodArgs(
      metadata = ObjectMetaArgs(
        namespace = appNamespace.metadata.name
      ),
      spec = PodSpecArgs(
        containers = ContainerArgs(
          name = "bash",
          image = "bash:5.1.8",
          command = List("tail", "-f", "/dev/null"),
          volumeMounts = List(
            VolumeMountArgs(name = "nginx-content", mountPath = "/sites")
          )
        ) :: Nil,
        volumes = List(
          VolumeArgs(
            name = "nginx-content",
            persistentVolumeClaim = PersistentVolumeClaimVolumeSourceArgs(
              claimName = nginxContentPVCName
            )
          )
        )
      )
    ),
    opts(provider = k3sProvider, dependsOn = nginxDeployment)
  )

  val nginxService = Service(
    "nginx-svc",
    ServiceArgs(
      spec = ServiceSpecArgs(
        selector = nginxLabels,
        ports = List(
          ServicePortArgs(name = "http", port = nginxServicePort, targetPort = nginxPort)
        ),
        `type` = ServiceSpecType.ClusterIP
      ),
      metadata = ObjectMetaArgs(
        namespace = appNamespace.metadata.name,
        labels = nginxLabels
      )
    ),
    opts(deleteBeforeReplace = true, provider = k3sProvider, dependsOn = nginxDeployment)
  )

  val appDeployment =
    Deployment(
      "app-deployment",
      DeploymentArgs(
        spec = DeploymentSpecArgs(
          selector = LabelSelectorArgs(matchLabels = appLabels),
          replicas = 1,
          template = PodTemplateSpecArgs(
            metadata = ObjectMetaArgs(
              name = "app-deployment",
              labels = appLabels,
              namespace = appNamespace.metadata.name
            ),
            spec = PodSpecArgs(
              containers = ConfiguredContainerArgs(
                name = "app",
                image = "ghcr.io/lbialy/scalify:0.0.6",
                configuration = Struct(
                  port = appContainerPort,
                  pulumiAccessToken = pulumiAccessToken,
                  cloudflareZoneId = cloudflareZoneId,
                  cloudflareToken = cloudflareToken
                ),
                ports = List(
                  ContainerPortArgs(name = "http", containerPort = appContainerPort)
                ),
                readinessProbe = ProbeArgs(
                  httpGet = HttpGetActionArgs(
                    path = "/",
                    port = appContainerPort
                  ),
                  initialDelaySeconds = 10,
                  periodSeconds = 5
                ),
                livenessProbe = ProbeArgs(
                  httpGet = HttpGetActionArgs(
                    path = "/",
                    port = appContainerPort
                  ),
                  initialDelaySeconds = 10,
                  periodSeconds = 5
                )
              ) :: Nil
            )
          )
        ),
        metadata = ObjectMetaArgs(
          namespace = appNamespace.metadata.name
        )
      ),
      opts(provider = k3sProvider)
    )

  val appService =
    Service(
      "app-svc",
      ServiceArgs(
        spec = ServiceSpecArgs(
          selector = appLabels,
          ports = List(
            ServicePortArgs(name = "http", port = appServicePort, targetPort = appContainerPort)
          ),
          `type` = ServiceSpecType.ClusterIP
        ),
        metadata = ObjectMetaArgs(
          namespace = appNamespace.metadata.name,
          labels = appLabels
        )
      ),
      opts(deleteBeforeReplace = true, provider = k3sProvider)
    )

  val mainIngress = Ingress(
    "main-ingress",
    IngressArgs(
      spec = IngressSpecArgs(
        rules = List(
          IngressRuleArgs(
            host = s"*.$ingressHost",
            http = HttpIngressRuleValueArgs(
              paths = List(
                HttpIngressPathArgs(
                  path = "/",
                  pathType = "Prefix",
                  backend = IngressBackendArgs(
                    service = IngressServiceBackendArgs(
                      name = nginxService.metadata.name.getOrFail(Exception("nginx service name not found!")),
                      port = ServiceBackendPortArgs(
                        number = nginxServicePort
                      )
                    )
                  )
                )
              )
            )
          ),
          IngressRuleArgs(
            host = ingressHost,
            http = HttpIngressRuleValueArgs(
              paths = List(
                HttpIngressPathArgs(
                  path = "/",
                  pathType = "Prefix",
                  backend = IngressBackendArgs(
                    service = IngressServiceBackendArgs(
                      name = appService.metadata.name.getOrFail(Exception("app service name not found!")),
                      port = ServiceBackendPortArgs(
                        number = appServicePort
                      )
                    )
                  )
                )
              )
            )
          )
        )
      ),
      metadata = ObjectMetaArgs(
        namespace = appNamespace.metadata.name,
        annotations = Map(
          "kubernetes.io/ingress.class" -> "traefik"
        )
      )
    ),
    opts(provider = k3sProvider, dependsOn = nginxService)
  )

  val cfProvider = cf.Provider(
    "cloudflare-provider",
    cf.ProviderArgs(
      apiToken = cloudflareToken
    )
  )

  val aRecords = nodes
    .map(_.zipWithIndex)
    .flatMap { vec =>
      vec.map { case (ipv4Address, idx) =>
        val recordIdx = idx + 1

        cf.Record(
          s"scali.fyi-a-record-main-ingress-$recordIdx",
          cf.RecordArgs(
            name = ingressHost,
            `type` = "A",
            value = ipv4Address,
            zoneId = cloudflareZoneId,
            ttl = 1,
            proxied = true
          ),
          opts(provider = cfProvider)
        )
      }.parSequence
    }

  Stack(
    k3sProvider,
    appNamespace,
    appDeployment,
    appService,
    nginxConfigurationConfigMap,
    nginxContentPVC,
    mainIngress,
    bashPodWithNginxContentVolume,
    aRecords
  ).exports(
    appNamespace = appNamespace.metadata.name,
    accessPodName = bashPodWithNginxContentVolume.metadata.name
  )
}
