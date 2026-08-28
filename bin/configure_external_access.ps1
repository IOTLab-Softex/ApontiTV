$ErrorActionPreference = "Stop"

$rules = @(
  @{ Name = "Aponti TV HTTP 80"; Port = 80; Description = "Acesso externo/local ao Aponti TV via nginx" },
  @{ Name = "Aponti TV Rails 3000"; Port = 3000; Description = "Acesso local direto ao Rails usado pelo app Android" }
)

foreach ($rule in $rules) {
  $existingRule = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue

  if ($existingRule) {
    Write-Host "Regra ja existe: $($rule.Name)"
    continue
  }

  New-NetFirewallRule `
    -DisplayName $rule.Name `
    -Description $rule.Description `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort $rule.Port | Out-Null

  Write-Host "Regra criada: $($rule.Name) porta $($rule.Port)"
}

$localIp = Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object {
    $_.IPAddress -notlike "127.*" -and
    $_.IPAddress -notlike "169.254.*" -and
    $_.PrefixOrigin -ne "WellKnown"
  } |
  Select-Object -First 1 -ExpandProperty IPAddress

Write-Host ""
Write-Host "Acesso local:"
Write-Host "  http://$localIp"
Write-Host "  http://$localIp`:3000"
Write-Host ""
Write-Host "Para acesso externo, configure no MikroTik/NAT:"
Write-Host "  WAN TCP 80  -> $localIp TCP 80"
Write-Host ""
Write-Host "Depois aponte o DNS apontitv.com para o IP publico da internet."
