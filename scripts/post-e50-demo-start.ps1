$ErrorActionPreference = "Stop"

$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$Compose = @(
  "-f", (Join-Path $Root "docker-compose.post-e50.yml"),
  "-f", (Join-Path $Root "docker-compose.post-e50.demo.yml"),
  "-p", "pfi-post-e50-demo"
)

docker compose @Compose up -d --build
& (Join-Path $PSScriptRoot "post-e50-demo-status.ps1")
