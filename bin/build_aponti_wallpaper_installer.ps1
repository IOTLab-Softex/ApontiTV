param(
  [string]$OutputPath
)

$ErrorActionPreference = "Stop"

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $csc)) {
  $csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}

if (-not (Test-Path -LiteralPath $csc)) {
  throw "csc.exe nao encontrado. Instale .NET Framework Developer Pack ou Visual Studio Build Tools."
}

$source = Join-Path $PSScriptRoot "ApontiWallpaperInstaller.cs"
$output = if ($OutputPath) {
  if ([System.IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path $PSScriptRoot $OutputPath }
} else {
  Join-Path $PSScriptRoot "ApontiWallpaperInstaller.exe"
}

& $csc /target:winexe /platform:anycpu /out:$output /reference:System.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll $source

if ($LASTEXITCODE -ne 0) {
  throw "Falha ao compilar o instalador."
}

Write-Host "Instalador gerado em: $output"
