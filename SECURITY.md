# Politica de Seguridad (SECURITY.md)

Este documento describe las garantias de seguridad incorporadas en **SGNIA - ATS Telemetry & GPS** (version `1.4.2`) y el procedimiento para reportar vulnerabilidades.

---

## 1. Modelo de Seguridad en Red Local

El servidor companion escucha en el puerto TCP `35500` y esta disenado exclusivamente para operar dentro de una red local privada de confianza o mediante tunel USB opcional (`adb reverse`).

### Validacion Estricta de `Origin`, `Host` y Subredes Privadas
- **Bloqueo de Peticiones con `Origin` (`RequestSecurityMiddleware`):** Cualquier peticion HTTP o WebSocket que incluya la cabecera `Origin` (sin importar su valor, incluyendo `localhost`) recibe **`403 Forbidden`** inmediatamente antes de alcanzar los endpoints, bloqueando ataques CSRF y llamadas desde navegadores web.
- **Validacion Estricta del Encabezado `Host` (`RequestSecurityMiddleware`):** Se analiza el `Host` (sin puerto y normalizando IPv6) y se permiten unicamente `localhost`, direcciones loopback (`127.0.0.0/8`, `::1`), direcciones IPv4 privadas RFC 1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) e IPv6 link-local (`fe80::/10`) o ULA (`fc00::/7`). Cualquier otro valor (dominios externos, IPs publicas, `169.254.x.x` o `Host` vacio/invalido) recibe **`403 Forbidden`**.
- **Middleware de IP de Origen (`IpWhitelistMiddleware`):** Todas las conexiones entrantes con IP publica o fuera de redes locales permitidas reciben **`403 Forbidden`**.
- **Validacion en Cliente Android (`AtsNetworkClient`):** El cliente movil verifica mediante `isAllowedPrivateAddress` que el host destino pertenezca exclusivamente a direcciones loopback, locales de sitio o link-local antes de establecer conexiones HTTP o WebSocket.
- **Regla de Firewall en Instalador:** El instalador oficial (`tools/AtsGpsInstaller/Program.cs`) configura la regla entrante para el puerto TCP `35500` restringida a perfil privado y subred local (`profile=private remoteip=LocalSubnet`).

### Autenticacion Obligatoria por PIN, Bloqueo por IP y Token Bearer
- **Generacion Criptografica del PIN:** Al iniciar el servidor, `AuthManager` genera un PIN aleatorio de 6 digitos mediante `RandomNumberGenerator.GetInt32`.
- **Sin Exenciones USB ni Localhost:** `POST /api/pair` exige siempre el PIN valido de 6 digitos tanto por Wi-Fi como por cable USB (`adb reverse` / `127.0.0.1` o `192.168.42.x`). No existen credenciales especiales ni bypass automatico para conexiones locales; un PIN vacio, incorrecto o legado recibe **`401 Unauthorized`**.
- **Comparacion en Tiempo Constante y Bloqueo por IP:** La verificacion del PIN utiliza `CryptographicOperations.FixedTimeEquals`. Tras 5 intentos fallidos consecutivos desde una misma direccion IP, el servidor bloquea esa IP especifica durante 2 minutos respondiendo **`429 Too Many Requests`** (con cabecera `Retry-After`), sin afectar a otros dispositivos legitimos de la red local.
- **Tokens de Sesion (48 horas) y Persistencia Segura:** Tras emparejarse con el PIN correcto, el servidor emite un token criptografico de 32 bytes con vigencia de 48 horas. En Android, `AuthTokenStore` persiste el token vinculado al host durante 48 horas y lo revoca automaticamente si el servidor responde `401`, solicitando de nuevo el PIN.
- **Endpoints Protegidos (`401 Unauthorized` sin `Authorization: Bearer <token>`):**
  - `POST /api/control/key` (emulacion de teclado virtual hacia el simulador)
  - `GET /api/map/info`
  - `POST /api/map/build` (extraccion cartografica local)
  - `GET /api/map/download`
  - `GET /api/destinations`
  - `GET /api/telemetry/snapshot`
  - `GET /ws/telemetry` (el token mediante `Authorization: Bearer <token>` —o fallback opcional— se valida **antes** de ejecutar `AcceptWebSocketAsync()`, devolviendo HTTP `401` si falta o es invalido)
