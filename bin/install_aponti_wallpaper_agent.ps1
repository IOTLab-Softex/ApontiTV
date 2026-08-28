param(
  [Parameter(Mandatory = $true)]
  [string]$ServerUrl,

  [Parameter(Mandatory = $true)]
  [string]$Token,

  [int]$IntervalSeconds = 60
)

$ErrorActionPreference = "Stop"

$SourceAgent = Join-Path $PSScriptRoot "aponti_wallpaper_agent.ps1"
$InstallDir = Join-Path $env:ProgramData "ApontiTV\WallpaperAgent"
$InstalledAgent = Join-Path $InstallDir "aponti_wallpaper_agent.ps1"
$ConfigPath = Join-Path $InstallDir "agent.json"
$TaskName = "ApontiTV Wallpaper Agent"

if (-not (Test-Path -LiteralPath $SourceAgent)) {
  throw "Agente nao encontrado em $SourceAgent"
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -LiteralPath $SourceAgent -Destination $InstalledAgent -Force

$config = [pscustomobject]@{
  server_url = $ServerUrl.Trim().TrimEnd("/")
  token = $Token.Trim()
  interval_seconds = $IntervalSeconds
}

$config | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8

$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$InstalledAgent`""
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description "Recebe wallpapers do Aponti TV." -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host "Agente instalado e iniciado."
Write-Host "Configuracao: $ConfigPath"
