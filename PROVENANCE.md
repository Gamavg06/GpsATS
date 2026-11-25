# Auditoria de Procedencia y Trazabilidad (PROVENANCE.md)

**Proyecto:** SGNIA - ATS Telemetry & GPS (`v1.4.2`)  
**Autor:** Gamaliel Velazquez Galicia (SGNIA)  
**Repositorio oficial:** https://github.com/Gamavg06/GpsATS

---

## 1. Estado de Revision y Retencion de Distribucion

- **Retencion de distribucion publica:** La distribucion publica del instalador (`Instalador_AtsGps_v1.4.2_Setup.exe`) y de los binarios empaquetados permanece **retenida** hasta completar la revision y aprobacion formal de este documento de procedencia.
- **Retiro de afirmaciones no verificables:** Las etiquetas historicas de desarrollo aislado o sin trazabilidad documental fueron retiradas integramente de toda la documentacion del repositorio (`README.md`, `LICENSES.md`, `CHANGELOG.md`, `NOTICE`, `SECURITY.md` y este archivo), reemplazandose por trazabilidad tecnica verificable mediante historial de Git, derivacion matematica explicita e inventario completo del entorno de trabajo.

---

## 2. Origen y Trazabilidad de Constantes de Camara 3D (`GpsMapCanvas.kt`)

La proyeccion 3D en perspectiva del navegador (`android/app/src/main/java/com/ats/gps/ui/map/GpsMapCanvas.kt`) utiliza un modelo de camara estenopeica (*pinhole*) sobre plano de suelo horizontal (`y = 0`), implementado en `drawMap3D` (lineas 1353-1758, con `projectPoint3D` en lineas 1457-1473) y replicado en forma inversa en `detectTapGestures` (lineas 396-434).

### 2.1 Tabla de Constantes y Derivacion Geometrica

| Constante / Expresion | Valor / Formula Actual | Significado Geometrico y Justificacion Matematica |
| :--- | :--- | :--- |
| `horizonY` | `h * 0.26f` | Ubica la linea del horizonte al 26% superior de la altura del lienzo `h`, reservando el cuarto superior para cielo crepuscular y banner superior de navegacion/alertas, y el 74% inferior para la calzada proyectada. |
| `targetTruckY` | `h * 0.78f` | Fija la posicion en pantalla del icono del vehiculo al 78% de la altura `h`, dejando espacio abajo para las tarjetas HUD inferiores y por delante (entre `0.26f` y `0.78f`, es decir `52%` de `h`) para anticipar giros y enlaces viales. |
| `camDist3D` | `65f` | Distancia longitudinal virtual (`65` metros de juego) detras del camion sobre el eje `rz` (`depth = rz + camDist3D`). Permite mantener visibles los tramos inmediatamente posteriores al eje trasero sin colapsar cerca del plano focal. |
| `focal3D` | `440f * zoomScale.coerceIn(0.6f, 2.0f)` | Distancia focal efectiva en pixeles (`440f` base acotada por `zoomScale` entre `0.6x` y `2.0x`), calibrada para obtener un campo de vision horizontal cercano a pantalla completa movil sin distorsion excesiva en los bordes laterales. |
| `camHeight3D` | `(targetTruckY - horizonY) / (focal3D / camDist3D)` | Altura virtual de la camara resuelta algebraicamente a partir de la ecuacion de proyeccion `sy = horizonY + camHeight3D * (focal3D / depth)`. Al evaluar en la posicion del camion (`rz = 0`, `depth = camDist3D`), se garantiza exactamente `sy = horizonY + (targetTruckY - horizonY) = targetTruckY` independientemente de la resolucion `h` o del `zoomScale`. |
| `minDepth` | `8f` | Plano de recorte cercano (*near clipping plane* a `8` unidades por delante del ojo virtual) en `projectPoint3D` (`depth < 8f`) y `projectSegment3D`. Evita divisiones por cero y saltos hiperbolicos cuando un segmento cruza detras de la camara (`depth <= 0`), interpolando el punto de corte `t = (minDepth - depth1) / (depth2 - depth1)`. |
| `speedZoomRatio` | `(speedKmh / 90f).coerceIn(0f, 1f)` | Razon normalizada de velocidad respecto a `90 km/h` (velocidad tipica de crucero en autopista estadounidense). |
| `dynamicZoom` | `zoomScale * (0.85f + speedZoomRatio * 0.35f)` | Factor de escala dinamica por velocidad: en detencion o maniobra urbana (`0 km/h`) aplica `0.85x` para ampliar el campo cercano y mostrar intersecciones; a `90 km/h` alcanza `1.20x` (`0.85f + 0.35f`) para estrechar el enfoque hacia el horizonte. |

