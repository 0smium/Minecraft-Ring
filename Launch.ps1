param([switch]$LiquidBounce, [switch]$B01)
$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$Install = Get-Content -LiteralPath "$ProjectRoot\installation.json" -Raw | ConvertFrom-Json
$GameExe = Join-Path $Install.game_dir 'eldenring.exe'
if (!(Test-Path -LiteralPath $GameExe)) { throw "Elden Ring was not found: $GameExe" }
$LbGradleArgs = @()
$ClientTask = 'runClient'
if ($B01) {
    $LiquidBounce = $true
    $SkillsJar = Join-Path $ProjectRoot 'runtime\downloads\puffish_skills-0.19.2-1.21-fabric.jar'
    if (!(Test-Path -LiteralPath $SkillsJar) -or (Get-FileHash -LiteralPath $SkillsJar -Algorithm SHA256).Hash -ne '276CE0B5F15BCFEAFE632C8EABE0D2D3104B9B0362897E66FD049BCD217BA915') {
        throw 'Prepare the pinned B01 dependency first: python tools/prepare_b01_skills.py'
    }
    Write-Output 'B01 profile: fixed LiquidBounce + Pufferfish Skills 0.19.2; use /erbridge progression to bind and choose challenge mode.'
}
if ($LiquidBounce) {
    $LbJar = Join-Path $ProjectRoot 'runtime\downloads\liquidbounce.jar'
    if (!(Test-Path -LiteralPath $LbJar)) { throw 'LB Build #3530 has not been prepared; see docs/experiments.' }
    $LbDigest = [Security.Cryptography.SHA256]::Create()
    $LbInput = [IO.File]::OpenRead($LbJar)
    try { $LbHash = [BitConverter]::ToString($LbDigest.ComputeHash($LbInput)).Replace('-', '') }
    finally { $LbInput.Dispose(); $LbDigest.Dispose() }
    if ($LbHash -ne '1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D') {
        throw 'LiquidBounce JAR differs from the verified v0.22.0 / Build #3530.'
    }
    $LbGradleArgs = @("-PlbJar=$LbJar", '-PlbProduction=true')
    if ($B01) { $LbGradleArgs += '-Pb01=true' }
    $ClientTask = 'productionLbClient'
    Write-Output "LiquidBounce v0.22.0 / Build #3530 enabled (SHA-256 $LbHash)."
}
$env:ERMC_DIR = Join-Path $ProjectRoot 'runtime'
$env:ERBRIDGE = '1'
$env:JAVA_HOME = (Get-ChildItem "$ProjectRoot\.tools\java" -Directory | Select-Object -First 1).FullName
$env:GRADLE_USER_HOME = Join-Path $ProjectRoot '.tools\gradle-home'
New-Item -ItemType Directory -Path $env:ERMC_DIR -Force | Out-Null
$SharedFile = Join-Path $env:ERMC_DIR 'bridge.shm'
function Read-BridgeHeader {
    if (!(Test-Path -LiteralPath $SharedFile)) { return $null }
    try {
        $Header = New-Object byte[] 128
        $Stream = [IO.File]::Open($SharedFile, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try { $Read = $Stream.Read($Header, 0, $Header.Length) } finally { $Stream.Dispose() }
        if ($Read -ne $Header.Length -or [BitConverter]::ToUInt32($Header, 0) -ne 0x434D484D) { return $null }
        return [PSCustomObject]@{
            HostProcessId = [BitConverter]::ToUInt32($Header, 0x20)
            MinecraftProcessId = [BitConverter]::ToUInt32($Header, 0x24)
            MinecraftStartMs = [BitConverter]::ToUInt64($Header, 0x30)
            CoreStatus = [BitConverter]::ToInt32($Header, 0x44)
        }
    } catch { return $null }
}
$HostProcess = Get-Process eldenring -ErrorAction SilentlyContinue | Where-Object Path -eq $GameExe | Select-Object -First 1
if (!$HostProcess) {
    Write-Output 'Starting Elden Ring...'
    $HostProcess = Start-Process -FilePath $GameExe -WorkingDirectory $Install.game_dir -PassThru
}
Write-Output 'Waiting for Elden Ring to load the Minecraft bridge...'
$Watch = [Diagnostics.Stopwatch]::StartNew()
$Ready = $false
while ($Watch.Elapsed.TotalSeconds -lt 90) {
    $State = Read-BridgeHeader
    if ($State -and $State.HostProcessId -eq $HostProcess.Id) {
        if ($State.CoreStatus -eq 1) { $Ready = $true; break }
        if ($State.CoreStatus -lt 0) { throw "Elden Ring bridge failed to load (status $($State.CoreStatus)). See runtime\er-bridge.log." }
    }
    if (!(Get-Process -Id $HostProcess.Id -ErrorAction SilentlyContinue)) {
        throw 'Elden Ring closed during startup. Minecraft was not launched. See runtime\er-bridge.log.'
    }
    Start-Sleep -Milliseconds 500
}
if (!$Ready) { throw 'Elden Ring did not load the bridge. Close Elden Ring and start it through start.bat. See runtime\er-bridge.log.' }
Write-Output "Elden Ring bridge ready (PID $($HostProcess.Id)). Choose Continue in the game."
$State = Read-BridgeHeader
if ($State -and $State.MinecraftProcessId -gt 0) {
    $MinecraftProcess = Get-Process -Id $State.MinecraftProcessId -ErrorAction SilentlyContinue
    if ($MinecraftProcess -and $MinecraftProcess.Path -eq (Join-Path $env:JAVA_HOME 'bin\java.exe')) {
        $StartedMs = [DateTimeOffset]::new($MinecraftProcess.StartTime).ToUnixTimeMilliseconds()
        if ([Math]::Abs([double]$StartedMs - [double]$State.MinecraftStartMs) -lt 120000) {
            Write-Output 'Minecraft bridge is already running. Use F8 to switch control.'
            return
        }
    }
}
$MinecraftRun = Join-Path $ProjectRoot 'bridge-base\elden-ring\mc-bridge\run'
function Set-MinecraftSetting([string]$Path, [string]$Name, [string]$Value) {
    $Lines = @()
    if (Test-Path -LiteralPath $Path) {
        $Lines = @(Get-Content -LiteralPath $Path -Encoding UTF8 | Where-Object { !$_.StartsWith($Name + ':') })
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllLines($Path, [string[]]($Lines + ($Name + ':' + $Value)), [Text.UTF8Encoding]::new($false))
}
# Minecraft owns the input window; the bridge supplies its borderless overlay layout.
Set-MinecraftSetting (Join-Path $MinecraftRun 'options.txt') 'fullscreen' 'false'
Set-MinecraftSetting (Join-Path $MinecraftRun 'config\fullscreenfix.properties') 'startInFullscreen' 'false'
Write-Output "Starting Minecraft in windowed mode... The $ClientTask task stays active while you play."
$Gradle = (Get-ChildItem "$ProjectRoot\.tools\gradle" -Directory | Select-Object -First 1).FullName + '\bin\gradle.bat'
& $Gradle '-p' "$ProjectRoot\bridge-base\elden-ring\mc-bridge" '--no-daemon' '--no-configuration-cache' @LbGradleArgs 'remapJar' $ClientTask
if ($LASTEXITCODE -ne 0) { throw 'Minecraft launch failed; see the console and run/logs/latest.log.' }
