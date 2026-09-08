$ErrorActionPreference = "Stop"

$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$Compose = @(
  "-f", (Join-Path $Root "docker-compose.post-e50.yml"),
  "-f", (Join-Path $Root "docker-compose.post-e50.demo.yml"),
  "-p", "pfi-post-e50-demo"
)

docker compose @Compose down
$DemoVolume = "post-e50-demo-postgres-data"
$VolumeExists = docker volume ls --format "{{.Name}}" | Where-Object { $_ -eq $DemoVolume }
if ($VolumeExists) {
  docker volume rm $DemoVolume
}
docker compose @Compose up -d --build
node (Join-Path $PSScriptRoot "post-e50-demo-seed.mjs")
& (Join-Path $PSScriptRoot "post-e50-demo-status.ps1")
