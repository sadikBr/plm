# plm: install, start, stop and look after the whole platform on this computer (Windows).
# Run it through plm.cmd:
#
#   plm install [--lite] [--yes] [--dry-run]
#                        install what's missing (Git, Docker Desktop, JDK 21, Node.js) with winget, build,
#                        start, and choose the site administrator's password: the one command to begin with
#   plm start [--lite]   start the services, the backend and the web UI, and wait until they're ready
#   plm stop             stop the web UI, the backend and the services (data is kept)
#   plm restart [--lite] stop, then start
#   plm status           what's running, and where
#   plm logs [backend|ui|services]   follow a log (Ctrl-C to stop following)
#   plm admin-password   change the site administrator's (plmadmin's) password, or set a forgotten one
#   plm load-data [voltara|byd] [--company "Name"]
#                        load demo data: an EV maker (fictional, or BYD with its real car names), its plant
#   plm setup            only check the prerequisites, install the dependencies, build the backend
#   plm reset            stop everything and delete all local data (asks first)
#
# --lite starts fewer services (no OpenSearch, no Temporal): about 3 GB less memory. --yes answers
# yes to every question; --dry-run only shows what install would do. Works with Windows PowerShell
# 5.1 and PowerShell 7. On macOS, Linux and WSL, use ./plm (same commands).
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$State = Join-Path $Root '.plm'
New-Item -ItemType Directory -Force -Path $State | Out-Null
$ComposeFile = Join-Path $Root 'deploy\docker-compose.yml'
$UiUrl = 'http://localhost:5173'
$ApiUrl = 'http://localhost:8080'
$Keycloak = 'http://localhost:8180'
$AdminUser = 'plmadmin'
$script:Lite = ($args -contains '--lite')
$script:AssumeYes = (($args -contains '--yes') -or ($args -contains '-y'))
$script:DryRun = ($args -contains '--dry-run')
$script:Java = $null

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

# Asks a yes/no question; $default is the answer with --yes or when nobody can be asked.
function AskYesNo([string]$question, [bool]$default = $true) {
  if ($script:AssumeYes) { return $true }
  if (-not [Environment]::UserInteractive) { return $default }
  $hint = '[Y/n]'
  if (-not $default) { $hint = '[y/N]' }
  $answer = Read-Host "  $question $hint"
  if (-not $answer) { return $default }
  return $answer -match '^[Yy]'
}

# Commands installed a moment ago are on the machine's PATH, not yet this session's.
function RefreshPath {
  $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}

function JavaMajor([string]$exe) {
  # java prints its version on stderr: read it without letting 'Stop' turn that into an error.
  $ErrorActionPreference = 'Continue'
  $line = (& $exe -version 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'version' } | Select-Object -First 1)
  if ($line -match '"1\.(\d+)') { return [int]$Matches[1] }
  if ($line -match '"(\d+)') { return [int]$Matches[1] }
  return 0
}

# Java: the one on PATH if it's 21 or newer, else a JDK 21+ installed beside an older default
# (then JAVA_HOME points at it, for the build too).
function FindJava {
  $onPath = Get-Command java -ErrorAction SilentlyContinue
  $candidates = @()
  if ($onPath) { $candidates += $onPath.Source }
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
  foreach ($dir in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")) {
    if (Test-Path $dir) {
      Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue | ForEach-Object { $candidates += (Join-Path $_.FullName 'bin\java.exe') }
    }
  }
  foreach ($c in $candidates) {
    if (-not (Test-Path $c)) { continue }
    if ((JavaMajor $c) -ge 21) {
      $script:Java = $c
      if (-not $onPath -or $c -ne $onPath.Source) { $env:JAVA_HOME = Split-Path (Split-Path $c -Parent) -Parent }
      return $true
    }
  }
  return $false
}
function NodeOk {
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) { return $false }
  return [int](& node -p 'process.versions.node.split(".")[0]') -ge 22
}
function GitOk { return $null -ne (Get-Command git -ErrorAction SilentlyContinue) }
function DockerInstalled {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
  $ErrorActionPreference = 'Continue'
  & docker compose version *> $null
  return $LASTEXITCODE -eq 0
}
function DockerRunning {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
  $ErrorActionPreference = 'Continue'
  & docker info *> $null
  return $LASTEXITCODE -eq 0
}

