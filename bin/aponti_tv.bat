@echo off
setlocal
set "SCRIPT=%~dp0aponti_tv.ps1"
set "APP=%~dp0server_manager\ApontiTV.ServerManager.exe"

if "%~1"=="" if exist "%APP%" (
  start "" "%APP%"
  exit /b 0
)

if /I "%~1"=="tray" if exist "%APP%" (
  start "" "%APP%" --tray
  exit /b 0
)

if not exist "%SCRIPT%" (
  echo Arquivo nao encontrado: %SCRIPT%
  pause
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "%SCRIPT%" %*
exit /b %errorlevel%
