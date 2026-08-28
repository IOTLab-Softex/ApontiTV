param(
  [Parameter(Mandatory = $true)]
  [string]$HostAddress,

  [int]$Port = 5555
)

$ErrorActionPreference = "Stop"

$adbCandidates = @(
  "C:\platform-tools\adb.exe",
  "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
  "adb"
)

$adb = $adbCandidates | Where-Object {
  if ($_ -match "[\\/]") {
    Test-Path $_
  } else {
    Get-Command $_ -ErrorAction SilentlyContinue
  }
} | Select-Object -First 1

if (-not $adb) {
  throw "ADB nao encontrado. Instale platform-tools ou ajuste o PATH."
}

$target = "$HostAddress`:$Port"
Write-Host "Testando TCP $target..."
$tcp = Test-NetConnection -ComputerName $HostAddress -Port $Port -InformationLevel Quiet
if (-not $tcp) {
  throw "A porta $Port nao respondeu em $HostAddress. Verifique VPN, firewall e ADB TCP/IP na TV."
}

Write-Host "Conectando ADB em $target..."
& $adb connect $target

Write-Host "Consultando estado..."
& $adb -s $target get-state

Write-Host ""
Write-Host "Se o estado for 'device', preencha no Aponti TV:"
Write-Host "  IP ADB/VPN da TV: $HostAddress"
Write-Host "  Porta ADB: $Port"