- Unicamente `GET /api/status` (salud/version basica) y `POST /api/pair` (protegido por PIN y bloqueo por IP) no requieren cabecera `Authorization: Bearer`.

---

## 2. Integridad del Instalador, Empaquetado y Mapa Local

- **Aceptacion Explicita de Licencias:** `tools/AtsGpsInstaller/Program.cs` presenta `NOTICE`, `LICENSES.md` y `LICENSE` antes de permitir la instalacion.
- **Proteccion contra Zip Slip:** Durante la extraccion del paquete embebido `payload.zip`, el instalador resuelve `Path.GetFullPath(...)` y valida que cada ruta destino permanezca estrictamente dentro del directorio de instalacion seleccionado.
- **Sin Redistribucion de Datos del Juego y Escritura Atomica:** `map_ats.bin` no se distribuye en el instalador ni en el repositorio; se genera localmente en la PC del usuario via `AtsGpsServer --build-map` escribiendo primero a `.tmp` y realizando reemplazo atomico bajo `SemaphoreSlim(1, 1)`.
- **Verificacion de Binarios (`SHA256SUMS`):** Las sumas criptograficas de los artefactos de distribucion oficiales se publican en [`SHA256SUMS`](SHA256SUMS).

---

## 3. Firma Digital (Authenticode) y Aviso de Windows SmartScreen

- **Estado actual de compilacion sin firma:** Los ejecutables Windows del proyecto (`Instalador_AtsGps_v1.4.2_Setup.exe` e `Iniciar_Servidor.exe`) se compilan actualmente **sin firma digital Authenticode**, ya que no existe un certificado comercial de firma de codigo instalado en el entorno de compilacion. Debido a la ausencia de firma y reputacion previa en Microsoft SmartScreen, Windows Defender SmartScreen puede mostrar la advertencia `"Windows protegio su PC"` al ejecutarlos por primera vez. La distribucion publica del instalador permanece retenida hasta completar la revision de [`PROVENANCE.md`](PROVENANCE.md).
- **Verificacion criptografica previa con `SHA256SUMS`:** Antes de ejecutar cualquier binario descargado o compilado, verifica su hash SHA-256 en PowerShell y comparalo con el valor publicado en [`SHA256SUMS`](SHA256SUMS):
```powershell
Get-FileHash -Algorithm SHA256 .\Instalador_AtsGps_v1.4.2_Setup.exe
Get-FileHash -Algorithm SHA256 .\Iniciar_Servidor.exe
```
- **Hoja de ruta de firma Authenticode:** Para futuras entregas publicas se contempla firmar todos los ejecutables Windows mediante un certificado comercial de firma de codigo (OV/EV) o **Microsoft Trusted Signing**, utilizando `signtool.exe` del **Windows 11 SDK** con sellado de tiempo RFC 3161.

---

## 4. Versiones Soportadas

| Version | Estado de Soporte |
| :--- | :--- |
| `1.4.2` | Soportada (version actual) |
| `< 1.4.2` | Sin soporte; actualizar a `1.4.2` |

---

## 5. Reporte de Vulnerabilidades

Si identificas una vulnerabilidad de seguridad en el servidor, cliente Android, lanzador WPF o instalador:

1. **No abras un issue publico** con detalles explotables.
2. Envia el reporte de forma privada mediante **GitHub Security Advisories** en el repositorio oficial (`https://github.com/Gamavg06/GpsATS/security/advisories`) o contactando directamente al autor (**Gamaliel Velazquez Galicia / SGNIA**) a traves de los canales privados del perfil del repositorio.
3. Incluye version afectada, pasos de reproduccion e impacto estimado. Se evaluara el incidente y se coordinara una correccion antes de su divulgacion publica.
