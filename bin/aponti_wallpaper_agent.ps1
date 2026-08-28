param(
  [string]$ServerUrl = "",
  [string]$Token = "",
  [int]$IntervalSeconds = 60,
  [switch]$Once
)

$ErrorActionPreference = "Stop"

$AgentVersion = "1.0.0"
$ConfigDir = Join-Path $env:ProgramData "ApontiTV\WallpaperAgent"
$ConfigPath = Join-Path $ConfigDir "agent.json"
$WallpaperPath = Join-Path $ConfigDir "wallpaper.jpeg"
$StatePath = Join-Path $ConfigDir "state.json"

function Normalize-ServerUrl {
  param([string]$Value)

  $clean = $Value.Trim().TrimEnd("/")
  if ([string]::IsNullOrWhiteSpace($clean)) { return "" }
  if ($clean -notmatch "^https?://") { $clean = "http://$clean" }
  return $clean
}

function Read-JsonFile {
  param([string]$Path)

  if (-not (Test-Path -LiteralPath $Path)) { return $null }
  $raw = Get-Content -LiteralPath $Path -Raw
  if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
  return $raw | ConvertFrom-Json
}

function Write-JsonFile {
  param(
    [string]$Path,
    [object]$Value
  )

  $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-FileSha256 {
  param([string]$Path)

  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Set-WindowsWallpaper {
  param([string]$Path)

  Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WallpaperApi {
  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool SystemParametersInfo(int uAction, int uParam, string lpvParam, int fuWinIni);
}
"@

  Set-ItemProperty -Path "HKCU:\Control Panel\Desktop" -Name WallpaperStyle -Value "10"
  Set-ItemProperty -Path "HKCU:\Control Panel\Desktop" -Name TileWallpaper -Value "0"
  [void][WallpaperApi]::SystemParametersInfo(20, 0, $Path, 3)
}

function Get-AgentConfig {
  New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null

  $config = Read-JsonFile -Path $ConfigPath
  if ($null -eq $config) {
    $config = [pscustomobject]@{
      server_url = ""
      token = ""
      interval_seconds = 60
    }
  }

  if (-not [string]::IsNullOrWhiteSpace($ServerUrl)) { $config.server_url = Normalize-ServerUrl $ServerUrl }
  if (-not [string]::IsNullOrWhiteSpace($Token)) { $config.token = $Token.Trim() }
  if ($IntervalSeconds -gt 0) { $config.interval_seconds = $IntervalSeconds }

  if ([string]::IsNullOrWhiteSpace($config.server_url) -or [string]::IsNullOrWhiteSpace($config.token)) {
    throw "Configure ServerUrl e Token. Exemplo: powershell -ExecutionPolicy Bypass -File bin\aponti_wallpaper_agent.ps1 -ServerUrl http://apontitv.com -Token SEU_TOKEN"
  }

  $config.server_url = Normalize-ServerUrl $config.server_url
  Write-JsonFile -Path $ConfigPath -Value $config
  return $config
}

function Invoke-AgentCycle {
  param([object]$Config)

  $headers = @{
    Authorization = "Bearer $($Config.token)"
    "X-Aponti-Agent-Hostname" = $env:COMPUTERNAME
    "X-Aponti-Agent-Version" = $AgentVersion
  }

  $payload = Invoke-RestMethod -Method Get -Uri "$($Config.server_url)/agent/wallpaper" -Headers $headers -TimeoutSec 30
  if (-not $payload.available) { return }

  $state = Read-JsonFile -Path $StatePath
  $currentVersion = if ($state) { $state.version } else { "" }
  if ($currentVersion -eq $payload.version -and (Test-Path -LiteralPath $WallpaperPath)) { return }

  $downloadPath = "$WallpaperPath.download"
  Invoke-WebRequest -UseBasicParsing -Uri $payload.url -OutFile $downloadPath -TimeoutSec 60

  $downloadHash = Get-FileSha256 -Path $downloadPath
  if ($payload.sha256 -and $downloadHash -ne $payload.sha256.ToLowerInvariant()) {
    Remove-Item -LiteralPath $downloadPath -Force -ErrorAction SilentlyContinue
    throw "Hash do wallpaper nao confere. Esperado $($payload.sha256), recebido $downloadHash."
  }

  Move-Item -LiteralPath $downloadPath -Destination $WallpaperPath -Force
  Set-WindowsWallpaper -Path $WallpaperPath

  Write-JsonFile -Path $StatePath -Value ([pscustomobject]@{
    version = $payload.version
    sha256 = $payload.sha256
    applied_at = (Get-Date).ToString("o")
  })

  Invoke-RestMethod -Method Post -Uri "$($Config.server_url)/agent/wallpaper/applied" -Headers $headers -Body @{ version = $payload.version } -TimeoutSec 15 | Out-Null
}

$config = Get-AgentConfig

do {
  try {
    Invoke-AgentCycle -Config $config
  } catch {
    $message = "$(Get-Date -Format o) $($_.Exception.Message)"
    Add-Content -LiteralPath (Join-Path $ConfigDir "agent.log") -Value $message
  }

  if ($Once) { break }
  Start-Sleep -Seconds ([int]$config.interval_seconds)
} while ($true)
