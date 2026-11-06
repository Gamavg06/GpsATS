# CHANGELOG - ATS Telemetría GPS

Todas las modificaciones notables, correcciones de errores y nuevas funcionalidades introducidas en este proyecto se registran en este documento.

---

## [v1.2.3] — 2026-11-05
> [!WARNING]
> **RECTIFICACIÓN Y AVISO DE AUDITORÍA: RESULTADOS PREVIOS MARCADOS COMO INVÁLIDOS**
> 1. **Benchmarks de A* previos (<1 ms en mapa completo):** Declarados **INVÁLIDOS**. El grafo original sufría de fragmentación severa (16,425 componentes conexas aisladas; la componente mayor tenía solo 307 nodos). Las mediciones anteriores corrían sobre nodos en islas aisladas o caminos triviales sin conectividad interestatal real.
> 2. **Conectividad de prefabs "100%":** Declarada **INVÁLIDA**. La fórmula anterior omitía el offset de origen PPD del modelo (`desc.Nodes[Origin].Position`) e interpretaba los campos `LeadsToNodes.Item1/Item3` como índices 1-based en lugar de bitmasks SCS (`1 << index`), descartando curvas y produciendo un error mediano de ~20m-27m en coordenadas del mundo.
> 3. **Historial de Git Reescrito:** Se documenta que el historial de Git fue reescrito para purgar de raíz binarios propietarios (`map_ats.bin` de 60 MB y APKs de debug).

### Cambiado y Corregido en v1.2.3
- **Conectividad del Grafo Vial (B.1 - B.3):**
  - **Decodificación bitmask en curvas SCS PPD:** Se implementó `BitOperations.TrailingZeroCount(nc.LeadsToNodes.Item1)` y `Item3`, recuperando las curvas de 4 vías y cruces en trébol descartadas anteriormente.
  - **Transformación de origen PPD exacta:** Se aplica `worldOrigin + Vector3.Transform(localPos - ppdOriginPos, rotation)`, reduciendo el error en el nodo de anclaje de 60.0 m a **0.0000 m**.
  - **Conexión topológica pura (0m snapping):** Se eliminó el snapping geométrico de 25m. La vinculación entre carreteras y prefabs se realiza al 100% de forma nativa por coincidencia exacta de `INode.Uid`. Casos que requieren snapping: **0**.
  - **Crecimiento de componente conexa:** La componente gigante pasó de **307 nodos (0.21%)** a **74,520 nodos (50.97%)** (+24,200% de incremento). Bend, Portland y Seattle se encuentran ahora en la misma componente conexa principal (CompID 1).
- **Ruteo A* Expandido por Aristas y Giros Prohibidos (B.4, C.5):**
  - Implementación de estado `(node, prevNode)` que penaliza giros en U y descarta giros prohibidos en intersecciones. Verificado con caso real en `EmpiricalRouteValidationTests`.
  - Ruteo empírico medido: Bend→Portland calculada con éxito (16.75 km de grafo, ratio de desvío 1.42x, 217 aristas, 8 ms en frío / 3.59 ms en caliente).
  - Transparencia: El ruteo de larga distancia a través de múltiples estados sigue en desarrollo y no se clasifica como 100% terminado.
- **Descubrimiento Dinámico de Sectores (C.4):**
  - Escaneo dinámico acelerado vía entradas HashFS: detecta exactamente **420 sectores** en 90 ms (+144 sectores sobre la lista fija de 276).
- **App Android v1.2.3 (D.9, E.13):**
  - `versionCode = 3`, `versionName = "1.2.3"`.
  - Generación de APK de Release firmado con keystore privada propia (SHA-256: `0E545FD91DE05F248CFEC95B59A974B1965A3728CDB39E99A933359B07BBB6DA`).
  - `debuggable=false`, `allowBackup=false`, `FLAG_KEEP_SCREEN_ON` para mantener pantalla encendida.
  - Indicador "Juego en Pausa" en la barra de telemetría cuando el juego se detiene, evitando valores congelados.
- **Servidor y Automatización (E.14, E.15):**
  - Detección dinámica de ATS en todas las bibliotecas de Steam (`libraryfolders.vdf`).
  - Puerto TCP 35500 unificado en toda la documentación, scripts y código.
  - Regla de firewall documentada con `-Profile Private -RemoteAddress LocalSubnet`.
  - Aviso de licencia MIT para el plugin `scs-telemetry.dll` (RenCloud).

---

