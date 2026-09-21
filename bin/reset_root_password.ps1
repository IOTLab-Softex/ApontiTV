$ErrorActionPreference = "Stop"

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$Ruby = "C:\Ruby33-x64\bin\ruby.exe"
$EnvironmentFile = Join-Path $ProjectRoot "config\windows_startup.env"
$Runner = Join-Path $PSScriptRoot "reset_root_password.rb"

function Write-Centered([string]$Text, [ConsoleColor]$Color = [ConsoleColor]::White) {
  $width = [Math]::Max(40, [Console]::WindowWidth)
  $padding = [Math]::Max(0, [Math]::Floor(($width - $Text.Length) / 2))
  Write-Host ((" " * $padding) + $Text) -ForegroundColor $Color
}

function Write-BoxLine([string]$Text = "", [ConsoleColor]$Color = [ConsoleColor]::Gray) {
  $contentWidth = 58
  $safeText = if ($Text.Length -gt $contentWidth) { $Text.Substring(0, $contentWidth) } else { $Text }
  Write-Host "  │ " -NoNewline -ForegroundColor DarkMagenta
  Write-Host $safeText.PadRight($contentWidth) -NoNewline -ForegroundColor $Color
  Write-Host " │" -ForegroundColor DarkMagenta
}

function ConvertTo-PlainText([Security.SecureString]$SecureValue) {
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
  try {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  }
  finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  }
}

function Import-ApontiEnvironment {
  $env:RAILS_ENV = "production"
  $env:RACK_ENV = "production"
  $env:RI_FORCE_PATH_FOR_DLL = "1"
  $env:RUBYLIB = Join-Path $ProjectRoot "ruby_overrides"
  $env:Path = ((@(
    "C:\Ruby33-x64\bin",
    "C:\Ruby33-x64\lib\ruby\3.3.0\x64-mingw-ucrt",
    "C:\Ruby33-x64\msys64\ucrt64\bin",
    "C:\Ruby33-x64\msys64\usr\bin"
  ) + $env:Path) -join ";")

  if (Test-Path -LiteralPath $EnvironmentFile) {
    Get-Content -LiteralPath $EnvironmentFile | ForEach-Object {
      if ($_ -match "^\s*([^#][^=]*)=(.*)$") {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2], "Process")
      }
    }
  }
}

Clear-Host
Write-Host ""
Write-Centered "APONTI TV" Magenta
Write-Centered "Redefinição da senha do usuário root" White
Write-Host ""
Write-Host "  ┌──────────────────────────────────────────────────────────────┐" -ForegroundColor DarkMagenta
Write-BoxLine "Este assistente altera somente o root local do Aponti TV." Gray
Write-BoxLine "A senha do Andar360 não será modificada." DarkGray
Write-Host "  └──────────────────────────────────────────────────────────────┘" -ForegroundColor DarkMagenta
Write-Host ""

try {
  Import-ApontiEnvironment

  if (-not (Test-Path -LiteralPath $Ruby)) {
    throw "Ruby não encontrado em $Ruby"
  }

  $rootEmail = if ($env:ROOT_USER_EMAIL) { $env:ROOT_USER_EMAIL } else { "root@apontitv.local" }
  Write-Host "  Usuário: " -NoNewline -ForegroundColor DarkGray
  Write-Host $rootEmail -ForegroundColor Cyan
  Write-Host "  A senha precisa ter pelo menos 8 caracteres." -ForegroundColor DarkGray
  Write-Host ""

  $firstSecure = Read-Host "  Nova senha" -AsSecureString
  $secondSecure = Read-Host "  Confirme a nova senha" -AsSecureString
  $firstPassword = ConvertTo-PlainText $firstSecure
  $secondPassword = ConvertTo-PlainText $secondSecure

  if ($firstPassword.Length -lt 8) {
    throw "A nova senha precisa ter pelo menos 8 caracteres."
  }
  if ($firstPassword -cne $secondPassword) {
    throw "As senhas digitadas não são iguais."
  }

  Write-Host ""
  $confirmation = Read-Host "  Confirmar alteração? (S/N)"
  if ($confirmation.Trim().ToUpperInvariant() -ne "S") {
    Write-Host ""
    Write-Host "  Operação cancelada. Nenhuma alteração foi realizada." -ForegroundColor Yellow
    exit 0
  }

  Write-Host ""
  Write-Host "  ● Salvando nova senha..." -ForegroundColor Cyan
  $env:APONTI_ROOT_NEW_PASSWORD = $firstPassword

  Push-Location $ProjectRoot
  try {
    # Windows PowerShell converts any native STDERR output into an error when
    # ErrorActionPreference is Stop. Ruby emits harmless dependency warnings
    # on STDERR, so capture the process result before deciding if it failed.
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
      $output = @(& $Ruby "bin\rails" runner $Runner 2>&1)
      $runnerExitCode = $LASTEXITCODE
    }
    finally {
      $ErrorActionPreference = $previousErrorPreference
    }

    if ($runnerExitCode -ne 0) {
      $usefulOutput = $output | ForEach-Object { $_.ToString() } | Where-Object {
        $_ -notmatch "mutex_m was loaded from the standard library" -and
        $_ -notmatch "will no longer be part of the default gems" -and
        $_ -notmatch "You can add mutex_m to your Gemfile"
      }
      $errorMessage = ($usefulOutput -join [Environment]::NewLine).Trim()
      if ([string]::IsNullOrWhiteSpace($errorMessage)) {
        $errorMessage = "O Rails não conseguiu alterar a senha (código $runnerExitCode)."
      }
      throw $errorMessage
    }
  }
  finally {
    Pop-Location
  }

  Write-Host ""
  Write-Host "  ┌──────────────────────────────────────────────────────────────┐" -ForegroundColor Green
  Write-BoxLine "Senha alterada com sucesso!" Green
  Write-BoxLine "Entre usando root ou $rootEmail" White
  Write-Host "  └──────────────────────────────────────────────────────────────┘" -ForegroundColor Green
}
catch {
  Write-Host ""
  Write-Host "  ERRO: $($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
finally {
  Remove-Item Env:APONTI_ROOT_NEW_PASSWORD -ErrorAction SilentlyContinue
  $firstPassword = $null
  $secondPassword = $null
}

Write-Host ""
Write-Host "  Pressione Enter para fechar." -ForegroundColor DarkGray
[void](Read-Host)