# ---- prerequisites: check (start) ----
function CheckPrerequisites {
  Say 'Checking prerequisites'
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "Docker isn't installed: run plm install, or see README.md" }
  if (-not (DockerRunning)) { Fail "Docker isn't running: start Docker Desktop and try again" }
  if (-not (DockerInstalled)) { Fail 'Docker Compose v2 is missing: update Docker Desktop' }
  Ok 'Docker'
  if (-not (FindJava)) { Fail "JDK 21 or newer isn't installed: run plm install, or see README.md" }
  Ok "Java $(JavaMajor $script:Java)"
  if (-not (NodeOk)) { Fail "Node.js 22 or newer isn't installed: run plm install, or see README.md" }
  Ok "Node.js $(& node --version)"
}

# ---- prerequisites: install (install) ----
$Packages = [ordered]@{
  git = @('Git', 'Git.Git')
  docker = @('Docker Desktop', 'Docker.DockerDesktop')
  java = @('JDK 21', 'EclipseAdoptium.Temurin.21.JDK')
  node = @('Node.js', 'OpenJS.NodeJS.LTS')
}

function WslReady {
  if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) { return $false }
  $ErrorActionPreference = 'Continue'
  & wsl --status *> $null
  return $LASTEXITCODE -eq 0
}
function IsAdministrator {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  return (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function EnsureDockerRunning {
  if (DockerRunning) { return }
  $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
  if (-not (Test-Path $desktop)) { Fail "Docker Desktop isn't installed: run plm install again, or see README.md" }
  Write-Host '  Starting Docker Desktop (the first time, accept its terms in the window that opens)'
  if ($script:DryRun) { return }
  Start-Process $desktop
  RefreshPath
  if (-not (WaitFor { DockerRunning } 300 'Docker is running')) {
    Fail "Docker didn't start: open Docker Desktop, wait until it's ready (if it asks you to sign out or restart, do so), then run plm install again"
  }
}

function EnsurePrerequisites {
  Say 'Checking prerequisites'
  $missing = @()
  if (GitOk) { Ok 'Git' } else { $missing += 'git' }
  if ((DockerInstalled) -or (Test-Path (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'))) { Ok 'Docker Desktop' } else { $missing += 'docker' }
  if (FindJava) { Ok "Java $(JavaMajor $script:Java)" } else { $missing += 'java' }
  if (NodeOk) { Ok "Node.js $(& node --version)" } else { $missing += 'node' }

  # Docker Desktop runs on WSL 2.
  if (($missing -contains 'docker') -and -not (WslReady)) {
    Warn 'Docker Desktop needs WSL 2, which is not set up yet.'
    if (-not (IsAdministrator)) {
      Fail 'Open PowerShell as administrator (right-click, Run as administrator), run "wsl --install", restart Windows, then run plm install again'
    }
    if (AskYesNo 'Set up WSL 2 now (Windows must restart afterwards)?') {
      if ($script:DryRun) { Write-Host '    would run: wsl --install --no-distribution' } else { & wsl --install --no-distribution }
      Fail 'Restart Windows, then run plm install again'
    }
    Fail 'Set up WSL 2 ("wsl --install" as administrator), restart, then run plm install again'
  }

  if ($missing.Count -gt 0) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
      Fail 'winget is missing: install "App Installer" from the Microsoft Store, or install the prerequisites as README.md says'
    }
    $names = ($missing | ForEach-Object { $Packages[$_][0] }) -join ', '
    Write-Host ''
    Write-Host "  To install: $names; with winget (Windows may ask for permission)."
    if (-not (AskYesNo 'Install them now?')) { Fail 'Nothing installed. README.md lists the commands to install them yourself.' }
    $failed = @()
    foreach ($m in $missing) {
      $name = $Packages[$m][0]
      $id = $Packages[$m][1]
      Say "Installing $name"
      if ($script:DryRun) {
        Write-Host "    would run: winget install --id $id -e"
        continue
      }
      & winget install --id $id -e --accept-source-agreements --accept-package-agreements | Out-Host
      # winget answers -1978335189 when it's already there.
      if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) { $failed += $name }
    }
    if ($failed.Count -gt 0) {
      Fail "Installing $($failed -join ', ') failed: see the messages above, or README.md to do it by hand, then run plm install again"
    }
    if ($script:DryRun) { return }
    RefreshPath
    if (-not (GitOk)) { Fail "Git still isn't there: open a new terminal and run plm install again" }
    if (-not (FindJava)) { Fail "JDK 21 still isn't there: open a new terminal and run plm install again" }
    if (-not (NodeOk)) { Fail "Node.js still isn't there: open a new terminal and run plm install again" }
    Ok "Everything's installed"
  }
  EnsureDockerRunning
  if ($script:DryRun) { return }
  if (-not (DockerInstalled)) { Fail 'Docker Compose v2 is missing: update Docker Desktop' }
  Ok 'Docker is running'

  # Memory: the full set of services wants about 8 GB.
  $mem = 0
  try { $mem = [int64](& docker info --format '{{.MemTotal}}') } catch { $mem = 0 }
  if (-not $script:Lite -and $mem -gt 0 -and $mem -lt 7000000000) {
    Warn "Docker has $([math]::Round($mem / 1GB)) GB of memory; all the services need about 8 GB (Docker Desktop: Settings > Resources)."
    if (AskYesNo 'Start with fewer services (--lite: search in Postgres, workflows in the backend)?') { $script:Lite = $true }
  }
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
function StartServices {
  $lite = $script:Lite
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

function StartBackend {
  $lite = $script:Lite
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
  $p = Start-Process -FilePath $script:Java -ArgumentList @('-jar', 'backend\app\build\libs\app.jar') -WorkingDirectory $Root `
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

function StartEverything {
  InstallDependencies
  BuildBackend
  StartServices
  StartBackend
  StartUi
}

function ReadyMessage([bool]$passwordSet) {
  Write-Host ''
  Say "The platform is ready: $UiUrl"
  if ($passwordSet) {
    Write-Host "  Sign in as $AdminUser, the site administrator, with the password you chose."
  } else {
    Write-Host "  Sign in as $AdminUser, the site administrator: the first time with the password $AdminUser,"
    Write-Host '  after which it asks you to choose your own (or run plm admin-password).'
  }
  Write-Host '  Users alice, bob and carol: password = user name.'
  Write-Host "  API $ApiUrl/swagger-ui.html - mail http://localhost:8025"
  Write-Host '  plm status - plm logs backend - plm stop'
  Start-Process $UiUrl
}

function StartAll {
  CheckPrerequisites
  StartEverything
  ReadyMessage $false
}

# ---- the site administrator's password (Keycloak's admin API) ----
function KeycloakAdminToken {
  $user = 'admin'
  $password = 'admin'
  if ($env:KEYCLOAK_ADMIN_USER) { $user = $env:KEYCLOAK_ADMIN_USER }
  if ($env:KEYCLOAK_ADMIN_PASSWORD) { $password = $env:KEYCLOAK_ADMIN_PASSWORD }
  $body = @{ client_id = 'admin-cli'; grant_type = 'password'; username = $user; password = $password }
  return (Invoke-RestMethod -Method Post -Uri "$Keycloak/realms/master/protocol/openid-connect/token" -Body $body).access_token
}

function PlainText([Security.SecureString]$secure) {
  $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function AskPassword {
  for ($i = 0; $i -lt 3; $i++) {
    $first = PlainText (Read-Host "  New password for $AdminUser (8 characters or more)" -AsSecureString)
    if ($first.Length -lt 8) { Write-Host '  Too short.'; continue }
    if ($first -eq $AdminUser) { Write-Host '  Choose something other than its first password.'; continue }
    $second = PlainText (Read-Host '  The same again' -AsSecureString)
    if ($first -ne $second) { Write-Host '  They differ.'; continue }
    return $first
  }
  return $null
}

# Sets plmadmin's password. $when: 'ask' (only if it still has its first one) or 'always'.
# True when plmadmin has a password of its own (set now or before), false if it keeps the first.
function SetAdminPassword([string]$when) {
  Say "The site administrator's password"
  if (-not (WaitFor { HttpOk "$Keycloak/realms/plm/.well-known/openid-configuration" } 120 'Keycloak is answering')) {
    Fail "Keycloak isn't running: plm start first"
  }
  try { $token = KeycloakAdminToken } catch { Fail "Keycloak's own admin account didn't sign in (KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD)" }
  $headers = @{ Authorization = "Bearer $token" }
  $users = @(Invoke-RestMethod -Uri "$Keycloak/admin/realms/plm/users?username=$AdminUser&exact=true" -Headers $headers)
  if ($users.Count -eq 0) { Fail "There's no $AdminUser in Keycloak: see Troubleshooting in docs/DEVELOPMENT.md" }
  $id = $users[0].id
  $first = @($users[0].requiredActions) -contains 'UPDATE_PASSWORD'
  if ($when -eq 'ask' -and -not $first) {
    Ok "$AdminUser already has its own password (change it with plm admin-password)"
    return $true
  }
  if ($env:PLM_ADMIN_PASSWORD -and $env:PLM_ADMIN_PASSWORD -ne $AdminUser) {
    $password = $env:PLM_ADMIN_PASSWORD
    Write-Host '  Using PLM_ADMIN_PASSWORD.'
  } elseif ([Environment]::UserInteractive -and (-not $script:AssumeYes -or $when -eq 'always')) {
    Write-Host "  $AdminUser is the one account that administers the site. Choose its password:"
    $password = AskPassword
    if (-not $password) { Fail 'No password set: run plm admin-password to try again' }
  } else {
    Warn "$AdminUser keeps its first password, ${AdminUser}: it asks for a new one at the first sign-in"
    return $false
  }
  $body = @{ type = 'password'; value = $password; temporary = $false } | ConvertTo-Json
  try {
    Invoke-RestMethod -Method Put -Uri "$Keycloak/admin/realms/plm/users/$id/reset-password" -Headers $headers `
      -ContentType 'application/json' -Body $body | Out-Null
  } catch {
    Fail "Keycloak refused the password: $($_.Exception.Message)"
  }
  # The first-sign-in request for a new password is done with.
  try {
    Invoke-RestMethod -Method Put -Uri "$Keycloak/admin/realms/plm/users/$id" -Headers $headers `
      -ContentType 'application/json' -Body '{"requiredActions":[]}' | Out-Null
  } catch { }
  Ok "$AdminUser's password is set"
  Write-Host '  (scripts and end-to-end tests read it from PLM_ADMIN_PASSWORD)'
  return $true
}

function AdminPassword {
  if (-not (HttpOk "$Keycloak/realms/plm/.well-known/openid-configuration")) {
    Write-Host '  Starting Keycloak'
    if ((Compose @('up', '-d', 'keycloak')) -ne 0) { Fail "Keycloak didn't start: is Docker running?" }
  }
  $null = SetAdminPassword 'always'
}

function LoadData([string[]]$rest) {
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'Node.js is needed: run plm install' }
  if (-not (HttpOk "$ApiUrl/actuator/health")) { Fail "The platform isn't running: plm start first" }
  $had = $env:PLM_ADMIN_PASSWORD
  if (-not $env:PLM_ADMIN_PASSWORD) { $env:PLM_ADMIN_PASSWORD = PlainText (Read-Host "  $AdminUser's password" -AsSecureString) }
  try {
    & node (Join-Path $Root 'scripts\demo-data\load.mjs') @rest | Out-Host
  } finally {
    $env:PLM_ADMIN_PASSWORD = $had
  }
}

function InstallAll {
  Say 'Installing the PLM platform on this computer'
  if ($script:DryRun) { Write-Host '  (dry run: nothing is installed or changed)' }
  EnsurePrerequisites
  if ($script:DryRun) {
    Write-Host ''
    Say "Then: install the web UI's packages, build the backend, start everything, ask for $AdminUser's password."
    return
  }
  StartEverything
  $set = SetAdminPassword 'ask'
  ReadyMessage $set
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
  $answer = Read-Host "This deletes all local data (database, files, search index, passwords). Type 'reset' to go on"
  if ($answer -ne 'reset') { Fail 'Nothing deleted' }
  StopProcess 'ui'
  StopProcess 'backend'
  $null = Compose @('--profile', 'full', 'down', '-v')
  Ok 'Everything stopped, all local data deleted. plm install begins afresh.'
}

$command = if ($args.Count -gt 0) { $args[0] } else { '' }
switch ($command) {
  'install' { InstallAll }
  'setup' {
    CheckPrerequisites
    InstallDependencies
    BuildBackend
    Say 'Ready: plm start'
  }
  'start' { StartAll }
  'stop' { StopAll }
  'restart' {
    StopAll
    StartAll
  }
  'status' { ShowStatus }
  'logs' {
    $which = if ($args.Count -gt 1) { $args[1] } else { 'backend' }
    ShowLogs $which
  }
  'admin-password' { AdminPassword }
  'load-data' { LoadData ($args | Select-Object -Skip 1) }
  'reset' { ResetAll }
  default {
    Get-Content $PSCommandPath -TotalCount 21 | Select-Object -Skip 3 | ForEach-Object { $_ -replace '^# ?', '' }
    exit 1
  }
}