## [v1.2.2] — 2026-11-05
### Cambiado y Corregido
- **Eliminación de binarios propietarios del repositorio:** `map_ats.bin` y APKs eliminados del historial y rastreo de Git.
- **Herramienta CLI `--build-map`:** Generación local de la red vial desde la instalación de ATS del usuario.
- **Licenciamiento unificado:** Servidor bajo **GNU GPL-2.0** (enlace con TruckLib) y app Android bajo **MIT License**.
- **Atribuciones añadidas:** OkHttp, Okio, Kotlin y AndroidX documentados en `LICENSES.md`.
- **Seguridad en red local:** WebSocket `ws://` documentado con precisión (sin cifrado TLS, autenticado con PIN). Validación estricta en código de subredes RFC 1918.
- **APK Release firmado:** Compilación con keystore RSA 2048, `debuggable=false`, `allowBackup=false`, icono propio y pantalla fija (`FLAG_KEEP_SCREEN_ON`).
- **Pruebas empíricas:** Suite `EmpiricalEvidenceTests.cs` (A* <1ms / 16KB, conectividad 100%, giros prohibidos y escaneo dinámico de sectores).

---

## [v1.2.1] — 2026-11-05
### Agregado
- **Documentación oficial y publicación en GitHub:** Configuración de repositorio Git en rama `main`, exclusión optimizada vía `.gitignore` y creación de `README.md` exhaustivo con diagramas Mermaid, insignias de estado, guía paso a paso y arquitectura del sistema.

---

## [v1.2.0] — 2026-11-05
### Agregado
- **Cobertura nacional de mapa (276 sectores):** Se extrajo la totalidad de la red vial de Washington, Oregón, California, Nevada, Arizona e Idaho.
- **86,033 Nodos viales** y **124,156 Aristas de carretera**.
- **96 Ciudades completas** registradas (incluyendo Bend, Eugene, Medford, Burns, Boise, Reno, etc.).
- **640 Empresas** y **1,321 Puntos de Interés (POIs)** oficiales:
  - ⛽ Gasolineras
  - 🛏️ Sitios de descanso y Garajes
  - 🔧 Talleres mecánicos
  - 🏬 Concesionarios de camiones (Peterbilt, Kenworth, Drake, etc.)
  - 🏢 Empresas de entrega
  - ⚖️ Básculas de pesaje

### Corregido
- **Pantalla negra en Bend, Oregón:** Se identificó que Bend reside en los sectores `(-26, -11)` y `(-26, -10)` que no estaban presentes en el lote inicial de 52 sectores. Con la inclusión de los 276 sectores, la zona queda completamente poblada de carreteras y servicios.
- **Filtro de visión (*Frustum Culling*) en `GpsMapCanvas.kt`:** Se corrigió la condición que descartaba tramos largos de carretera cuyos extremos salían del cuadro de visión pero cruzaban por el centro de la pantalla.
- **Protección de dibujo en Canvas:** Se protegieron los tamaños de fuente de las etiquetas de ciudades y POIs para evitar excepciones numéricas en Android Paint.

---

## [v1.1.0] — 2026-11-05
### Agregado
- **Formato binario `ATSMAP03`:** Soporte completo para serializar y deserializar puntos de interés (POIs).
- **Controles táctiles de navegación:** Soporte para pellizco (*pinch-to-zoom*) de $0.10\times$ a $4.5\times$ y arrastre (*drag-to-pan*).
- **Botones flotantes de control:**
  - `[+]` y `[-]`: Zoom in / Zoom out rápido.
  - `[🎯]`: Recentrado instantáneo al camión con pastilla de estado.
  - `[3D / 2D]`: Alternador de perspectiva.
- **Rejilla táctica 3D en el suelo:** Líneas de perspectiva en el asfalto que aportan sensación de movimiento y velocidad incluso en estacionamientos.
- **Carteles flotantes 3D (Billboards):** Iconos de servicios en perspectiva con postes verticales anclados al suelo.
- **Autodetección de carga y ruta:** Detección automática de mercancía, tonelaje, origen, destino y trazado de ruta con algoritmo A*.

---

## [v1.0.1] — 2026-11-05
### Corregido
- **Política de seguridad de red:** Se activó `usesCleartextTraffic` y configuración XML permisiva para IPs privadas (RFC 1918) como `172.16.46.179`.
- **Contraste de campos de texto:** Color blanco puro explícito en los campos de IP, Puerto y PIN en Compose Dark Mode.
- **Proyección de cámara 3D:** Se invirtió el signo del eje focal de avance ($z_{cam}$) adaptado al sistema de coordenadas de SCS.

---

## [v1.0.0] — 2026-11-05
### Lanzamiento Inicial Clean-Room
- **Servidor .NET 10:** Lector MMF `Local\SCSTelemetry`, broadcaster WebSocket a 30 Hz y seguridad por PIN efímero de 6 dígitos.
- **Extractor de mapas `TruckLib`:** Parsing de carreteras, prefabs y ciudades desde archivos `.scs` oficiales.
- **App Android (Jetpack Compose):**
  - Motor de navegación 2D/3D.
  - Garmin Junction View para salidas de autopista.
  - Tablero analógico con los 5 canales de desgaste oficiales de SCS.
  - Asistente de voz TTS nativo.
