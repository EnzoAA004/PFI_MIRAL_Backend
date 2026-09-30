$ErrorActionPreference = "Stop"

$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$Compose = @(
  "-f", (Join-Path $Root "docker-compose.post-e50.yml"),
  "-f", (Join-Path $Root "docker-compose.post-e50.demo.yml"),
  "-p", "pfi-post-e50-demo"
)

docker compose @Compose down
