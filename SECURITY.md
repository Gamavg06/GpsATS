# Politica de Seguridad (SECURITY.md)

Este documento describe las garantias de seguridad incorporadas en **SGNIA - ATS Telemetry & GPS** (version `1.4.2`) y el procedimiento para reportar vulnerabilidades.

---

## 1. Modelo de Seguridad en Red Local

El servidor companion escucha en el puerto TCP `35500` y esta disenado exclusivamente para operar dentro de una red local de confianza o mediante tunel USB directo (`adb reverse`).

### Autenticacion por PIN y Token de Sesion
- **Generacion Criptografica del PIN:** Al iniciar el servidor, `AuthManager` genera un PIN aleatorio de 6 digitos mediante `RandomNumberGenerator.GetInt32`.
- **Comparacion en Tiempo Constante y Bloqueo:** La verificacion del PIN en `POST /api/pair` utiliza `CryptographicOperations.FixedTimeEquals` y bloquea temporalmente nuevos intentos durante 2 minutos tras 5 fallos consecutivos.
- **Tokens de Sesion (48 horas):** Tras emparejarse, el servidor emite un token criptografico de 32 bytes con vigencia de 48 horas.
- **Endpoints Protegidos:** Tanto el canal WebSocket en tiempo real (`/ws/telemetry?token=...`) como los endpoints REST sensibles —`/api/telemetry/snapshot`, `/api/control/key` (emulacion de teclado virtual hacia el simulador) y `/api/map/build` (extraccion cartografica local)— exigen validacion estricta del token de sesion mediante query string `?token=` o cabecera `Authorization: Bearer <token>`.
- **Exencion Directa USB:** Las conexiones provenientes de loopback (`127.0.0.1` / `::1`) via `adb reverse` o de la subred estandar de USB Tethering Android (`192.168.42.x`) pueden autorizarse automaticamente desde `/api/pair` sin introducir el PIN manual.

### Restriccion a Subredes Privadas (RFC 1918 / Loopback)
- **Middleware de Servidor (`IpWhitelistMiddleware`):** Todas las peticiones HTTP y WebSocket entrantes son filtradas antes de alcanzar los controladores; cualquier direccion IP externa o publica recibe `403 Forbidden`. Unicamente se aceptan direcciones Loopback (`127.0.0.1`, `::1`), RFC 1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Link-Local (`169.254.0.0/16`, `fe80::`) y ULA IPv6 (`fc00::/7`).
- **Validacion en Cliente Android (`AtsNetworkClient`):** El cliente movil valida mediante `isAllowedPrivateAddress` que el host destino pertenezca exclusivamente a direcciones loopback, locales de sitio o link-local antes de establecer conexiones HTTP o WebSocket.
- **Regla de Firewall en Instalador:** El instalador oficial (`tools/AtsGpsInstaller/Program.cs`) configura la regla entrante para el puerto TCP `35500` restringida a perfil privado y subred local (`profile=private remoteip=LocalSubnet`).

---

## 2. Integridad del Instalador y Empaquetado

- **Proteccion contra Zip Slip:** Durante la extraccion del paquete embebido `payload.zip`, `tools/AtsGpsInstaller/Program.cs` resuelve `Path.GetFullPath(...)` y valida que cada ruta destino permanezca estrictamente dentro del directorio de instalacion seleccionado antes de crear directorios o escribir archivos.
- **Escritura Atomica del Mapa Binario:** `MapManager.BuildMapAsync` serializa primero hacia un archivo temporal `.tmp` y realiza el reemplazo atomico sobre `map_ats.bin` bajo un `SemaphoreSlim(1, 1)` para evitar archivos corruptos ante lecturas concurrentes.

---

## 3. Versiones Soportadas

| Version | Estado de Soporte |
| :--- | :--- |
| `1.4.2` | Soportada (version actual) |
| `< 1.4.2` | Sin soporte; actualizar a `1.4.2` |

---

## 4. Reporte de Vulnerabilidades

Si identificas una vulnerabilidad de seguridad en el servidor, cliente Android, lanzador WPF o instalador:

1. **No abras un issue publico** con detalles explotables.
2. Envia el reporte de forma privada mediante **GitHub Security Advisories** en el repositorio oficial (`https://github.com/Gamavg06/GpsATS/security/advisories`) o contactando directamente al autor (**Gamaliel Velazquez Galicia / SGNIA**) a traves de los canales privados del perfil del repositorio.
3. Incluye version afectada, pasos de reproduccion e impacto estimado. Se evaluara el incidente y se coordinara una correccion antes de su divulgacion publica.
