# CHANGELOG - ATS Telemetria GPS

Todas las modificaciones notables, correcciones de errores y nuevas funcionalidades introducidas en este proyecto se registran en este documento.

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
