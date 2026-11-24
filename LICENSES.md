# Autoria y Registro de Licencias (LICENSES.md)

**Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA).**

Todo el codigo fuente original desarrollado para la plataforma **SGNIA - ATS Telemetry & GPS** (`server/`, `android/`, `tools/`, `tests/`, scripts de automatizacion y documentacion) es obra intelectual original disenada bajo arquitectura clean-room por **Gamaliel Velazquez Galicia (SGNIA)** y publicada bajo el modelo dual de codigo abierto descrito a continuacion. Los avisos de copyright y licencias de todas las bibliotecas y SDKs de terceros se preservan integramente en este registro.

---

## 1. Matriz Resumen de Licenciamiento

| Componente / Dependencia | Origen / Autor | Version | Licencia Aplicable | Alcance en el Proyecto |
| :--- | :--- | :--- | :--- | :--- |
| **SGNIA - ATS Telemetry & GPS Server** (`server/`, `tests/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0** | Enlaza `TruckLib` (GPL-2.0) en PC ([LICENSE](LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Android App** (`android/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2 (Build 17)` | **MIT License** | Cliente movil independiente sin `TruckLib` ([android/LICENSE](android/LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Launcher** (`tools/AtsGpsLauncher/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **MIT License** | Lanzador grafico WPF en PC |
| **SGNIA - ATS Telemetry & GPS Installer** (`tools/AtsGpsInstaller/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0 / MIT** | Empaqueta y despliega servidor (GPL-2.0) y cliente Android (MIT) |
| **SCS Telemetry SDK** (`third_party/scs_sdk/`) | SCS Software | `1.14+` | **MIT License** | Headers de referencia de memoria compartida |
| **scs-sdk-plugin** (`scs-telemetry.dll`) | RenCloud | `1.12.1` | **MIT License** | Plugin nativo para lectura MMF en el simulador |
| **TruckLib / HashFS / Models** | sk-zk | `0.5.2` / `0.2.7` / `0.4.3` | **GNU GPL-2.0** | Lectura de archivos `.scs` y extraccion cartografica (solo PC) |
| **OkHttp & Okio** | Square, Inc. | `4.12.0` / `3.6.0+` | **Apache License 2.0** | Transporte HTTP y WebSocket en Android |
| **Kotlin & Coroutines** | JetBrains s.r.o. | `2.1.0` / `1.8.0+` | **Apache License 2.0** | Lenguaje y concurrencia en Android |
| **AndroidX & Jetpack Compose** | The Android Open Source Project (AOSP) | Compose BOM | **Apache License 2.0** | Interfaz declarativa y renderizado Canvas en Android |

---

## 2. Esquema Dual de Licencias del Codigo Propio

- **Servidor PC y Suite de Pruebas (`server/`, `tests/`):** Licenciados bajo **GNU General Public License v2.0 (GPL-2.0)** debido a que referencian las bibliotecas `TruckLib` (`GPL-2.0`) para la lectura de paquetes HashFS y mapas `.scs`. Ver archivo [`LICENSE`](LICENSE).
- **Aplicacion Movil Android y Lanzador WPF (`android/`, `tools/AtsGpsLauncher/`):** Licenciados bajo **MIT License**. Son componentes independientes que se comunican mediante HTTP/WebSocket y no contienen ni enlazan codigo ni binarios de `TruckLib`. Ver archivo [`android/LICENSE`](android/LICENSE).

---

## 3. SDK Oficial de SCS Software (SCS Telemetry SDK)

- **Ubicacion en el repositorio:** `third_party/scs_sdk/`
- **Fuente oficial:** https://modding.scssoft.com/wiki/Documentation/Engine/SDK/Telemetry
- **Archivos incluidos:** `NOTICE.txt`, `scs-telemetry-common.hpp`, `scs_sdk_readme.txt`, `scs_sdk_license.txt`
- **Licencia:** MIT License
- **Texto de la licencia:**
```text
SCS SDK
Copyright (C) 2016 SCS Software

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 4. Plugin de Memoria Compartida para SCS Telemetry (`scs-sdk-plugin`)

- **Repositorio oficial:** https://github.com/RenCloud/scs-sdk-plugin
- **Version:** 1.12.1
- **Licencia:** MIT License
- **Uso:** Provee la DLL `scs-telemetry.dll` instalada en el directorio de plugins de American Truck Simulator (`bin/win_x64/plugins/`) para publicar los datos del simulador en la memoria compartida `Local\SCSTelemetry`.

---

## 5. Biblioteca de Lectura de Formato SCS y Mapas (`TruckLib`)

- **Repositorio oficial:** https://github.com/sk-zk/TruckLib
- **Paquetes NuGet utilizados en `server/`:**
  - `TruckLib` v0.5.2
  - `TruckLib.HashFs` v0.2.7
  - `TruckLib.Models` v0.4.3
- **Licencia:** GNU General Public License v2.0 (GPL-2.0)
- **Impacto legal:** Al estar `TruckLib` licenciada bajo GPL-2.0, el ensamblado del servidor `AtsGpsServer` que la referencia se distribuye bajo **GNU GPL-2.0**. No se incluye ni se distribuye dentro del cliente Android ni en `AtsGpsLauncher`.

---

## 6. Bibliotecas del Cliente Android

### 6.1 OkHttp
- **Repositorio oficial:** https://github.com/square/okhttp
- **Version:** 4.12.0
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2019 Square, Inc.

### 6.2 Okio
- **Repositorio oficial:** https://github.com/square/okio
- **Version:** 3.6.0+ (dependencia transitiva de OkHttp)
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2013 Square, Inc.

### 6.3 Kotlin Standard Library & Coroutines
- **Repositorio oficial:** https://github.com/JetBrains/kotlin y https://github.com/Kotlin/kotlinx.coroutines
- **Version:** Kotlin 2.1.0 / Coroutines 1.8.0+
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2010-2024 JetBrains s.r.o.

### 6.4 AndroidX & Google Jetpack Compose
- **Fuente oficial:** Android Open Source Project (AOSP)
- **Componentes:** `androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.compose.ui`, `androidx.compose.material3`
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright (C) The Android Open Source Project.
