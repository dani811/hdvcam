# Arquitectura reconstruida

## Identidad y capas

Aplicación Android `com.record.dv`, versión `1.23.20261012` (23), minSdk 24 / targetSdk 36, arm64 y armeabi-v7a. El paquete de producto contiene pantallas propias; el SDK `com.weapons18.api` centraliza acceso a dispositivos y medios; JNI delega funciones de cámara a `libipcamera.so`/`libaw_net_client.so`; bibliotecas IJK/FFmpeg muestran vídeo/RTSP y procesan medios.

```text
Pantallas (com.record.dv)
  ├─ alta: QR ssid/pass + cambio a Wi-Fi de cámara
  ├─ conexión / live view / controles / galería local
  └─ eventos Android ← broadcasts del SDK
          ↓
W18DVToolApi + singleton p011c.e (coordinador)
  ├─ descubrimiento/callbacks JNI y sesión SID
  ├─ adaptador V3       → IOControl binario, puertos 192.168.100.1
  ├─ adaptador V316     → IOControl binario, puertos 192.168.10.1:8082
  └─ adaptador MSTAR01  → HTTP Config.cgi + UDP estado, 192.168.1.1
          ↓
libaw_net_client.so + libipcamera.so / IOTC / AVAPIs
          ↓
dispositivo de cámara y endpoints locales de medios
```

El diagrama representa rutas observadas en código estático; las líneas hasta el firmware/native no son una captura de tráfico.

## Componentes Android propios

- `com.record.dv.MainActivity`: contenedor principal, registra callback de conectividad Wi-Fi y navega entre conexión, álbum local y acerca de.
- `com.record.dv.apC.AllContext`: `Application`, inicializa `WifiManager` y `W18DVToolApi`.
- `com.record.dv.atv.ScanAtv`: lector QR Huawei ScanKit. Extrae `ssid:[...]` y `pass:[...]` y devuelve ambos valores por Intent.
- `com.record.dv.atv.StartActivity`: launcher.
- `com.record.dv.atv.CamConAtv`, `CamConQZAtv`, `CamConYZAtv`: pantallas de cámara/conexión por variantes; relaciones exactas pendientes.
- `com.record.dv.view.PtzJoystickView`: joystick PTZ.
- `com.record.dv.RecStatusTextView`: widget de estado de grabación.
- `com.record.dv.wto.ScrollingLineScanView`: widget de escaneo.

## SDK y estado compartido

- `com.weapons18.api.W18DVToolApi`: API de alto nivel, operaciones sobre dispositivo, reproducción, lista/descarga de archivos, recursos y callbacks.
- `com.weapons18.api.DVCtrlApi`: interfaz de control para dispositivos MSTAR01 (métodos enumerados en `symbol-map.md`).
- `com.weapons18.api.v`: bridge JNI ofuscado. Carga `aw_net_client` e `ipcamera`; entrega callbacks de native al listener Java.
- `p011c.e`: singleton/coordinador; inicialización, estado de dispositivo, sesión, selección del adaptador por familia, IOControl, broadcasts y medios.
- `p011c.c`: listener de callback JNI; interpreta descubrimiento/conexión y despacha respuestas.
- `p036h.b`: metadatos de firmware/dispositivo y descargas de menús/strings locales desde la cámara.
- `p011c.d`: dispatcher de eventos del SDK.

La selección observada al iniciar el coordinador es `V3 → selector JNI 0 (AW/IOTC)`, `V316 → 1 (TUTK)` y `MSTAR01 → 2 (ruta local/RTSP)`. La evidencia cruza `p011c.e.k()` y `v.yt(int)` con las ramas nativas de `jni_initNetClient` y `jni_startIpcamStream`; aún falta confirmar cada ruta en un dispositivo.

## Flujos confirmados parcialmente

### Escaneo y conexión

1. `ScanAtv` decodifica QR y devuelve SSID/contraseña de Wi-Fi.
2. El coordinador inicializa `v`, instala listener JNI y llama `st(application)`.
3. Callback de búsqueda `12322` se decodifica en UID, campo tipo dirección y puerto según variante; el código llama `cd(uid, "12345")`.
4. Callback `12289`: SID negativo significa error; SID no negativo se guarda como sesión activa.
5. Se crean adaptadores por firmware y comienza actualización de estado/ajustes/archivos.

La llamada `sed()` no realiza por sí sola el escaneo: crea una vez el consumidor nativo `messageListThread`, que drena la cola de mensajes y reenvía eventos a `W18CallbackFunc`/listener Java. El disparador de búsqueda reside en la inicialización del stack de transporte o en su biblioteca y continúa pendiente.

La secuencia exacta de acciones de UI/Wi-Fi entre pasos 1 y 2 está pendiente de trazar en Activities/Fragments.

### Live view y control

- El SDK pide frames por JNI y usa IJK/FFmpeg; se observa soporte de URLs `rtsp://` y whitelist FFmpeg RTSP/TCP/UDP/RTP.
- Controles de firmware se envían como IOControl `(sid, command, payload, length)`; IDs vistos por familia están en `protocols/iocontrol.md`.
- MSTAR01 consume HTTP local para propiedades/acciones y UDP para estado asíncrono.

### Galería y ficheros

El SDK sincroniza bases de datos SQLite servidas por la cámara y obtiene thumbnails/media por HTTP local; direcciones por familia están en `protocols/overview.md`. Está pendiente documentar consultas de DB, nombres de directorio y descarga completa.

## Seguridad / datos que no deben aparecer en logs públicos

- El QR contiene credenciales Wi-Fi y el código las almacena temporalmente en campos/textos UI.
- El modelo imprime `password='12345'` y el callback usa `"12345"` como segundo argumento de `cd`. Su papel no está probado.
- El UID de cámara y datos locales pueden ser identificadores sensibles. Redactar antes de guardar capturas o logs.

## Esqueleto clean-room reconstruido

La nueva implementación vive separada del material del APK y replica solo contratos respaldados por evidencia:

```text
app (org.hdvcam.app)
└── MainActivity                 interfaz de laboratorio offline y trazas
core (com.hdvcam.core)
├── DeviceFamily                 V3/V316/MSTAR01 → selector 0/1/2
├── Evidence                      confirmed / partial / simulated / not-implemented
└── TraceEvent, TraceRecorder    historial local de acciones
protocol-aw (com.hdvcam.protocol.aw)
└── AwDiscoveryRequest            codificación de 80 bytes del discovery observado
protocol-mstar01 (com.hdvcam.protocol.mstar01)
├── Mstar01Http         composición URL Config.cgi
└── Mstar01StatusParser          pares clave=valor de la fixture UDP
protocol-tutk (com.hdvcam.protocol.tutk)
└── TutkDiscoveryContract        límites medidos; sin reimplementar SDK nativo
```

No se han integrado DEX, código JADX, smali ni bibliotecas nativas de la app de referencia. Transporte, persistencia de dispositivos, sesión/auth, codecs y reproducción permanecen fuera del esqueleto hasta reconstruir y probar sus contratos. La tabla de trazabilidad está en `docs/traceability.md`.
