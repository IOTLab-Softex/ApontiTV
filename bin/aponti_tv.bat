@echo off
setlocal EnableDelayedExpansion

set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%" >nul

set "APP_NAME=Aponti TV"
set "RUBY_ROOT=C:\Ruby33-x64"
set "NGINX_ROOT=C:\nginx"
set "RUBY_BIN=%RUBY_ROOT%\bin"
set "RUBY_DLL=%RUBY_ROOT%\lib\ruby\3.3.0\x64-mingw-ucrt"
set "MSYS_UCRT=%RUBY_ROOT%\msys64\ucrt64\bin"
set "MSYS_USR=%RUBY_ROOT%\msys64\usr\bin"
set "FFMPEG_BIN=%NGINX_ROOT%\ffmpeg\bin"
set "ENV_FILE=config\windows_startup.env"
set "STARTUP_NAME=ApontiTVStartup.vbs"
set "STARTUP_FOLDER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "STARTUP_FILE=%STARTUP_FOLDER%\%STARTUP_NAME%"
set "STARTUP_CMD_FILE=%STARTUP_FOLDER%\ApontiTVStartup.cmd"
set "WIN_FIND=%SystemRoot%\System32\find.exe"
set "WIN_TIMEOUT=%SystemRoot%\System32\timeout.exe"

set "RI_FORCE_PATH_FOR_DLL=1"
set "RUBYLIB=%CD%\ruby_overrides"
set "RAILS_ENV=production"
set "RACK_ENV=production"
set "RAILS_SERVE_STATIC_FILES=true"
set "PATH=%RUBY_BIN%;%RUBY_DLL%;%MSYS_UCRT%;%MSYS_USR%;%NGINX_ROOT%;%FFMPEG_BIN%;%PATH%"

if not exist "log" mkdir "log"
if not exist "tmp\pids" mkdir "tmp\pids"
if not exist "%NGINX_ROOT%\temp\hls" mkdir "%NGINX_ROOT%\temp\hls"

call :load_secret

if /I "%~1"=="auto" (
  call :iniciar oculto
  popd >nul
  exit /b
)

if /I "%~1"=="install" (
  call :ativar_auto_startup
  popd >nul
  exit /b
)

if /I "%~1"=="uninstall" (
  call :desativar_auto_startup
  popd >nul
  exit /b
)

if /I "%~1"=="stop" (
  call :encerrar
  popd >nul
  exit /b
)

:menu
cls
echo ==============================
echo        MENU APONTI TV
echo ==============================
echo [1] Iniciar Aponti TV oculto
echo [2] Iniciar Aponti TV visivel
echo [3] Exibir Rails no terminal
echo [4] Encerrar Aponti TV
echo [5] Ativar inicializacao automatica no Windows
echo [6] Desativar inicializacao automatica no Windows
echo [7] Precompilar assets
echo [8] Sair
echo ==============================
set /p choice="Digite a opcao desejada: "

if "%choice%"=="1" (
  call :iniciar oculto
  pause
  goto menu
)

if "%choice%"=="2" (
  call :iniciar visivel
  pause
  goto menu
)

if "%choice%"=="3" (
  call :encerrar_rails
  echo Iniciando Rails no terminal...
  ruby bin\rails s -b 0.0.0.0 -e production
  pause
  goto menu
)

if "%choice%"=="4" (
  call :encerrar
  pause
  goto menu
)

if "%choice%"=="5" (
  call :ativar_auto_startup
  pause
  goto menu
)

if "%choice%"=="6" (
  call :desativar_auto_startup
  pause
  goto menu
)

if "%choice%"=="7" (
  call :precompile_assets
  pause
  goto menu
)

if "%choice%"=="8" (
  popd >nul
  exit /b
)

goto menu

:load_secret
if exist "%ENV_FILE%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    if not "%%A"=="" if not "%%B"=="" set "%%A=%%B"
  )
)

if not defined SECRET_KEY_BASE (
  echo Criando SECRET_KEY_BASE fixa...
  for /f "usebackq delims=" %%S in (`"%RUBY_BIN%\ruby.exe" -e "require 'securerandom'; puts SecureRandom.hex(64)"`) do set "SECRET_KEY_BASE=%%S"
  if exist "%ENV_FILE%" (
    >> "%ENV_FILE%" echo SECRET_KEY_BASE=!SECRET_KEY_BASE!
  ) else (
    > "%ENV_FILE%" echo SECRET_KEY_BASE=!SECRET_KEY_BASE!
  )
)
goto :eof

:log
echo [%date% %time%] %~1>> "log\windows_startup.log"
goto :eof

:precompile_assets
echo ==============================
echo Precompilando assets em production...
call :log "Precompilando assets"
bundle exec rails assets:precompile
goto :eof

:iniciar
set "modo=%~1"
echo ==============================
echo Iniciando %APP_NAME%...
call :log "Inicializando Aponti TV via BAT"

call :iniciar_nginx
call :iniciar_rails %modo%
call :status_final
goto :eof

