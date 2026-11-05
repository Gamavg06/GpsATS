# CHANGELOG - ATS Telemetría GPS

Todas las modificaciones notables, correcciones de errores y nuevas funcionalidades introducidas en este proyecto se registran en este documento.

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
