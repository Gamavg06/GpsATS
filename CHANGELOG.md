# CHANGELOG - ATS Telemetria GPS

Todas las modificaciones notables, correcciones de errores y nuevas funcionalidades introducidas en este proyecto se registran en este documento.

---

## [v1.4.1] - 2026-11-19
### Corregido y Ampliado
- **Normalizador Inteligente de Destinos en `MainActivity.kt`:**
  - Implementacion de algoritmo de coincidencia insensibilizada a espacios, guiones bajos y mayusculas (`norm(s)`), resolviendo emparejamiento exacto entre la telemetria (`"Little Rock"`, `"Las Vegas"`, `"San Francisco"`) y los identificadores binarios del mapa (`"Little_rock"`, `"Las_vegas"`, `"San_francisc"`).
  - Resolucion automatica de la ruta A*, despliegue inmediato de la cinta cian de navegacion, activacion de la tarjeta Turn-by-Turn y guia de voz hablada.
- **Cobertura Territorial Expandida al 100% de DLCs (763 Sectores):**
  - Regeneracion completa de `map_ats.bin` escaneando todos los paquetes instalados (`dlc_ar.scs`, `dlc_ok.scs`, `dlc_tx.scs`, etc.).
  - Base de datos ampliada a **278,723 nodos**, **1,275,420 aristas**, **243 ciudades** y **3,956 POIs** oficiales.
- **Correccion Visual de Calzadas 3D en `GpsMapCanvas.kt`:**
  - Supresion de `chainEdges` en vista 3D: cada arista vial proyecta estrictamente su propia polilinea vectorial nativa (`edge.polyline`), eliminando lineas diagonales espurias y triangulos cruzados en intersecciones.
- **Distribucion Oficial Unica:**
  - Android bumped to `versionCode = 16`, `versionName = "1.4.1"`.
  - Reempaquetado del instalador Setup PC `Instalador_AtsGps_v1.4.1_Setup.exe` con la base cartografica completa.

---

## [v1.4.0] - 2026-11-19
### Agregado y Optimizado (Rendimiento Extremo y Nuevas Funcionalidades)
- **Eliminacion Total de Lag y Stuttering en Renderizado 2D/3D:**
  - `GpsMapCanvas.kt`: Implementacion de `Viewport2DCache` y `Viewport3DCache` para pre-calcular y encadenar polilineas viales una sola vez al cambiar de cuadrante (> 25 m en 2D, > 15 m en 3D), eliminando llamadas repetitivas a `chainEdges` y `smoothPolyline` a 60-120 Hz.
  - Creacion de `RoadSegPool3D` y `RouteSegPool3D` con buffers estaticos pre-asignados y algoritmo QuickSort in-place de indices primitivos (`IntArray`), reduciendo a **0 bytes** la generacion de objetos en el heap durante el dibujo continuo.
  - Implementacion de `RouteRenderCache` con ventana de busqueda deslizante $O(1)$ para recorte y proyeccion de la ruta activa en tiempo real.
  - Snapping inteligente con umbral de desplazamiento (`SnapCache`): calculo de `snapToRoad` restringido a desplazamientos > 1.8 m o variaciones de rumbo > 8°.
  - Reutilizacion estatica de `Path` (`reusablePaths.reset()`) y pinturas `Paint`, suprimiendo la sobrecarga del recolector de basura (*Garbage Collector* de Android ART).
- **Asistente de Voz Paso a Paso Turn-by-Turn:**
  - `NavigationVoiceAssistant.kt` y `TurnByTurnCard.kt`: Anuncios de audio dinamicos mediante Text-to-Speech (TTS) en hitos de distancia (1000m, 500m, 200m, 50m) y anuncio de llegada a la ciudad de destino.
  - Optimizacion algoritmica en `calculateNavigationInstruction`: busqueda focalizada en ventana local $[-2 .. +8]$ tramos para deduccion inmediata de giros y carriles en tiempo $O(1)$.
- **Bitacora de Entregas y Registro de Viajes (`TripLogScreen` / `TripLogManager`):**
  - Nueva pestaña táctil dedicada en la interfaz (`AppTab.BITACORA`).
  - Registro automatico de cada entrega finalizada: ciudad de origen, destino, tipo de carga, tonelaje transportado, ganancias cobradas, distancia recorrida, velocidad media y porcentaje de daño.
  - Almacenamiento persistente encriptado localmente en `SharedPreferences` con tarjetas metricas de acumulados (ganancias totales, kilometros totales y viajes completados).
