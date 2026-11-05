@echo off
chcp 65001 > nul
title ATS GPS & Telemetría - Servidor PC (.NET 10)

echo ================================================================================
echo           ATS GPS & TELEMETRÍA - SERVIDOR COMPANION (.NET 10)
echo ================================================================================
echo.

cd /d "%~dp0"

:: 1. Verificación o compilación de la publicación del Servidor
if not exist "server\publish\AtsGpsServer.dll" (
    echo [COMPILACIÓN] Preparando binarios de publicación en server\publish...
    dotnet publish -c Release -o "server\publish" "server\AtsGpsServer.csproj"
    if errorlevel 1 (
        echo [ERROR] No se pudo compilar el servidor. Se requiere .NET 10 SDK para compilar por primera vez.
        pause
        exit /b 1
    )
)

:: 2. Verificación del plugin SCS Telemetry
set "ATS_PLUGIN=C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator\bin\win_x64\plugins\scs-telemetry.dll"
if not exist "%ATS_PLUGIN%" (
    echo [AVISO] No se encontró 'scs-telemetry.dll' en la carpeta oficial de ATS:
    echo         "%ATS_PLUGIN%"
    echo.
    echo         Copia el archivo 'server\plugins\scs-telemetry.dll' a esa ruta para que el
    echo         juego pueda enviar telemetría en tiempo real.
    echo.
) else (
    echo [OK] Plugin scs-telemetry.dll detectado en la carpeta de 64 bits de ATS.
)

:: 2. Verificación de existencia del mapa local
if not exist "server\data\map_ats.bin" (
    echo.
    echo [AVISO] No se encontró el archivo de red vial 'server\data\map_ats.bin'.
    echo         Iniciando extracción local on-demand desde los archivos del juego...
    echo.
    dotnet "server\publish\AtsGpsServer.dll" --build-map
    if errorlevel 1 (
        echo.
        echo [ERROR] No se pudo generar el mapa. Asegúrate de tener instalado ATS y .NET 10.
        pause
        exit /b 1
    )
    echo [OK] Mapa generado exitosamente.
    echo.
)

:: 3. Aviso de Firewall de Windows
echo [FIREWALL] Si la app móvil no conecta, abre el puerto 35500 TCP en Windows Firewall:
echo            powershell: New-NetFirewallRule -DisplayName "ATS GPS" -Direction Inbound -LocalPort 35500 -Protocol TCP -Action Allow
echo.

:: 4. Ejecución del Servidor con .NET 10 Runtime
echo [INICIO] Ejecutando servidor companion...
dotnet "server\publish\AtsGpsServer.dll"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] El servidor terminó con error. Verifica que el Runtime de .NET 10 esté instalado.
    pause
)
