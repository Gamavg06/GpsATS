# Autoria y Registro de Licencias (LICENSES.md)

**Copyright (c) 2026 Gamaliel Velazquez Galicia (SGNIA).**

Todo el codigo fuente original desarrollado para la plataforma **SGNIA - ATS Telemetry & GPS** (`server/`, `android/`, `tools/`, `tests/`, scripts de automatizacion y documentacion) es obra intelectual original desarrollada por **Gamaliel Velazquez Galicia (SGNIA)** y publicada bajo el modelo dual de codigo abierto descrito a continuacion. Los avisos de copyright y textos verbatim de licencia de todas las bibliotecas y SDKs de terceros se preservan integramente en este registro y en el archivo consolidado [`NOTICE`](NOTICE).

Para verificar la integridad criptografica de los binarios publicados, consulta el archivo de sumas de verificacion [`SHA256SUMS`](SHA256SUMS), y para la trazabilidad tecnica de procedencia consulta [`PROVENANCE.md`](PROVENANCE.md).

---

## 1. Matriz Resumen de Licenciamiento

| Componente / Dependencia | Origen / Autor | Version | Licencia Aplicable | Alcance en el Proyecto |
| :--- | :--- | :--- | :--- | :--- |
| **SGNIA - ATS Telemetry & GPS Server** (`server/`, `tests/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0** | Enlaza `TruckLib` (GPL-2.0) en PC ([LICENSE](LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Android App** (`android/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2 (Build 17)` | **MIT License** | Cliente movil independiente sin `TruckLib` ([android/LICENSE](android/LICENSE)) |
| **SGNIA - ATS Telemetry & GPS Launcher** (`tools/AtsGpsLauncher/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **MIT License** | Lanzador grafico WPF en PC |
| **SGNIA - ATS Telemetry & GPS Installer** (`tools/AtsGpsInstaller/`) | Gamaliel Velazquez Galicia (SGNIA) | `1.4.2` | **GNU GPL-2.0 / MIT** | Empaqueta y despliega servidor (GPL-2.0) y cliente Android (MIT) |
| **SCS Telemetry SDK** (`third_party/scs_sdk/`) | SCS Software | `1.14+` | **SCS SDK (MIT)** | Headers de referencia de memoria compartida |
| **scs-sdk-plugin** (`scs-telemetry.dll`) | Hans / RenCloud | `1.12.1` | **The MIT License (MIT)** | Plugin nativo para lectura MMF en el simulador |
| **TruckLib / HashFS / Models / Core / Sii** | skzk (`sk-zk/TruckLib`) | `0.5.2` / `0.2.7` / `0.4.3` / `0.3.1` / `0.2.2` | **GNU GPL-2.0** | Lectura de archivos `.scs` y extraccion cartografica (solo PC) |
| **RBush** | viceroypenguin / Turning Code, LLC | `4.0.0` | **MIT License** | Indice espacial R-Tree en servidor PC |
| **Sprache** | Nicholas Blumhardt / Sprache Contributors | `2.3.1` | **The MIT License** | Combinadores de parsing SII en servidor PC |
| **GisDeflate** | skzk / Microsoft / Eric Biggers / NVIDIA | `1.0.2` | **MIT / Apache-2.0** | Descompresion auxiliar en servidor PC |
| **System.IO.Hashing** | .NET Foundation / Microsoft Corporation | `10.0.13` | **The MIT License (MIT)** | Hashing no criptografico en servidor PC |
| **OkHttp** | Square, Inc. | `4.12.0` | **Apache License 2.0** | Transporte HTTP y WebSocket en Android |
| **Okio** | Square, Inc. | `3.6.0+` | **Apache License 2.0** | I/O de buffers en Android (dependencia transitiva de OkHttp) |
| **Kotlin & Coroutines** | JetBrains s.r.o. y colaboradores | `2.1.0` / `1.8.0+` | **Apache License 2.0** | Lenguaje y concurrencia en Android |
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
- **Licencia:** SCS SDK (MIT-style License)
- **Texto verbatim de `third_party/scs_sdk/NOTICE.txt`:**
```text
SCS Telemetry SDK Files
=========================
These files (scs-telemetry-common.hpp, scs_sdk_readme.txt, scs_sdk_license.txt) originate from SCS Software's Telemetry SDK.
Distributed under the SCS SDK License (see scs_sdk_license.txt).
Copyright (c) SCS Software.
```
- **Texto verbatim de `third_party/scs_sdk/scs_sdk_license.txt`:**
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
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

---

## 4. Plugin de Memoria Compartida para SCS Telemetry (`scs-sdk-plugin`)

- **Repositorio oficial:** https://github.com/RenCloud/scs-sdk-plugin
- **Archivo fuente de licencia:** https://raw.githubusercontent.com/RenCloud/scs-sdk-plugin/master/LICENSE
- **Version:** 1.12.1
- **Licencia:** The MIT License (MIT)
- **Uso:** Provee la DLL `scs-telemetry.dll` (`server/plugins/scs-telemetry.dll`) instalada en el directorio de plugins de American Truck Simulator (`bin/win_x64/plugins/`) para publicar los datos del simulador en la memoria compartida `Local\SCSTelemetry`.
- **Texto verbatim completo de `LICENSE`:**
```text
The MIT License (MIT)

Copyright (c) 2014 Hans

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

---

## 5. Biblioteca de Lectura de Formato SCS y Mapas (`TruckLib`)

- **Repositorio oficial:** https://github.com/sk-zk/TruckLib
- **Autor:** `skzk`
- **Paquetes NuGet utilizados en `server/`:**
  - `TruckLib` v0.5.2
  - `TruckLib.HashFs` v0.2.7
  - `TruckLib.Models` v0.4.3
  - `TruckLib.Core` v0.3.1
  - `TruckLib.Sii` v0.2.2
- **Licencia:** GNU General Public License Version 2, June 1991 (`Copyright (C) 1989, 1991 Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA`)
- **Impacto legal:** Al estar `TruckLib` licenciada bajo GPL-2.0, el ensamblado del servidor `AtsGpsServer` que la referencia se distribuye bajo **GNU GPL-2.0** con codigo fuente disponible en `https://github.com/Gamavg06/GpsATS` y texto integro verbatim de la licencia distribuido en el archivo raiz [`LICENSE`](LICENSE). No se incluye ni se distribuye dentro del cliente Android ni en `AtsGpsLauncher`.

---

## 6. Bibliotecas Auxiliares del Servidor (.NET)

### 6.1 RBush (`4.0.0`)
- **Repositorio oficial:** https://github.com/viceroypenguin/RBush
- **Copyright en `.nuspec`:** `Copyright © 2017-2024 Turning Code, LLC (and others)`
- **Texto verbatim de `LICENSE` (`https://raw.githubusercontent.com/viceroypenguin/RBush/v4.0.0/LICENSE`):**
```text
MIT License

Copyright (c) 2017 viceroypenguin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### 6.2 Sprache (`2.3.1`)
- **Repositorio oficial:** https://github.com/sprache/Sprache
- **Copyright en `.nuspec`:** `Copyright Sprache Contributors 2017`
- **Texto verbatim de `licence.txt` (`https://raw.githubusercontent.com/sprache/Sprache/master/licence.txt`):**
```text
The MIT License

Copyright (c) 2011 Nicholas Blumhardt

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### 6.3 GisDeflate (`1.0.2`)
- **Autor NuGet:** `skzk`
- **Licencia:** MIT License y Apache License 2.0 (4 bloques de aviso verbatim en `gisdeflate/1.0.2/LICENSE.md`):
```text
MIT License

Copyright (c) 2024 skzk

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

===

MIT License

Copyright (c) Microsoft Corporation.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE

===

Copyright 2016 Eric Biggers

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation files
(the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

SPDX-FileCopyrightText: Copyright (c) 2020, 2021, 2022 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 6.4 System.IO.Hashing (`10.0.13`)
- **Repositorio oficial:** https://github.com/dotnet/runtime
- **Copyright en `.nuspec`:** `© Microsoft Corporation. All rights reserved.`
- **Texto verbatim de `LICENSE.TXT` (`https://raw.githubusercontent.com/dotnet/runtime/main/LICENSE.TXT`):**
```text
The MIT License (MIT)

Copyright (c) .NET Foundation and Contributors

All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

---

## 7. Bibliotecas del Cliente Android y Herramienta Externa Opcional

Todas las dependencias siguientes se utilizan bajo **Apache License, Version 2.0** (`http://www.apache.org/licenses/LICENSE-2.0`):

### 7.1 OkHttp
- **Repositorio oficial:** https://github.com/square/okhttp
- **Version:** 4.12.0
- **Licencia:** Apache License, Version 2.0 (`http://www.apache.org/licenses/LICENSE-2.0`)
- **Atribucion:** `Copyright 2019 Square, Inc.`

### 7.2 Okio
- **Repositorio oficial:** https://github.com/square/okio
- **Version:** 3.6.0+ (dependencia transitiva de OkHttp)
- **Licencia:** Apache License, Version 2.0 (`http://www.apache.org/licenses/LICENSE-2.0`)
- **Atribucion:** `Copyright 2013 Square, Inc.`

### 7.3 Kotlin Standard Library & Coroutines
- **Repositorio oficial:** https://github.com/JetBrains/kotlin y https://github.com/Kotlin/kotlinx.coroutines
- **Version:** Kotlin 2.1.0 / Coroutines 1.8.0+
- **Licencia:** Apache License, Version 2.0 (`http://www.apache.org/licenses/LICENSE-2.0`)
- **Atribucion (`license/COPYRIGHT.txt` y `license/NOTICE.txt`):** `Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.` (Kotlin Compiler: `Copyright 2010-2024 JetBrains s.r.o. and respective authors and developers`).

### 7.4 AndroidX & Google Jetpack Compose
- **Fuente oficial:** Android Open Source Project (AOSP)
- **Componentes:** `androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.compose.ui`, `androidx.compose.material3`
- **Licencia:** Apache License, Version 2.0 (`http://www.apache.org/licenses/LICENSE-2.0`)
- **Atribucion:** `Copyright (C) The Android Open Source Project`

### 7.5 Android SDK Platform-Tools (`adb`) (Herramienta Externa Opcional)
- **Fuente oficial:** https://developer.android.com/tools/releases/platform-tools
- **Licencia:** Apache License, Version 2.0 (`http://www.apache.org/licenses/LICENSE-2.0`, Google LLC / AOSP)
- **Distribucion:** No se redistribuye en el repositorio ni en el instalador. Es completamente opcional para usuarios que deseen enlace por cable USB (`adb reverse`); la conexion por red Wi-Fi privada no requiere `adb`.

---

## 8. Verificacion e Integridad de Artefactos (`NOTICE` y `SHA256SUMS`)

- [`NOTICE`](NOTICE): Archivo de texto plano UTF-8 sin BOM con todos los avisos de copyright y textos verbatim de licencia exigidos para distribucion binaria y documental.
- [`SHA256SUMS`](SHA256SUMS): Registro de hashes SHA-256 de los artefactos de distribucion oficiales publicados en cada version.
- [`PROVENANCE.md`](PROVENANCE.md): Auditoria de procedencia de constantes de camara, inventario de archivos del cliente y estado de retencion de distribucion.
