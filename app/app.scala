package app

import besom.cfg.*

case class Config(
    port: Int,
    pulumiAccessToken: String,
    cloudflareToken: String,
    cloudflareZoneId: String
) derives Configured

@main def main() =
  given Config = resolveConfiguration[Config]

  Http.startServer()
