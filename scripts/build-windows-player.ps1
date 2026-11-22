[CmdletBinding()]
param(
    [switch] $SkipDependencyInstall
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'Continue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot 'player\build\mpv-runtime'
$mpvApi = 'https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest'

function Invoke-WingetInstall([string] $Id) {
    Write-Host "Installing $Id..."
    winget install --exact --id $Id --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        $code = $LASTEXITCODE
        throw "winget failed installing $Id (exit $code). If this is WiX, rerun in an elevated PowerShell window; WiX requires administrator rights and Windows feature .NET Framework 3.5 (NetFx3)."
    }
}

function Refresh-ToolPath {
    $machinePath = [Environment]::GetEnvironmentVariable('PATH', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    $env:PATH = "$machinePath;$userPath"
    $pathCandidates = @((Join-Path $env:ProgramFiles '7-Zip'))
    foreach ($programFiles in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (-not $programFiles) { continue }
        $jdkRoot = Join-Path $programFiles 'Eclipse Adoptium'
        if (Test-Path $jdkRoot) {
            $pathCandidates += Get-ChildItem $jdkRoot -Directory -Filter 'jdk-25*' |
                ForEach-Object { Join-Path $_.FullName 'bin' }
        }
        $pathCandidates += Get-ChildItem $programFiles -Directory -Filter 'WiX Toolset v3*' -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin' }
    }
    foreach ($candidate in $pathCandidates) {
        if ($candidate -and (Test-Path $candidate) -and ($env:PATH -notlike "*$candidate*")) {
            $env:PATH = "$candidate;$env:PATH"
        }
    }
}

function Test-Jdk25 {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { return $false }
    # java -version writes to stderr; PowerShell 5 treats native stderr as an error record
    # when ErrorActionPreference is Stop. Let cmd.exe capture that stream as ordinary text.
    $versionOutput = & $env:ComSpec /d /c "`"$($java.Source)`" -version 2>&1"
    return (($versionOutput -join "`n") -match 'version "25\.')
}

function Test-Wix3 {
    return [bool](Get-Command candle.exe -ErrorAction SilentlyContinue) -and
        [bool](Get-Command light.exe -ErrorAction SilentlyContinue)
}

function Test-SevenZip {
    return [bool](Get-Command 7z.exe -ErrorAction SilentlyContinue)
}

Refresh-ToolPath
if (-not $SkipDependencyInstall) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'winget is missing. Install/update App Installer from Microsoft Store, then rerun this script.'
    }

    $needsJdk = -not (Test-Jdk25)
    $needsWix = -not (Test-Wix3)
    $needs7zip = -not (Test-SevenZip)
    $needsAdmin = $needsWix
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($needsAdmin -and -not $isAdmin) {
        Write-Host 'WiX is missing and its installer requires Administrator rights and .NET Framework 3.5 (NetFx3). Restarting this script with a UAC prompt...'
        $elevationArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
        $elevated = Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $elevationArgs -Wait -PassThru
        exit $elevated.ExitCode
    }

    if ($needsWix) {
        $netFx3 = Get-WindowsOptionalFeature -Online -FeatureName NetFx3
        if ($netFx3.State -ne 'Enabled') {
            Write-Host 'Enabling Windows feature .NET Framework 3.5 (NetFx3), required by WiX...'
            Enable-WindowsOptionalFeature -Online -FeatureName NetFx3 -All -NoRestart | Out-Null
        }
    }
    if ($needsJdk) { Invoke-WingetInstall 'EclipseAdoptium.Temurin.25.JDK' }
    if ($needsWix) { Invoke-WingetInstall 'WiXToolset.WiXToolset' }
    if ($needs7zip) { Invoke-WingetInstall '7zip.7zip' }
    Refresh-ToolPath
}

if (-not (Test-Jdk25)) {
    throw 'JDK 25 was not found on PATH. Open a new PowerShell window and rerun this script.'
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) { throw 'jpackage is missing from PATH.' }
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or
    -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    throw 'WiX 3 tools (candle.exe and light.exe) were not found. Reopen PowerShell or check the WiX installation.'
}
$sevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue
if (-not $sevenZip) { throw '7z.exe was not found. Reopen PowerShell or install 7-Zip.' }

Write-Host 'Finding the latest x64 libmpv runtime...'
$release = Invoke-RestMethod -Uri $mpvApi -Headers @{ 'User-Agent' = 'WebPresenter-build-script' }
$devAsset = $release.assets | Where-Object { $_.name -match '^mpv-dev-x86_64-.*\.7z$' } | Select-Object -First 1
$appAsset = $release.assets | Where-Object { $_.name -match '^mpv-x86_64-\d{8}-git-.*\.7z$' } | Select-Object -First 1
if (-not $devAsset -or -not $appAsset) {
    throw "Could not find matching x64 mpv development/runtime archives in release $($release.tag_name)."
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$archiveDir = Join-Path $runtimeDir 'archives'
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
$devArchive = Join-Path $archiveDir $devAsset.name
$appArchive = Join-Path $archiveDir $appAsset.name
$oldProgressPreference = $ProgressPreference
try {
    # Suppress the slow per-byte progress rendering only for these downloads, not DISM/NetFx3.
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $devAsset.browser_download_url -OutFile $devArchive
    Invoke-WebRequest -Uri $appAsset.browser_download_url -OutFile $appArchive
} finally {
    $ProgressPreference = $oldProgressPreference
}

# The dev archive supplies libmpv-2.dll; the full mpv archive supplies its runtime DLL dependencies.
& $sevenZip.Source x $devArchive "-o$runtimeDir" -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the mpv development archive.' }
& $sevenZip.Source x $appArchive "-o$runtimeDir" -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the mpv runtime archive.' }

$mpvDll = Get-ChildItem -Path $runtimeDir -Filter 'libmpv-2.dll' -Recurse | Select-Object -First 1
if (-not $mpvDll) { throw 'libmpv-2.dll was not present in the downloaded mpv archives.' }
$dlls = Join-Path $runtimeDir 'dlls'
New-Item -ItemType Directory -Force -Path $dlls | Out-Null
Get-ChildItem -Path $runtimeDir -Filter '*.dll' -Recurse |
    Where-Object { $_.FullName -notlike "$dlls\*" } |
    Copy-Item -Destination $dlls -Force

Write-Host 'Building the self-contained Windows installer...'
Push-Location $repoRoot
try {
    .\gradlew.bat :player:windowsInstaller "-PmpvDir=$dlls"
    if ($LASTEXITCODE -ne 0) { throw "Gradle packaging failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

Write-Host "Installer created under $repoRoot\player\build\windows-installer"
