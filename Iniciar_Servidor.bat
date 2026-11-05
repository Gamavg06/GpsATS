@echo off
chcp 65001 > nul
title ATS GPS & Telemetría - Servidor PC

echo ===================================================
echo     ATS GPS & TELEMETRÍA - SERVIDOR COMPANION
echo ===================================================
echo.
echo Iniciando servidor en C:\Telemetriagps\server ...
echo.

cd /d "%~dp0server"
dotnet run -c Release --no-launch-profile

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] No se pudo iniciar el servidor. Verifica que .NET 10 SDK este instalado.
    pause
)
