# Registro de Licencias y Fuentes Externas (LICENSES.md)

Este proyecto es software libre y de código abierto desarrollado bajo principios clean-room. Para garantizar la conformidad legal y la transparencia, todas las dependencias, herramientas y bibliotecas externas se detallan a continuación con su fuente, versión y licencia aplicable.

---

## 1. Esquema de Licenciamiento del Proyecto

* **Servidor PC (`server/`):** Licenciado bajo **GNU General Public License v2.0 (GPL-2.0)** debido a que referencia y enlaza dinámicamente las bibliotecas de `TruckLib` (GPL-2.0) para la lectura de paquetes HashFS y mapas `.scs`. (Ver archivo [`LICENSE`](LICENSE)).
* **Aplicación Móvil (`android/`):** Licenciada bajo **MIT License**. Es un cliente visual independiente que no contiene código ni binarios de TruckLib. (Ver archivo [`android/LICENSE`](android/LICENSE)).

---

## 2. SDK Oficial de SCS Software (SCS Telemetry SDK)

* **Ubicación en el repositorio:** `third_party/scs_sdk/`
* **Fuente oficial:** https://modding.scssoft.com/wiki/Documentation/Engine/SDK/Telemetry
* **Archivos incluidos:** `scs-telemetry-common.hpp`, `scs_sdk_readme.txt`, `scs_sdk_license.txt`
* **Licencia:** MIT License
* **Texto de la licencia:**
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

## 3. Plugin de Memoria Compartida para SCS Telemetry (scs-sdk-plugin)

* **Repositorio oficial:** https://github.com/RenCloud/scs-sdk-plugin
* **Versión:** 1.12.1
* **Licencia:** MIT License
* **Uso:** Genera la DLL `scs-telemetry.dll` que se instala en el directorio de plugins de American Truck Simulator (`bin/win_x64/plugins/`) para escribir en la memoria compartida `Local\SCSTelemetry`.

---

## 4. Biblioteca de Lectura de Formato SCS y Mapas (TruckLib)

* **Repositorio oficial:** https://github.com/sk-zk/TruckLib
* **Paquetes NuGet y versiones utilizadas en el servidor:**
  - `TruckLib` v0.5.2
  - `TruckLib.HashFs` v0.2.7
  - `TruckLib.Models` v0.4.3
* **Licencia:** GNU General Public License v2.0 (GPL-2.0)
* **Impacto legal en el proyecto:** Al estar `TruckLib` bajo la licencia GPL-2.0, el ensamblado del servidor `AtsGpsServer` que lo referencia se distribuye en su totalidad bajo los términos de la **GNU GPL v2.0**. No se incluye ni se distribuye dentro del cliente Android.

---

## 5. Bibliotecas del Cliente Android

### 5.1 OkHttp
* **Repositorio oficial:** https://github.com/square/okhttp
* **Versión:** 4.12.0
* **Licencia:** Apache License 2.0
* **Atribución:** Copyright 2019 Square, Inc.

### 5.2 Okio
* **Repositorio oficial:** https://github.com/square/okio
* **Versión:** 3.6.0+ (dependencia transitiva de OkHttp)
* **Licencia:** Apache License 2.0
* **Atribución:** Copyright 2013 Square, Inc.

### 5.3 Kotlin Standard Library & Coroutines
* **Repositorio oficial:** https://github.com/JetBrains/kotlin y https://github.com/Kotlin/kotlinx.coroutines
* **Versión:** Kotlin 2.1.0 / Coroutines 1.8.0+
* **Licencia:** Apache License 2.0
* **Atribución:** Copyright 2010-2024 JetBrains s.r.o.

### 5.4 AndroidX & Google Jetpack Compose
* **Fuente oficial:** Android Open Source Project (AOSP)
* **Componentes:** `androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.compose.ui`, `androidx.compose.material3`
* **Licencia:** Apache License 2.0
* **Atribución:** Copyright (C) The Android Open Source Project.
