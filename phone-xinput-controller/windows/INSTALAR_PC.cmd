@echo off
setlocal
cd /d "%~dp0"
echo ==============================================
echo  Xbox Phone Controller - Instalador do PC
echo ==============================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0INSTALAR_PC.ps1"
if errorlevel 1 (
  echo.
  echo A instalacao terminou com erro.
  pause
)
endlocal