### 2.2 Trazabilidad Exacta en `git log`

El historial completo de `android/app/src/main/java/com/ats/gps/ui/map/GpsMapCanvas.kt` muestra la evolucion iterativa que condujo a las constantes actuales:

1. **`5806b78` (2026-11-05) — Proyeccion 3D inicial:**
   - Parametros iniciales: `horizonY = h * 0.35f`, `camFollowDist = 14f`, `camHeight = 6.0f`, `focalLength = h * 0.70f * zoomScale.coerceIn(0.5f, 2.5f)`.
2. **`7d59afc` -> `e6360f7` -> `b2f92fc` (2026-11-09) — Camara con inclinacion `pitchRad`:**
   - `7d59afc`: ajuste preliminar (`horizonY = h * 0.38f`, `camFollowDist = 14f`, `camHeight = 5.2f`, `focalLength = h * 0.72f`).
   - `e6360f7`: introduccion de inclinacion vertical `pitchRad = 16.0°`, `horizonY = h * 0.28f`, `camFollowDist = 18f`, `camHeight = 10.5f`, `focalLength = h * 0.65f`.
   - `b2f92fc`: elevacion y aumento de inclinacion (`pitchRad = 34.0°`, `horizonY = h * 0.18f`, `camFollowDist = 44f`, `camHeight = 28f`, `focalLength = h * 0.80f`).
3. **`724ef86` (2026-11-19, `v1.3.8`) — Sustitucion por el modelo pinhole actual:**
   - Reemplaza la camara previa con `pitchRad = 34°` por la formulacion algebraica actual sin rotacion de cabeceo explícita, introduciendo juntas todas las constantes vigentes: `horizonY = h * 0.26f`, `targetTruckY = h * 0.78f`, `camDist3D = 65f`, `focal3D = 440f * zoomScale.coerceIn(0.6f, 2.0f)`, `camHeight3D = (targetTruckY - horizonY) / (focal3D / camDist3D)`, `minDepth = 8f` y zoom dinamico por velocidad (`speedKmh / 90f` y `0.85f + speedZoomRatio * 0.35f`).
4. **`2960b49` (2026-11-19, `v1.4.0`) — Refactor a pools estaticos sin GC:**
   - Migra el renderizado a `RoadSegPool3D` y `RouteSegPool3D` con QuickSort in-place manteniendo identicas todas las constantes de camara.
5. **`3fc5ee9` (2026-11-23, `v1.4.2`) — Desproyeccion inversa en toques 3D:**
   - Replica exactamente las mismas constantes (`0.26f`, `0.78f`, `65f`, `90f`, `0.85f`, `0.35f`, `440f`, `camHeight3D`) dentro de `detectTapGestures` para mapear toques en pantalla hacia coordenadas del mundo 3D.

---

## 3. Inventario Completo de Archivos del Cliente y Auditoria del Workspace

### 3.1 Inventario Completo en `android/` (31 archivos)

Excluyendo las carpetas generadas de compilacion y cache local (`android/.gradle/` y `android/app/build/`), el cliente Android esta compuesto exactamente por los siguientes **31 archivos**:

