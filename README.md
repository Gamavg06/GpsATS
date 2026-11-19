# SGNIA - ATS Telemetry & GPS Companion System

[![Version: v1.3.8](https://img.shields.io/badge/Version-v1.3.8%20(Build%2013)-00E5FF.svg)](CHANGELOG.md)
[![Server License: GPL-2.0](https://img.shields.io/badge/Server%20License-GPL--2.0-blue.svg)](LICENSE)
[![Android App License: MIT](https://img.shields.io/badge/App%20License-MIT-green.svg)](android/LICENSE)
[![.NET 10](https://img.shields.io/badge/.NET-10.0-512BD4.svg)](https://dotnet.microsoft.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20Canvas-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![SCS SDK](https://img.shields.io/badge/SCS%20Telemetry%20SDK-v1.14%2B-FF6F00.svg)](https://modding.scssoft.com/wiki/Documentation/Engine/SDK/Telemetry)

Sistema integral de telemetria en cabina, navegacion por satelite (GPS) en tiempo real y panel de instrumentos complementario para **American Truck Simulator** (y compatible con Euro Truck Simulator 2). Desarrollado desde cero bajo arquitectura clean-room, con renderizado por hardware de baja latencia y comunicacion directa a 30 Hz por USB (ADB Reverse) o Wi-Fi local.

---

## Galeria Visual (v1.3.8 - Figma Dark OLED)

| Vista 3D en Autopista | Vista 2D Trazo Continuo (Oakland) | Alerta de Sentido Contrario | Tablero de Telemetria | Informacion y Licencias |
| :---: | :---: | :---: | :---: | :---: |
| ![Vista 3D en Autopista](docs/screenshots/vista_3d_autopista.png) | ![Vista 2D en Oakland](docs/screenshots/vista_2d_trazo_continuo.png) | ![Alerta de Sentido Contrario](docs/screenshots/alerta_sentido_contrario.png) | ![Tablero de Telemetria](docs/screenshots/tablero_telemetria.png) | ![Informacion y Licencias](docs/screenshots/informacion_licencias.png) |
| Proyeccion 3D con recorte de plano cercano, suavizado Chaikin, cinta cian de 4 capas y guia de carriles | Renderizado 2D en 2 pases globales sin costuras internas negras ni micro-conectores de prefab | Deteccion en vivo de sentido contrario, alerta visual/TTS y recalculo A* con momento hacia adelante | Velocimetro digital, limite de velocidad, marcha, RPM, 5 canales de desgaste y autonomia | Licencias del proyecto y avisos de componentes de terceros en scroll vertical unico |

---

## Caracteristicas Principales

### Motor Cartografico Vectorial y Map-Matching
- **Generacion Local On-Demand (`--build-map`):** La base de datos cartografica (`map_ats.bin`, formato `ATSMAP03`) se extrae directamente de los archivos `.scs` instalados en la PC del usuario. No se distribuyen activos propietarios del juego.
- **Cobertura Completa:** Escaneo dinamico via HashFS de `base_map*.scs` y `dlc_*.scs` (147,268 nodos, 653,410 aristas, 168 ciudades, 971 empresas y 2,103 POIs).
- **Map-Matching (`snapToRoad`) y Anclaje Vial:** Proyeccion ortogonal con alineacion por rumbo (`headingDeg`) que penaliza carriles de sentido opuesto en autopistas divididas (`4.5x` cuando `dot < -0.35`) para mantener el cursor centrado sobre la calzada real incluso en curvas pronunciadas y salidas de intercambiador.
- **Insignias de POIs Limpias:** Clasificacion tipografica de alto contraste sobre el mapa (`GAS`, `RST`, `SRV`, `DLR`, `LOG`, `WGH`).

### Ruteo A* Estilo ATS, Recalculo Dinamico y Sentido Contrario
- **Costo por Tiempo, Jerarquia y Momento Inicial:** El algoritmo `AStarRouter` pondera la velocidad limite (`105 / speed`), jerarquia de via (autopistas, carreteras principales, calles locales y rampas), semaforos, giros en U (`cosTurn < -0.60`), cruces sinteticos de mediana y momento hacia adelante desde el nodo de origen.
- **Recalculo Automatico Fuera de Ruta:** `evaluateRouteTracking()` evalua la distancia ortogonal y alineacion respecto a la ruta activa en cada frame a 30 Hz. Al confirmar desvio (`> 48 m` o `>= 4` frames fuera de ruta), lanza un recalculo asincrono en `Dispatchers.Default` sin cancelar busquedas activas en cada tick de posicion.
- **Deteccion y Alerta de Sentido Contrario:** Cuando el vehiculo avanza (`speedKmh > 8` y `gear > 0`) contra el flujo dirigido de la via (`dot < -0.45`) o en sentido opuesto a la ruta activa (`dot < -0.60`), activa el banner de alerta roja (`ALERTA: SENTIDO CONTRARIO - RECALCULANDO RUTA`), aviso por voz TTS (`Precaucion, sentido contrario`) y recalculo hacia la siguiente salida o retorno legal.

### Renderizador Dual 2D / 3D (Canvas Compose)
- **Vista 3D en Perspectiva:** Cielo crepuscular, resplandor en horizonte (`horizonY = 0.26 * h`), recorte estricto bajo horizonte (`clipRect`), recorte geometrico de segmentos en plano cercano (`projectSegment3D` con `minDepth = 8f`), subdivision de primer plano con suavizado Chaikin, 3 pases globales de carreteras (bordes, asfalto y divisores de carril) y 4 pases globales para la ruta cian iniciando exactamente bajo el cursor del camion.
- **Vista 2D Tactica:** Filtrado de micro-conectores internos de prefab (`roadType == 3 && length < 18f` fuera de ruta), encadenamiento topologico sin plegado inverso (`chainEdges` con rechazo de aristas opuestas `A->B->A`), suavizado Chaikin y 2 pases globales (todos los bordes primero, todos los rellenos unificados encima).
- **Controles e Interpolacion 60-120 Hz:** Interpolacion continua entre frames de telemetria, zoom dinamico por velocidad en 3D, selector `3D / 2D`, zoom tactil (`0.10x` a `4.5x`), desplazamiento libre y botones `CTR` / `CFG`.

### Tablero OLED Figma Dark y Lanzador de Escritorio
- **Tablero de Telemetria (`DashboardScreen`):** Resumen de entrega activa, velocimetro digital, limite de velocidad, marcha (`D1-D18`, `R`, `N`), RPM, 5 canales de desgaste oficial SCS (Neumaticos, Transmision, Motor, Chasis, Cabina), combustible y autonomia con acceso rapido a parada cercana.
- **Lanzador WPF (`AtsGpsLauncher` / `Iniciar_Servidor.exe`):** Panel de escritorio `.NET 10` sin ventana negra de consola con estado HTTP/WS, PIN de emparejamiento, enlace automatico USB `adb reverse tcp:35500 tcp:35500`, instalador de APK en 1 clic y visor de logs UTF-8 en vivo.

---

## Arquitectura del Sistema

```mermaid
flowchart TD
    subgraph PC["Entorno Windows (PC de Simulacion)"]
        ATS["American Truck Simulator (64-bit Direct3D)"]
        SDK["scs-telemetry.dll (Plugin SCS SDK)"]
        MMF[("Local\\SCSTelemetry (Shared Memory 0 ms)")]
        Launcher["Iniciar_Servidor.exe (WPF Control Panel)"]
        Server["AtsGpsServer.exe (.NET 10 Host - GPL-2.0)"]
        MapExtractor["Extractor Binario (AtsGpsServer --build-map)"]

        ATS -->|Hooks de Telemetria| SDK
        SDK -->|Escritura MMF| MMF
        MMF -->|Lectura 30 Hz| Server
        Launcher -->|Supervisa Proceso y ADB| Server
        ATS -.->|Lectura DLCs .scs| MapExtractor
        MapExtractor -->|Genera map_ats.bin local| Server
    end

    subgraph Transport["Transporte Local (USB ADB Reverse o LAN RFC 1918)"]
        WS["WebSocket ws:// autenticado con PIN (Puerto 35500 / 30 Hz)"]
    end

    subgraph Mobile["Dispositivo Android (Kotlin + Jetpack Compose - MIT)"]
        Client["AtsGpsApp v1.3.8"]
        Router["AStarRouter + MapGraph (Snap / Off-Route / Wrong-Way)"]
        Canvas["GpsMapCanvas (2D 2-Pass / 3D 4-Pass)"]
        HUD["DashboardScreen + TurnByTurnCard + TTS"]

        Client --> Router
        Router --> Canvas
        Client --> HUD
    end

    Server -->|JSON Telemetry Stream| WS
    WS -->|30 Hz Updates| Client
```

---

## Guia de Instalacion y Uso

### 1. Requisitos Previos
- **PC (Windows 10/11 x64):** [.NET 10.0 Runtime o SDK](https://dotnet.microsoft.com/download/dotnet/10.0), *American Truck Simulator* y el plugin `scs-telemetry.dll` ubicado en `bin\win_x64\plugins\scs-telemetry.dll`.
- **Android (8.0+):** Dispositivo conectado por cable USB (Depuracion USB activada) o en la misma red Wi-Fi privada (RFC 1918).

### 2. Generacion Local del Mapa (`--build-map`)
```powershell
dotnet run --project server -- --build-map
```
Genera `server/data/map_ats.bin` directamente desde los archivos `.scs` locales del juego.

### 3. Ejecucion del Servidor y Lanzador PC
Ejecuta `Iniciar_Servidor.exe` (o `Iniciar_Servidor.bat`). El lanzador inicia `AtsGpsServer.exe` en puerto `35500`, configura `adb reverse tcp:35500 tcp:35500` automaticamente al detectar cable USB y muestra el codigo PIN de 6 digitos.

### 4. Instalacion de la App Android
1. Instala `apks/release.apk` (o usa el boton **Instalar APK en Celular** en `Iniciar_Servidor.exe`).
2. Por cable USB la app detecta `127.0.0.1:35500` automaticamente; por Wi-Fi introduce la IP LAN de la PC y el PIN de 6 digitos.

---

## Estructura del Repositorio

```text
GpsATS/
├── Iniciar_Servidor.exe            # Lanzador grafico WPF publicado (.NET 10)
├── Iniciar_Servidor.bat            # Script de arranque CLI y puente ADB
├── CHANGELOG.md                    # Historial detallado de versiones
├── LICENSE                         # Licencia GNU GPL-2.0 (Servidor)
├── LICENSES.md                     # Registro de licencias y terceros
├── README.md                       # Documentacion principal
├── docs/
│   └── screenshots/                # Capturas oficiales de v1.3.8 (1080x2200)
├── tools/
│   └── AtsGpsLauncher/             # Codigo fuente del panel de control WPF (.NET 10)
├── server/                         # Servidor .NET 10 (GPL-2.0)
│   ├── Program.cs                  # Host Kestrel, WebSocket y CLI --build-map
│   ├── Hubs/                       # TelemetryBroadcaster (30 Hz)
│   ├── Map/                        # MapExtractor, MapDataModels, MapBinarySerializer
│   ├── Security/                   # AuthManager (PIN) e IpWhitelistMiddleware (RFC 1918)
│   └── Telemetry/                  # ScsTelemetryReader (MMF) y TelemetrySnapshot
├── android/                        # Aplicacion Android nativa (MIT License)
│   └── app/src/main/java/com/ats/gps/
│       ├── MainActivity.kt         # Loop 30 Hz, recalculo dinamico y alerta wrong-way
│       ├── data/
│       │   ├── map/                # AStarRouter.kt, BinaryMapReader.kt
│       │   ├── model/              # MapGraph.kt (snapToRoad, evaluateRouteTracking)
│       │   └── network/            # AtsNetworkClient.kt
│       └── ui/
│           ├── map/GpsMapCanvas.kt # Renderizador 2D/3D por pases globales
│           ├── navigation/         # TurnByTurnCard.kt
│           ├── dashboard/          # DashboardScreen.kt
│           ├── diagnostic/         # TelemetryDiagnosticScreen.kt
│           ├── junction/           # JunctionView.kt
│           ├── tts/                # NavigationVoiceAssistant.kt
│           └── about/              # AboutScreen.kt
└── tests/
    └── AtsGpsServer.Tests/         # Pruebas xUnit (.NET 10)
```

---

## Compilacion desde el Codigo Fuente

### Servidor y Lanzador (.NET 10)
```powershell
dotnet build server/AtsGpsServer.csproj -c Release
dotnet publish tools/AtsGpsLauncher/AtsGpsLauncher.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o .
```

### App Android (Gradle)
```powershell
cd android
./gradlew assembleRelease
```

---

## Licencias y Aspectos Legales
- **Servidor PC (`server/`):** Licenciado bajo **GNU General Public License v2.0 (GPL-2.0)**. Ver [LICENSE](LICENSE).
- **Aplicacion Android (`android/`):** Licenciada bajo **MIT License**. Ver [android/LICENSE](android/LICENSE).
- **Aviso de Marcas:** *American Truck Simulator* y *Euro Truck Simulator 2* son marcas registradas de **SCS Software s.r.o.**. Proyecto independiente de codigo abierto sin afiliacion comercial con SCS Software.
