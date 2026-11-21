# plm: start, stop and look after the whole platform on this computer (Windows).
# Run it through plm.cmd:
#
#   plm setup            check the prerequisites, install the dependencies, build the backend
#   plm start [--lite]   start the services, the backend and the web UI, and wait until they're ready
#   plm stop             stop the web UI, the backend and the services (data is kept)
#   plm restart [--lite] stop, then start
#   plm status           what's running, and where
#   plm logs [backend|ui|services]   follow a log (Ctrl-C to stop following)
#   plm reset            stop everything and delete all local data (asks first)
#
# --lite starts fewer services (no OpenSearch, no Temporal). Works with Windows PowerShell 5.1 and
# PowerShell 7. On macOS, Linux and WSL, use ./plm (same commands).
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$State = Join-Path $Root '.plm'
New-Item -ItemType Directory -Force -Path $State | Out-Null
$ComposeFile = Join-Path $Root 'deploy\docker-compose.yml'
$UiUrl = 'http://localhost:5173'
$ApiUrl = 'http://localhost:8080'

function Say([string]$text) { Write-Host $text -ForegroundColor White }
function Ok([string]$text) { Write-Host "  [ok] $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "  [!!] $text" -ForegroundColor Yellow }
function Fail([string]$text) {
  Write-Host "  [xx] $text" -ForegroundColor Red
  exit 1
}

# Runs docker compose, its output to the console (not into the function's result), and returns the exit code.
function Compose([string[]]$arguments) {
  & docker compose -f $ComposeFile @arguments | Out-Host
  return $LASTEXITCODE
}

# ---- processes we started: a pid file each ----
function PidOf([string]$name) {
  $file = Join-Path $State "$name.pid"
  if (Test-Path $file) { return (Get-Content $file -Raw).Trim() }
  return $null
}
function IsRunning([string]$name) {
  $id = PidOf $name
  if (-not $id) { return $false }
  return $null -ne (Get-Process -Id $id -ErrorAction SilentlyContinue)
}
function StopProcess([string]$name) {
  $id = PidOf $name
  if ($id -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) {
    # /T: with the processes it started (the UI's esbuild, for one).
    & taskkill /PID $id /T /F *> $null
    Ok "$name stopped"
  }
  Remove-Item (Join-Path $State "$name.pid") -ErrorAction SilentlyContinue
}

function PortOpen([int]$port) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $task = $client.ConnectAsync('127.0.0.1', $port)
    return ($task.Wait(500) -and $client.Connected)
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}
function HttpOk([string]$url) {
  try {
    $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
    return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400)
  } catch {
    return $false
  }
}
# Waits up to $seconds for $check (a script block) to return true.
function WaitFor([scriptblock]$check, [int]$seconds, [string]$what) {
  for ($i = 0; $i -lt $seconds; $i++) {
    if (& $check) {
      Ok $what
      return $true
    }
    Start-Sleep -Seconds 1
  }
  return $false
}

# ---- prerequisites ----
function CheckPrerequisites {
  Say 'Checking prerequisites'
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker isn't installed: see 'Install the prerequisites' in README.md" }
  & docker info *> $null
  if ($LASTEXITCODE -ne 0) { Fail "Docker isn't running: start Docker Desktop and try again" }
  & docker compose version *> $null
  if ($LASTEXITCODE -ne 0) { Fail 'Docker Compose v2 is missing: update Docker Desktop' }
  Ok 'Docker'
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail "Java isn't installed: install JDK 21 (see README.md)" }
  $versionLine = (cmd /c 'java -version 2>&1' | Select-Object -First 1)
  $major = 0
  if ($versionLine -match '"(\d+)') { $major = [int]$Matches[1] }
  if ($major -lt 21) { Fail "Java $major found; JDK 21 or newer is needed (see README.md)" }
  Ok "Java $major"
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail "Node.js isn't installed: install Node.js 22 (see README.md)" }
  $nodeMajor = [int](& node -p 'process.versions.node.split(".")[0]')
  if ($nodeMajor -lt 22) { Fail "Node.js $nodeMajor found; 22 or newer is needed (see README.md)" }
  Ok "Node.js $(& node --version)"
}