- **Alertas Tempranas de Telemetria en Cabina:**
  - Alerta acustica y visual por exceso de velocidad cuando la velocidad del vehiculo supera en > 5 km/h el limite legal de la via.
  - Alerta temprana de combustible critico (< 12% del tanque) y alerta preventiva de fatiga (< 35 min restantes para descanso obligatorio).
- **Selector de Temas Visuales del Mapa (`STY`):**
  - Boton flotante rapido para alternar entre `DARK_PREMIUM` (modo noche Figma), `HIGH_CONTRAST` (negro OLED) y `CYBER_NEON` (cian tactico).
- **Instalador Autogestionado para PC (.exe / Setup):**
  - `Instalador_AtsGps_v1.4.0_Setup.exe`: Instalador independiente con deteccion automatica del directorio de Steam de American Truck Simulator, copia automatica de `scs-telemetry.dll` a la carpeta de plugins del juego, configuracion del servidor y APK v1.4.0 en un solo archivo ejecutable con icono nativo.
- **Compilacion y Version:**
  - Android bumped to `versionCode = 15`, `versionName = "1.4.0"`.

---

## [v1.3.8] - 2026-11-19
### Mejorado y Corregido
- **Limpieza Visual Cero Emojis (Android y Lanzador PC):**
  - Reemplazo de todos los pictogramas por insignias tipograficas de alto contraste en `GpsMapCanvas.kt` (`GAS`, `RST`, `SRV`, `DLR`, `LOG`, `WGH`, `POI`), `MainActivity.kt` (`CTR`, `CFG`, `FUEL`, `DESCANSO`, `ALERTA: SENTIDO CONTRARIO - RECALCULANDO RUTA`, `EXPLORACION LIBRE - PULSA PARA CENTRAR`), `DashboardScreen.kt` (`OK Todo en orden`, `AVISO Revision requerida`, `Parada cercana`), `TelemetryDiagnosticScreen.kt` (`v1.3.8 (Build 13)`) y `AtsGpsLauncher` (`MainWindow.xaml` / `MainWindow.xaml.cs`).
- **Correccion de Encadenado Topologico en `GpsMapCanvas.kt`:**
  - `chainEdges()` ahora rechaza explicitamente la arista inversa directa (`candEdge.toNodeIndex == currEdge.fromNodeIndex`) y saltos hacia atras (`dot < -0.35f`), evitando plegados `A -> B -> A` y triangulos espurios tras el suavizado Chaikin.
- **Documentacion y Capturas Oficiales:**
  - Regeneracion de las 5 vistas oficiales `1080x2200` en `docs/screenshots/` a partir de `map_ats.bin` real.
  - Rediseño integral de `README.md` y limpieza completa de `CHANGELOG.md`.
- **Compilacion y Publicacion:**
  - `versionCode = 13`, `versionName = "1.3.8"` en Android y actualizacion publicada de `Iniciar_Servidor.exe` (`AtsGpsLauncher`).

---

## [v1.3.7] - 2026-11-19
### Agregado y Corregido
- **Ruteo A* con Velocidad, Jerarquia y Momento Inicial (`AStarRouter.kt`):**
  - Prohibicion de retroceso inmediato `A -> B -> A` y penalizacion de direccion inversa al inicio (`initialReversePenalty = 4500f` cuando `dot < -0.20f`).
  - Penalizaciones de giros cerrados (`cosTurn < -0.15f` y `cosTurn < -0.60f`), cruces sinteticos de mediana y semaforos, ponderando velocidad limite y categoria vial.
- **Seguimiento en Tiempo Real y Sentido Contrario (`MapGraph.kt`, `MainActivity.kt`):**
  - Agregado `evaluateRouteTracking()` para evaluar distancia a ruta activa, alineacion por rumbo y deteccion de sentido contrario (`isWrongWay`) tanto en vias dirigidas como en contraflujo sobre la ruta.
  - Recalculo dinamico en corrutina no cancelable (`routeJob` en `Dispatchers.Default`) ante desvios confirmados (`> 48 m` o `>= 4` frames fuera de ruta), sin interrumpir busquedas A* a 30 Hz.
  - Banner superior rojo de alerta y aviso de voz TTS (`"Precaucion, sentido contrario"`).

---

## [v1.3.6] - 2026-11-19
### Agregado y Corregido
- **Map-Matching Vial (`MapGraph.kt`, `MainActivity.kt`, `GpsMapCanvas.kt`):**
  - Implementado `snapToRoad()` con proyeccion ortogonal y filtro por rumbo (`4.5x` penalizacion a carriles opuestos) y `findBestRouteStartNode()` para incluir el tramo actual en A*.
