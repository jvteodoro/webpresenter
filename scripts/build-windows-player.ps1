[CmdletBinding()]
param(
    [switch] $SkipDependencyInstall
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot 'player\build\mpv-runtime'
$mpvReleases = 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/latest'

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

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw 'curl.exe is required to download the libmpv archives.' }

Write-Host 'Finding the latest x64 libmpv archive links...'
$releasePage = (Invoke-WebRequest -Uri $mpvReleases -Headers @{ 'User-Agent' = 'WebPresenter-build-script' }).Content
$devMatch = [regex]::Match($releasePage, 'href="(?<url>/shinchiro/mpv-winbuild-cmake/releases/download/[^" ]+/mpv-dev-x86_64-\d{8}-git-[^" ]+\.7z)"')
$appMatch = [regex]::Match($releasePage, 'href="(?<url>/shinchiro/mpv-winbuild-cmake/releases/download/[^" ]+/mpv-x86_64-\d{8}-git-[^" ]+\.7z)"')
if (-not $devMatch.Success -or -not $appMatch.Success) {
    throw 'Could not find x64 libmpv archives on the latest GitHub release page.'
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$archiveDir = Join-Path $runtimeDir 'archives'
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
$devArchive = Join-Path $archiveDir ([IO.Path]::GetFileName($devMatch.Groups['url'].Value))
$appArchive = Join-Path $archiveDir ([IO.Path]::GetFileName($appMatch.Groups['url'].Value))
& $curl.Source --fail --location --retry 5 --retry-all-errors --connect-timeout 30 --output $devArchive ("https://github.com" + $devMatch.Groups['url'].Value)
if ($LASTEXITCODE -ne 0) { throw "Could not download the libmpv development archive (curl exit $LASTEXITCODE)." }
& $curl.Source --fail --location --retry 5 --retry-all-errors --connect-timeout 30 --output $appArchive ("https://github.com" + $appMatch.Groups['url'].Value)
if ($LASTEXITCODE -ne 0) { throw "Could not download the libmpv runtime archive (curl exit $LASTEXITCODE)." }

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