:iniciar_nginx
echo ==============================
echo Verificando nginx...
if not exist "%NGINX_ROOT%\nginx.exe" (
  echo nginx.exe nao encontrado em %NGINX_ROOT%.
  call :log "nginx.exe nao encontrado"
  goto :eof
)

netstat -ano | "%WIN_FIND%" ":80" | "%WIN_FIND%" "LISTENING" >nul
if !errorlevel! equ 0 (
  echo nginx ja esta ouvindo na porta 80.
  call :log "Nginx ja esta ouvindo na porta 80"
  goto :eof
)

start "" /D "%NGINX_ROOT%" "%NGINX_ROOT%\nginx.exe"
"%WIN_TIMEOUT%" /t 2 >nul
echo nginx iniciado.
call :log "Nginx iniciado"
goto :eof

:iniciar_rails
set "modo=%~1"
echo ==============================
echo Verificando Rails na porta 3000...

netstat -ano | "%WIN_FIND%" ":3000" | "%WIN_FIND%" "LISTENING" >nul
if !errorlevel! equ 0 (
  echo Rails ja esta ouvindo na porta 3000.
  call :log "Rails ja esta ouvindo na porta 3000"
  goto :eof
)

del "tmp\pids\server.pid" >nul 2>&1

echo Iniciando Rails server...
if /I "%modo%"=="visivel" (
  start "Rails Server - Aponti TV" cmd /k "cd /d ^"%CD%^" && ruby bin\rails s -b 0.0.0.0 -e production"
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath 'ruby' -ArgumentList 'bin\rails','s','-b','0.0.0.0','-e','production' -WorkingDirectory '%CD%' -WindowStyle Hidden -RedirectStandardOutput 'log\rails_startup.out.log' -RedirectStandardError 'log\rails_startup.err.log'"
)

set /a retries=0
:wait_rails
netstat -ano | "%WIN_FIND%" ":3000" | "%WIN_FIND%" "LISTENING" >nul
if !errorlevel! equ 0 (
  echo Rails server ouvindo na porta 3000.
  call :log "Rails iniciado na porta 3000"
  goto :eof
)

set /a retries+=1
if !retries! leq 15 (
  echo Aguardando Rails abrir porta 3000... !retries!/15
  "%WIN_TIMEOUT%" /t 2 >nul
  goto wait_rails
)

echo Rails nao abriu a porta 3000. Veja log\rails_startup.err.log
call :log "Rails nao abriu a porta 3000"
goto :eof

:status_final
echo ==============================
echo Status final:
netstat -ano | "%WIN_FIND%" ":80" | "%WIN_FIND%" "LISTENING" >nul
if !errorlevel! equ 0 (echo OK nginx porta 80) else (echo FALHA nginx porta 80)

netstat -ano | "%WIN_FIND%" ":3000" | "%WIN_FIND%" "LISTENING" >nul
if !errorlevel! equ 0 (echo OK Rails porta 3000) else (echo FALHA Rails porta 3000)
echo ==============================
goto :eof

:encerrar
echo ==============================
echo Encerrando %APP_NAME%...
call :encerrar_rails
call :encerrar_nginx
echo Servicos encerrados.
call :log "Servicos encerrados via BAT"
goto :eof

:encerrar_rails
echo Encerrando Rails na porta 3000...
for /f "tokens=5" %%P in ('netstat -ano ^| "%WIN_FIND%" ":3000" ^| "%WIN_FIND%" "LISTENING"') do (
  taskkill /PID %%P /F >nul 2>&1
  echo Rails PID %%P encerrado.
)
del "tmp\pids\server.pid" >nul 2>&1
goto :eof

:encerrar_nginx
echo Encerrando nginx...
if exist "%NGINX_ROOT%\nginx.exe" (
  "%NGINX_ROOT%\nginx.exe" -s quit >nul 2>&1
)
"%WIN_TIMEOUT%" /t 1 >nul
taskkill /IM nginx.exe /F >nul 2>&1
goto :eof

:ativar_auto_startup
echo ==============================
echo Criando inicializacao automatica no Windows...
if not exist "%STARTUP_FOLDER%" mkdir "%STARTUP_FOLDER%"
if exist "%STARTUP_CMD_FILE%" del "%STARTUP_CMD_FILE%" >nul 2>&1
> "%STARTUP_FILE%" echo Set WshShell = CreateObject("WScript.Shell")
>> "%STARTUP_FILE%" echo WshShell.Run "cmd.exe /c ""%~f0"" auto", 0, False

if exist "%STARTUP_FILE%" (
  echo Inicializacao automatica configurada:
  echo %STARTUP_FILE%
) else (
  echo Falha ao criar inicializacao automatica.
)
goto :eof

:desativar_auto_startup
echo ==============================
echo Removendo inicializacao automatica...
if exist "%STARTUP_FILE%" (
  del "%STARTUP_FILE%"
  echo Inicializacao automatica removida.
) else (
  echo Nenhum arquivo de inicializacao encontrado.
)
if exist "%STARTUP_CMD_FILE%" del "%STARTUP_CMD_FILE%" >nul 2>&1
goto :eof