- **Renderizado 2D y 3D en Pases Globales (`GpsMapCanvas.kt`):**
  - Suavizado Chaikin (`smoothPolyline`), subdivision de segmentos largos, recorte de ruta detras del camion (`buildSmoothActiveRoutePoints`), filtrado de micro-conectores de prefab (`roadType == 3 && length < 18f`), renderizado 2D en 2 pases globales y 3D con `clipRect`, recorte en plano cercano `projectSegment3D()` (`minDepth = 8f`), 3 pases de calzada y 4 pases de cinta cian.

---

## [v1.2.3] - 2026-11-05
> [!WARNING]
> **RECTIFICACION Y AVISO DE AUDITORIA: RESULTADOS PREVIOS MARCADOS COMO INVALIDOS**
> 1. **Benchmarks de A* previos (<1 ms en mapa completo):** Declarados **INVALIDOS**. El grafo anterior sufria de fragmentacion severa (16,425 componentes conexas aisladas; la componente mayor tenia solo 307 nodos).
> 2. **Conectividad de prefabs "100%":** Declarada **INVALIDA**. La formula anterior omitia el offset de origen PPD del modelo e interpretaba los campos `LeadsToNodes.Item1/Item3` como indices 1-based en lugar de bitmasks SCS.
> 3. **Historial de Git Reescrito:** Se purgaron binarios propietarios del historial de Git (`map_ats.bin` y APKs de debug).

### Cambiado y Corregido en v1.2.3
- **Conectividad del Grafo Vial (B.1 - B.3):**
  - Decodificacion bitmask en curvas SCS PPD y transformacion exacta de origen PPD (error reducido a **0.0000 m**).
  - Conexion topologica nativa por coincidencia exacta de `INode.Uid` (**0** casos de snapping geometrico en el grafo).
  - Crecimiento de componente conexa principal de **307 nodos** a **74,520 nodos (50.97%)**.
- **Ruteo A* Expandido por Aristas y Giros Prohibidos (B.4, C.5):**
  - Implementacion de estado `(node, prevNode)` validada con `EmpiricalRouteValidationTests`.
- **Descubrimiento Dinamico de Sectores (C.4):**
  - Escaneo HashFS dinamico de **420 sectores** en 90 ms.
- **App Android v1.2.3:**
  - `versionCode = 3`, `versionName = "1.2.3"`, APK Release firmado, `FLAG_KEEP_SCREEN_ON` e indicador "Juego en Pausa".
- **Servidor y Automatizacion:**
  - Deteccion dinamica de bibliotecas de Steam, puerto TCP 35500 y regla de firewall para subred local.

---

## [v1.2.2] - 2026-11-05
### Cambiado y Corregido
- Eliminacion de binarios propietarios del repositorio y adicion de CLI `--build-map`.
- Licenciamiento unificado: Servidor bajo **GNU GPL-2.0** y app Android bajo **MIT License**.
- Seguridad en red local: WebSocket `ws://` autenticado con PIN y validacion estricta de subredes RFC 1918.

---

## [v1.2.1] - 2026-11-05
### Agregado
- Configuracion de repositorio Git, `.gitignore` y creacion inicial de `README.md`.

---

## [v1.2.0] - 2026-11-05
### Agregado
- Cobertura de 276 sectores (Washington, Oregon, California, Nevada, Arizona e Idaho): 86,033 nodos, 124,156 aristas, 96 ciudades, 640 empresas y 1,321 POIs oficiales (Gasolineras, Sitios de descanso, Talleres mecanicos, Concesionarios, Empresas y Basculas).

### Corregido
- Correccion de visibilidad en Bend, Oregon, filtro de vision (*Frustum Culling*) en `GpsMapCanvas.kt` y proteccion de tamanos de fuente en Android Paint.

---

## [v1.1.0] - 2026-11-05
### Agregado
- Formato binario `ATSMAP03` con soporte para POIs.
- Controles tactiles de zoom (`0.10x` a `4.5x`), arrastre libre y botones `[+]`, `[-]`, `[CTR]`, `[3D / 2D]`.
- Rejilla tactica 3D en el suelo, carteles flotantes 3D y autodeteccion de ruta A*.

---

## [v1.0.1] - 2026-11-05
### Corregido
- Politica de trafico en red local privada (RFC 1918), contraste en campos de emparejamiento y signo de eje focal en proyeccion 3D.

---

## [v1.0.0] - 2026-11-05
### Lanzamiento Inicial Clean-Room
- Servidor .NET 10 con lector MMF `Local\SCSTelemetry` y WebSocket a 30 Hz.
- Extractor de mapas `TruckLib` y app Android en Jetpack Compose con Junction View, tablero de desgaste y asistente TTS.