function NeedsInstall([string]$dir) {
  $modules = Join-Path $dir 'node_modules'
  $lock = Join-Path $dir 'package-lock.json'
  if (-not (Test-Path $modules)) { return $true }
  return (Get-Item $lock).LastWriteTime -gt (Get-Item $modules).LastWriteTime
}

function InstallDependencies {
  Say 'Installing dependencies'
  $ui = Join-Path $Root 'frontend'
  if (NeedsInstall $ui) {
    Push-Location $ui
    try { & npm ci --no-audit --no-fund } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail "Installing the web UI's packages failed" }
  }
  Ok 'Web UI packages'
  $converter = Join-Path $Root 'workers\cad-converter'
  if (NeedsInstall $converter) {
    Push-Location $converter
    try { & npm ci --no-audit --no-fund } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Warn "The CAD converter didn't install: 3D views won't be made" }
  }
  Ok 'CAD converter'
}

function BuildBackend {
  Say 'Building the backend'
  Write-Host '  (the first build downloads dependencies and takes a few minutes)'
  $log = Join-Path $State 'build.log'
  Push-Location $Root
  try { & cmd /c "gradlew.bat -q :backend:app:bootJar > `"$log`" 2>&1" } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) {
    Get-Content $log -Tail 30
    Fail "The backend didn't build: see .plm\build.log"
  }
  Ok 'backend\app\build\libs\app.jar'
}

# ---- start ----
function StartServices([bool]$lite) {
  Say 'Starting the services'
  if ($lite) { $code = Compose @('up', '-d') } else { $code = Compose @('--profile', 'full', 'up', '-d') }
  if ($code -ne 0) { Fail "The services didn't start: plm logs services" }
  if (-not (WaitFor { PortOpen 5432 } 120 'PostgreSQL on :5432')) { Fail "PostgreSQL didn't start: plm logs services" }
  if (-not (WaitFor { HttpOk 'http://localhost:8180/realms/plm/.well-known/openid-configuration' } 180 'Keycloak on :8180')) {
    Fail "Keycloak didn't start: plm logs services"
  }
  if (-not (WaitFor { PortOpen 8333 } 60 'S3 storage on :8333')) { Warn "S3 storage isn't answering yet" }
  if (-not (WaitFor { PortOpen 19092 } 60 'Redpanda on :19092')) { Warn "Redpanda isn't answering yet (events wait in the outbox)" }
  if (-not $lite) {
    if (-not (WaitFor { HttpOk 'http://localhost:9200' } 180 'OpenSearch on :9200')) { Fail "OpenSearch didn't start: try plm start --lite" }
    if (-not (WaitFor { PortOpen 7233 } 120 'Temporal on :7233')) { Fail "Temporal didn't start: try plm start --lite" }
  }
}

function StartBackend([bool]$lite) {
  Say 'Starting the backend'
  if (IsRunning 'backend') {
    Ok "already running (pid $(PidOf 'backend'))"
    return
  }
  if (PortOpen 8080) { Fail 'Something else is using port 8080: stop it first' }
  if ($lite) {
    Remove-Item Env:PLM_SEARCH_ENGINE, Env:PLM_TEMPORAL_TARGET -ErrorAction SilentlyContinue
  } else {
    $env:PLM_SEARCH_ENGINE = 'opensearch'
    $env:PLM_TEMPORAL_TARGET = 'localhost:7233'
  }
  $p = Start-Process -FilePath 'java' -ArgumentList @('-jar', 'backend\app\build\libs\app.jar') -WorkingDirectory $Root `
    -RedirectStandardOutput (Join-Path $State 'backend.log') -RedirectStandardError (Join-Path $State 'backend.err.log') `
    -WindowStyle Hidden -PassThru
  Set-Content -Path (Join-Path $State 'backend.pid') -Value $p.Id
  if (-not (WaitFor { HttpOk "$ApiUrl/actuator/health" } 240 'Backend on :8080')) {
    Get-Content (Join-Path $State 'backend.log') -Tail 30
    Fail "The backend didn't come up: see .plm\backend.log"
  }
}

function StartUi {
  Say 'Starting the web UI'
  if (IsRunning 'ui') {
    Ok "already running (pid $(PidOf 'ui'))"
    return
  }
  if (PortOpen 5173) { Fail 'Something else is using port 5173: stop it first' }
  $p = Start-Process -FilePath 'node' -ArgumentList @('node_modules/vite/bin/vite.js', '--port', '5173', '--strictPort') `
    -WorkingDirectory (Join-Path $Root 'frontend') `
    -RedirectStandardOutput (Join-Path $State 'ui.log') -RedirectStandardError (Join-Path $State 'ui.err.log') `
    -WindowStyle Hidden -PassThru
  Set-Content -Path (Join-Path $State 'ui.pid') -Value $p.Id
  if (-not (WaitFor { HttpOk $UiUrl } 60 'Web UI on :5173')) {
    Get-Content (Join-Path $State 'ui.err.log') -Tail 20
    Fail "The web UI didn't come up: see .plm\ui.log"
  }
}

function StartAll([bool]$lite) {
  CheckPrerequisites
  InstallDependencies
  BuildBackend
  StartServices $lite
  StartBackend $lite
  StartUi
  Write-Host ''
  Say "The platform is ready: $UiUrl"
  Write-Host '  Sign in as plmadmin, the site administrator: the first time, with the password plmadmin,'
  Write-Host '  after which it asks you to choose your own. Users alice, bob and carol: password = user name.'
  Write-Host "  API $ApiUrl/swagger-ui.html - mail http://localhost:8025"
  Write-Host '  plm status - plm logs backend - plm stop'
  Start-Process $UiUrl
}

function StopAll {
  Say 'Stopping'
  StopProcess 'ui'
  StopProcess 'backend'
  $code = Compose @('--profile', 'full', 'stop')
  if ($code -eq 0) { Ok 'services stopped (data kept)' } else { Warn 'No services to stop' }
}

function ShowStatus {
  Say 'Platform status'
  if (IsRunning 'ui') { Ok "Web UI      $UiUrl (pid $(PidOf 'ui'))" } else { Warn 'Web UI      not running' }
  if ((IsRunning 'backend') -and (HttpOk "$ApiUrl/actuator/health")) {
    Ok "Backend     $ApiUrl (pid $(PidOf 'backend'))"
  } elseif (IsRunning 'backend') {
    Warn "Backend     starting (pid $(PidOf 'backend'))"
  } else {
    Warn 'Backend     not running'
  }
  $services = [ordered]@{ PostgreSQL = 5432; Keycloak = 8180; S3 = 8333; Redpanda = 19092; Mailpit = 8025; OpenSearch = 9200; Temporal = 7233 }
  foreach ($name in $services.Keys) {
    $port = $services[$name]
    if (PortOpen $port) { Ok ('{0,-11} :{1}' -f $name, $port) } else { Warn ('{0,-11} not running' -f $name) }
  }
}

function ShowLogs([string]$which) {
  switch ($which) {
    'backend' { Get-Content (Join-Path $State 'backend.log') -Tail 100 -Wait }
    'ui' { Get-Content (Join-Path $State 'ui.log') -Tail 100 -Wait }
    'services' { $null = Compose @('--profile', 'full', 'logs', '-f', '--tail', '100') }
    default { Fail 'Which log: backend, ui or services' }
  }
}

function ResetAll {
  $answer = Read-Host "This deletes all local data (database, files, search index). Type 'reset' to go on"
  if ($answer -ne 'reset') { Fail 'Nothing deleted' }
  StopProcess 'ui'
  StopProcess 'backend'
  $null = Compose @('--profile', 'full', 'down', '-v')
  Ok 'Everything stopped, all local data deleted. plm start begins afresh.'
}

$command = if ($args.Count -gt 0) { $args[0] } else { '' }
$lite = ($args -contains '--lite')
switch ($command) {
  'setup' {
    CheckPrerequisites
    InstallDependencies
    BuildBackend
    Say 'Ready: plm start'
  }
  'start' { StartAll $lite }
  'stop' { StopAll }
  'restart' {
    StopAll
    StartAll $lite
  }
  'status' { ShowStatus }
  'logs' {
    $which = if ($args.Count -gt 1) { $args[1] } else { 'backend' }
    ShowLogs $which
  }
  'reset' { ResetAll }
  default {
    Get-Content $PSCommandPath -TotalCount 12 | Select-Object -Skip 3 | ForEach-Object { $_ -replace '^# ?', '' }
    exit 1
  }
}
