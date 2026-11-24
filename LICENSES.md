# Autoria y Registro de Licencias (LICENSES.md)

**Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA).**

Todo el codigo fuente original desarrollado para la plataforma **SGNIA - ATS Telemetry & GPS** (`server/`, `android/`, `tools/`, `tests/`, scripts de automatizacion y documentacion) es obra intelectual original disenada bajo arquitectura clean-room por **Gamaliel Velazquez Galicia (SGNIA)** y publicada bajo el modelo dual de codigo abierto descrito a continuacion. Los avisos de copyright y licencias de todas las bibliotecas y SDKs de terceros se preservan integramente en este registro y en el archivo consolidado [`NOTICE`](NOTICE).

Para verificar la integridad criptografica de los binarios publicados, consulta el archivo de sumas de verificacion [`SHA256SUMS`](SHA256SUMS).

---

## 1. Matriz Resumen de Licenciamiento

| Componente / Dependencia | Origen / Autor | Version | Licencia Aplicable | Alcance en el Proyecto |
| :--- | :--- | :--- | :--- | :--- |
| **SGNIA - ATS Telemetry & GPS Server** (`server/`, `tests/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0** | Enlaza `TruckLib` (GPL-2.0) en PC ([LICENSE](LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Android App** (`android/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2 (Build 17)` | **MIT License** | Cliente movil independiente sin `TruckLib` ([android/LICENSE](android/LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Launcher** (`tools/AtsGpsLauncher/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **MIT License** | Lanzador grafico WPF en PC |
| **SGNIA - ATS Telemetry & GPS Installer** (`tools/AtsGpsInstaller/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0 / MIT** | Empaqueta y despliega servidor (GPL-2.0) y cliente Android (MIT) |
| **SCS Telemetry SDK** (`third_party/scs_sdk/`) | SCS Software | `1.14+` | **MIT License** | Headers de referencia de memoria compartida |
| **scs-sdk-plugin** (`scs-telemetry.dll`) | RenCloud / contributors | `1.12.1` | **MIT License** | Plugin nativo para lectura MMF en el simulador |
| **TruckLib / HashFS / Models / Core / Sii** | sk-zk | `0.5.2` / `0.2.7` / `0.4.3` / `0.3.1` / `0.2.2` | **GNU GPL-2.0** | Lectura de archivos `.scs` y extraccion cartografica (solo PC) |
| **RBush / Sprache / GisDeflate / System.IO.Hashing** | Autores respectivos | `4.0.0` / `2.3.1` / `1.0.2` / `10.0.13` | **MIT License** | Dependencias transitivas de indice espacial, parsing y hashing en PC |
| **OkHttp & Okio** | Square, Inc. | `4.12.0` / `3.6.0+` | **Apache License 2.0** | Transporte HTTP y WebSocket en Android |
| **Kotlin & Coroutines** | JetBrains s.r.o. | `2.1.0` / `1.8.0+` | **Apache License 2.0** | Lenguaje y concurrencia en Android |
| **AndroidX & Jetpack Compose** | The Android Open Source Project (AOSP) | Compose BOM | **Apache License 2.0** | Interfaz declarativa y renderizado Canvas en Android |
| **Android SDK Platform-Tools (`adb`)** | Google LLC / AOSP | Opcional | **Apache License 2.0** | Herramienta externa opcional (no redistribuida) para tunel USB |

---

## 2. Esquema Dual de Licencias del Codigo Propio

- **Servidor PC y Suite de Pruebas (`server/`, `tests/`):** Licenciados bajo **GNU General Public License v2.0 (GPL-2.0)** debido a que referencian las bibliotecas `TruckLib` (`GPL-2.0`) para la lectura de paquetes HashFS y mapas `.scs`. El codigo fuente completo esta disponible en `https://github.com/Gamavg06/GpsATS`. Ver archivo [`LICENSE`](LICENSE) y [`NOTICE`](NOTICE).
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
- **Texto completo de la licencia:**
```text
MIT License

Copyright (c) 2018-2024 RenCloud / scs-sdk-plugin contributors

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

## 5. Biblioteca de Lectura de Formato SCS y Mapas (`TruckLib`)

- **Repositorio oficial:** https://github.com/sk-zk/TruckLib
- **Paquetes NuGet utilizados en `server/`:**
  - `TruckLib` v0.5.2
  - `TruckLib.HashFs` v0.2.7
  - `TruckLib.Models` v0.4.3
  - `TruckLib.Core` v0.3.1
  - `TruckLib.Sii` v0.2.2
- **Licencia:** GNU General Public License v2.0 (GPL-2.0)
- **Impacto legal:** Al estar `TruckLib` licenciada bajo GPL-2.0, el ensamblado del servidor `AtsGpsServer` que la referencia se distribuye bajo **GNU GPL-2.0** con codigo fuente disponible en `https://github.com/Gamavg06/GpsATS` y texto integro en [`LICENSE`](LICENSE). No se incluye ni se distribuye dentro del cliente Android ni en `AtsGpsLauncher`.

---

## 6. Bibliotecas Auxiliares del Servidor (.NET)

- **`RBush`** (v4.0.0) — MIT License
- **`Sprache`** (v2.3.1) — MIT License
- **`GisDeflate`** (v1.0.2) — MIT License
- **`System.IO.Hashing`** (v10.0.13) — MIT License

---

## 7. Bibliotecas del Cliente Android y Herramienta Externa Opcional

### 7.1 OkHttp
- **Repositorio oficial:** https://github.com/square/okhttp
- **Version:** 4.12.0
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2019 Square, Inc.

### 7.2 Okio
- **Repositorio oficial:** https://github.com/square/okio
- **Version:** 3.6.0+ (dependencia transitiva de OkHttp)
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2013 Square, Inc.

### 7.3 Kotlin Standard Library & Coroutines
- **Repositorio oficial:** https://github.com/JetBrains/kotlin y https://github.com/Kotlin/kotlinx.coroutines
- **Version:** Kotlin 2.1.0 / Coroutines 1.8.0+
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright 2010-2024 JetBrains s.r.o.

### 7.4 AndroidX & Google Jetpack Compose
- **Fuente oficial:** Android Open Source Project (AOSP)
- **Componentes:** `androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.compose.ui`, `androidx.compose.material3`
- **Licencia:** Apache License 2.0
- **Atribucion:** Copyright (C) The Android Open Source Project.

### 7.5 Android SDK Platform-Tools (`adb`) (Herramienta Externa Opcional)
- **Fuente oficial:** https://developer.android.com/tools/releases/platform-tools
- **Licencia:** Apache License 2.0 (Google LLC / AOSP)
- **Distribucion:** No se redistribuye en el repositorio ni en el instalador. Es completamente opcional para usuarios que deseen enlace por cable USB (`adb reverse`); la conexion por red Wi-Fi privada no requiere `adb`.

---

## 8. Verificacion e Integridad de Artefactos (`NOTICE` y `SHA256SUMS`)

- [`NOTICE`](NOTICE): Archivo de texto plano UTF-8 sin BOM con todos los avisos de copyright y licencias exigidos para distribucion binaria y documental.
- [`SHA256SUMS`](SHA256SUMS): Registro de hashes SHA-256 de los artefactos de distribucion oficiales publicados en cada version.