**Codigo fuente Kotlin (17 archivos `.kt` bajo `android/app/src/main/java/com/ats/gps/`):**
1. `android/app/src/main/java/com/ats/gps/MainActivity.kt`
2. `android/app/src/main/java/com/ats/gps/data/map/AStarRouter.kt`
3. `android/app/src/main/java/com/ats/gps/data/map/BinaryMapReader.kt`
4. `android/app/src/main/java/com/ats/gps/data/model/MapGraph.kt`
5. `android/app/src/main/java/com/ats/gps/data/model/TelemetryData.kt`
6. `android/app/src/main/java/com/ats/gps/data/network/AtsNetworkClient.kt`
7. `android/app/src/main/java/com/ats/gps/data/network/AuthTokenStore.kt`
8. `android/app/src/main/java/com/ats/gps/data/triplog/TripLogManager.kt`
9. `android/app/src/main/java/com/ats/gps/ui/about/AboutScreen.kt`
10. `android/app/src/main/java/com/ats/gps/ui/dashboard/CircularGauge.kt`
11. `android/app/src/main/java/com/ats/gps/ui/dashboard/DashboardScreen.kt`
12. `android/app/src/main/java/com/ats/gps/ui/diagnostic/TelemetryDiagnosticScreen.kt`
13. `android/app/src/main/java/com/ats/gps/ui/junction/JunctionView.kt`
14. `android/app/src/main/java/com/ats/gps/ui/map/GpsMapCanvas.kt`
15. `android/app/src/main/java/com/ats/gps/ui/navigation/TurnByTurnCard.kt`
16. `android/app/src/main/java/com/ats/gps/ui/triplog/TripLogScreen.kt`
17. `android/app/src/main/java/com/ats/gps/ui/tts/NavigationVoiceAssistant.kt`

**Manifiesto, recursos, configuracion Gradle, licencia y archivos locales de firma (14 archivos):**
18. `android/app/build.gradle.kts`
19. `android/app/src/main/AndroidManifest.xml`
20. `android/app/src/main/res/drawable/ic_launcher.png`
21. `android/app/src/main/res/xml/network_security_config.xml`
22. `android/build.gradle.kts`
23. `android/gradle.properties`
24. `android/gradle/libs.versions.toml`
25. `android/gradle/wrapper/gradle-wrapper.jar`
26. `android/gradle/wrapper/gradle-wrapper.properties`
27. `android/gradlew.bat`
28. `android/LICENSE`
29. `android/local.properties` *(configuracion local del SDK, ignorado por Git)*
30. `android/release.keystore` *(almacen local de firma APK, ignorado por Git)*
31. `android/settings.gradle.kts`

### 3.2 Resultado de Inspeccion del Entorno de Trabajo (`c:\Telemetriagps`)

La inspeccion completa del arbol de trabajo `c:\Telemetriagps` (directorios `android/`, `apks/`, `docs/`, `server/`, `tests/`, `third_party/`, `tools/` y archivos raiz) confirma:

- **0 archivos** de clientes externos, paquetes APK ajenos ni fuentes de terceros adicionales en todo el entorno de trabajo.
- Los unicos artefactos de codigo o binarios de terceros presentes en el repositorio son:
  1. **`third_party/scs_sdk/`** (4 archivos oficiales del SCS Telemetry SDK: `NOTICE.txt`, `scs_sdk_license.txt`, `scs_sdk_readme.txt`, `scs-telemetry-common.hpp`).
  2. **`server/plugins/scs-telemetry.dll`** (binario de `RenCloud/scs-sdk-plugin` v1.12.1 bajo licencia MIT).
- El unico paquete movil compilado presente en `apks/` corresponde exclusivamente a la compilacion actual `apks/AtsGpsApp_v1.4.2.apk` (excluida de Git).

---

## 4. Relacion de Componentes de Terceros

Todas las dependencias de terceros (SDKs, bibliotecas `.NET` via NuGet, bibliotecas Android y herramientas externas opcionales) junto con sus avisos de copyright y textos verbatim de licencia se encuentran documentados en [`NOTICE`](NOTICE) y [`LICENSES.md`](LICENSES.md).
