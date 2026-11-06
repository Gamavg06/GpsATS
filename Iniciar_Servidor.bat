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

:: 2. Detección Dinámica de la Instalación de American Truck Simulator (Steam Libraries)
set "ATS_DIR="
for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$paths = @('C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator'); " ^
    "$reg = (Get-ItemProperty -Path 'HKCU:\Software\Valve\Steam' -Name 'SteamPath' -ErrorAction SilentlyContinue).SteamPath; " ^
    "if ($reg -and (Test-Path $reg)) { " ^
    "    $vdf = Join-Path $reg 'steamapps\libraryfolders.vdf'; " ^
    "    if (Test-Path $vdf) { " ^
    "        Get-Content $vdf | Select-String '\"path\"\s+\"([^\"]+)\"' | ForEach-Object { " ^
    "            $p = $_.Matches[0].Groups[1].Value.Replace('\\\\', '\'); " ^
    "            $candidate = Join-Path $p 'steamapps\common\American Truck Simulator'; " ^
    "            if (Test-Path $candidate) { $paths += $candidate } " ^
    "        } " ^
    "    } " ^
    "} " ^
    "foreach ($p in $paths) { if (Test-Path (Join-Path $p 'bin\win_x64\amtrucks.exe')) { Write-Output $p; break } }"`) do (
    set "ATS_DIR=%%I"
)

if "%ATS_DIR%"=="" (
    set "ATS_DIR=C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator"
)

echo [DETECCIÓN] Carpeta del juego ATS:
echo             "%ATS_DIR%"
echo.

:: 3. Verificación del plugin SCS Telemetry (Licencia MIT de RenCloud)
set "ATS_PLUGIN=%ATS_DIR%\bin\win_x64\plugins\scs-telemetry.dll"
if not exist "%ATS_PLUGIN%" (
    echo [AVISO] No se encontró 'scs-telemetry.dll' en la carpeta oficial de ATS:
    echo         "%ATS_PLUGIN%"
    echo.
    echo         El plugin 'scs-telemetry.dll' está basado en SCS SDK y licenciado bajo MIT (RenCloud).
    echo         Repositorio oficial: https://github.com/RenCloud/scs-sdk-plugin
    echo         Copia 'server\plugins\scs-telemetry.dll' a dicha ruta para habilitar la telemetría.
    echo.
) else (
    echo [OK] Plugin scs-telemetry.dll (MIT RenCloud) detectado en la carpeta de 64 bits de ATS.
)

:: 4. Verificación de necesidad de recompilación (si los fuentes son más nuevos que la publicación)
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
    echo [COMPILACIÓN] Código fuente actualizado. Compilando publicación en server\publish...
    dotnet publish -c Release -o "server\publish" "server\AtsGpsServer.csproj"
    if errorlevel 1 (
        echo [ERROR] No se pudo compilar el servidor. Se requiere .NET 10 SDK para compilar.
        pause
        exit /b 1
    )
    echo [OK] Publicación generada correctamente.
    echo.
)

:: 5. Verificación de existencia del mapa local binario
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

:: 6. Aviso de Regla de Firewall de Windows
echo [FIREWALL] Puerto TCP 35500:
echo            Para permitir conexiones de la app en la subred local, ejecuta como Administrador:
echo            powershell -Command "New-NetFirewallRule -DisplayName 'ATS GPS Server' -Direction Inbound -LocalPort 35500 -Protocol TCP -Action Allow -Profile Private -RemoteAddress LocalSubnet"
echo.

:: 7. Ejecución del Servidor con .NET 10 Runtime
echo [INICIO] Ejecutando servidor companion en puerto 35500...
dotnet "server\publish\AtsGpsServer.dll"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] El servidor terminó con error. Verifica que el Runtime de .NET 10 esté instalado.
    pause
)
