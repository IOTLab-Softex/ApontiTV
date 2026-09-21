@echo off
title Aponti TV - Redefinir senha do root
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0reset_root_password.ps1"
if errorlevel 1 pause
