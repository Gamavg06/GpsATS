@echo off
chcp 65001 > nul
title ATS GPS & Telemetría - Servidor PC (.NET 10)

echo ================================================================================
echo           ATS GPS & TELEMETRÍA - SERVIDOR COMPANION (.NET 10)
echo ================================================================================
echo.

cd /d "%~dp0"

:: 1. Verificación de presencia de .NET SDK / Runtime
where dotnet >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] No se encontró el comando 'dotnet' en el PATH del sistema.
    echo         Por favor, instala .NET 10 Runtime o SDK desde:
    echo         https://dotnet.microsoft.com/download/dotnet/10.0
    echo.
    pause
    exit /b 1
)

:: 2. Detección Dinámica de la Instalación de ATS mediante detect-ats.ps1
set "ATS_DIR="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\detect-ats.ps1"`) do (
    set "ATS_DIR=%%I"
)

if "%ATS_DIR%"=="" (
    set "ATS_DIR=C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator"
)

echo [DETECCIÓN] Carpeta del juego ATS:
echo             "%ATS_DIR%"
echo.

:: 3. Verificación del plugin SCS Telemetry
set "ATS_PLUGIN=%ATS_DIR%\bin\win_x64\plugins\scs-telemetry.dll"
if not exist "%ATS_PLUGIN%" (
    echo [AVISO] No se encontró 'scs-telemetry.dll' en la carpeta de ATS:
    echo         "%ATS_PLUGIN%"
    echo.
    echo         Copia 'server\plugins\scs-telemetry.dll' a dicha ruta para habilitar telemetría.
    echo.
) else (
    echo [OK] Plugin scs-telemetry.dll detectado en la carpeta de 64 bits de ATS.
)

:: 4. Verificación de necesidad de recompilación
set "NEED_BUILD=0"
if not exist "server\publish\AtsGpsServer.dll" (
    set "NEED_BUILD=1"
) else (
    for /f "usebackq" %%B in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$pubTime = (Get-Item 'server\publish\AtsGpsServer.dll').LastWriteTime; " ^
        "$srcNewer = Get-ChildItem -Recurse 'server' -Include *.cs,*.csproj | Where-Object { $_.LastWriteTime -gt $pubTime }; " ^
        "if ($srcNewer) { Write-Output '1' } else { Write-Output '0' }"`) do (
        set "NEED_BUILD=%%B"
    )
)

if "%NEED_BUILD%"=="1" (
    echo [COMPILACIÓN] Código fuente actualizado. Publicando servidor en server\publish...
    dotnet publish -c Release -o "server\publish" "server\AtsGpsServer.csproj"
    if errorlevel 1 (
        echo [ERROR] No se pudo compilar el servidor.
        pause
        exit /b 1
    )
    echo [OK] Publicación generada correctamente.
    echo.
)

:: 5. Verificación de existencia del mapa local binario
if not exist "server\data\map_ats.bin" (
    echo.
    echo [AVISO] No se encontró 'server\data\map_ats.bin'.
    echo         Iniciando extracción local on-demand...
    echo.
    dotnet "server\publish\AtsGpsServer.dll" --build-map
    if errorlevel 1 (
        echo.
        echo [ERROR] No se pudo generar el mapa.
        pause
        exit /b 1
    )
    echo [OK] Mapa generado exitosamente.
    echo.
)

:: 6. Verificación de Permisos de Administrador y Configuración de Firewall
net session >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [FIREWALL] Permisos de Administrador detectados. Verificando regla del puerto 35500...
    netsh advfirewall firewall show rule name="ATS GPS Server" >nul 2>&1
    if %ERRORLEVEL% NEQ 0 (
        netsh advfirewall firewall add rule name="ATS GPS Server" dir=in action=allow protocol=TCP localport=35500 profile=private remoteip=LocalSubnet >nul 2>&1
        echo [FIREWALL] Regla 'ATS GPS Server' agregada exitosamente para puerto 35500 TCP (profile=private, remoteip=LocalSubnet).
    ) else (
        echo [FIREWALL] Regla 'ATS GPS Server' ya configurada en el Firewall de Windows.
    )
) else (
    echo [AVISO] Ejecutando sin privilegios de Administrador. No se pudo verificar ni crear la regla en el Firewall de Windows.
    echo         Si la app Android no logra conectar en la red local, cierra esta ventana y ejecuta 'Iniciar_Servidor.bat' como Administrador
    echo         para habilitar el puerto 35500 TCP (profile=private, remoteip=LocalSubnet).
)
echo.

:: 7. Ejecución del Servidor con .NET 10 Runtime
echo [INICIO] Ejecutando servidor companion en puerto 35500...
dotnet "server\publish\AtsGpsServer.dll"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] El servidor terminó con error.
    pause
)
