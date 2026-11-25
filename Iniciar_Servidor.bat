@echo off
title ATS GPS - Servidor PC (.NET 10)

echo ================================================================================
echo           ATS GPS - SERVIDOR COMPANION (.NET 10)
echo ================================================================================
echo.

REM Establecer directorio de trabajo relativo al script
cd /d "%~dp0"

REM 1. Deteccion de la instalacion de ATS
set "ATS_DIR="
if exist "server\detect-ats.ps1" (
    for /f "usebackq delims=" %%I in (`powershell -NoProfile -ExecutionPolicy Bypass -File "server\detect-ats.ps1"`) do (
        set "ATS_DIR=%%I"
    )
)
if "%ATS_DIR%"=="" (
    set "ATS_DIR=C:\Program Files (x86)\Steam\steamapps\common\American Truck Simulator"
)

echo [DETECCION] Carpeta del juego ATS:
echo             "%ATS_DIR%"
echo.

REM 2. Verificacion del plugin SCS Telemetry
set "ATS_PLUGIN=%ATS_DIR%\bin\win_x64\plugins\scs-telemetry.dll"
if not exist "%ATS_PLUGIN%" (
    echo [AVISO] No se encontro 'scs-telemetry.dll' en la carpeta de ATS:
    echo         "%ATS_PLUGIN%"
    echo.
    echo         Copia 'server\plugins\scs-telemetry.dll' a dicha ruta para habilitar telemetria.
    echo.
) else (
    echo [OK] Plugin scs-telemetry.dll detectado en la carpeta de 64 bits de ATS.
)

REM 3. Preparacion del binario del servidor (priorizar AtsGpsServer.exe compilado)
set "SERVER_EXE=server\publish\AtsGpsServer.exe"
set "USE_DOTNET_RUN=0"

if exist "%SERVER_EXE%" (
    echo [OK] Binario precompilado detectado: "%SERVER_EXE%"
    echo.
) else (
    echo [INFO] No se encontro "%SERVER_EXE%". Verificando .NET SDK/CLI para compilar...
    where dotnet >nul 2>&1
    if %ERRORLEVEL% NEQ 0 (
        echo [ERROR] No se encontro "%SERVER_EXE%" ni el comando 'dotnet' en el PATH del sistema.
        echo         Instala .NET 10 Runtime/SDK o ejecuta desde un paquete compilado:
        echo         https://dotnet.microsoft.com/download/dotnet/10.0
        echo.
        pause
        exit /b 1
    )

    if not exist "server\AtsGpsServer.csproj" (
        echo [ERROR] No se encontro 'server\AtsGpsServer.csproj' ni '%SERVER_EXE%'.
        pause
        exit /b 1
    )

    echo [COMPILACION] Publicando servidor en server\publish...
    dotnet publish -c Release -o "server\publish" "server\AtsGpsServer.csproj"
    if errorlevel 1 (
        echo [AVISO] Fallo 'dotnet publish'. Se intentara ejecutar con 'dotnet run'.
        set "USE_DOTNET_RUN=1"
    ) else (
        echo [OK] Servidor compilado en server\publish.
    )
    echo.
)

REM 4. Configuracion automatica de cable USB (ADB reverse)
set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB_EXE%" (
    where adb >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        for /f "tokens=*" %%i in ('where adb') do set "ADB_EXE=%%i"
    )
)

if exist "%ADB_EXE%" (
    echo [USB CABLE] Configurando puente de alta velocidad por cable...
    "%ADB_EXE%" reverse tcp:35500 tcp:35500 >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        echo [OK] Cable USB activo y vinculado (adb reverse tcp:35500).
        echo      En la app movil selecciona 'Cable USB' (127.0.0.1) e ingresa el PIN de 6 digitos mostrado en consola.
    ) else (
        echo [AVISO CABLE USB]
        echo  Para que el cable funcione como red de datos de telemetria:
        echo  - En tu celular ve a Opciones de Desarrollador y activa 'Depuracion por USB'.
        echo  - O en la notificacion USB cambia de 'Transferencia de datos' a 'Anclaje USB' / 'Compartir conexion por USB'.
    )
    echo.
)

REM 5. Verificacion de mapa binario
set "HAS_MAP=0"
if exist "server\data\map_ats.bin" set "HAS_MAP=1"
if exist "server\publish\data\map_ats.bin" set "HAS_MAP=1"
if "%HAS_MAP%"=="0" (
    echo.
    echo [AVISO] No se encontro 'map_ats.bin' en server\data ni server\publish\data.
    echo         Iniciando extraccion local on-demand...
    echo.
    set "USE_PREBUILT=0"
    if "%USE_DOTNET_RUN%"=="0" if exist "%SERVER_EXE%" set "USE_PREBUILT=1"
    if "%USE_PREBUILT%"=="1" (
        "%SERVER_EXE%" --build-map
    ) else (
        dotnet run --project "server\AtsGpsServer.csproj" -- --build-map
    )
    if errorlevel 1 (
        echo.
        echo [ERROR] No se pudo generar el mapa.
        pause
        exit /b 1
    )
    echo [OK] Mapa generado exitosamente.
    echo.
)

REM 6. Verificacion de Firewall
net session >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [FIREWALL] Permisos de Administrador detectados. Verificando regla del puerto 35500...
    netsh advfirewall firewall show rule name="ATS GPS Server" >nul 2>&1
    if %ERRORLEVEL% NEQ 0 (
        netsh advfirewall firewall add rule name="ATS GPS Server" dir=in action=allow protocol=TCP localport=35500 profile=private,domain remoteip=LocalSubnet >nul 2>&1
        echo [FIREWALL] Regla 'ATS GPS Server' agregada exitosamente para puerto 35500 TCP.
    ) else (
        echo [FIREWALL] Regla 'ATS GPS Server' ya configurada en el Firewall de Windows.
    )
) else (
    echo [AVISO] Ejecutando sin privilegios de Administrador.
    echo         Si la app Android no conecta en la red local, ejecuta 'Iniciar_Servidor.bat' como Administrador
    echo         para habilitar el puerto 35500 TCP.
)
echo.

REM 7. Ejecucion del Servidor
echo [INICIO] Ejecutando servidor companion en puerto 35500...
echo Presiona Ctrl+C en esta ventana para detener el servidor.
echo.
set "USE_PREBUILT=0"
if "%USE_DOTNET_RUN%"=="0" if exist "%SERVER_EXE%" set "USE_PREBUILT=1"
if "%USE_PREBUILT%"=="1" (
    "%SERVER_EXE%"
) else (
    dotnet run --project "server\AtsGpsServer.csproj"
)

echo.
echo ================================================================================
echo El servidor se ha detenido.
echo ================================================================================
pause
