# Mapa funcional de la aplicación original

Estado: análisis estático JADX + manifiesto de apktool, leído desde `/tmp/hdvcam-jadx/sources` y `/tmp/hdvcam-apktool/AndroidManifest.xml` el 2026-10-30. El árbol temporal puede desaparecer; este mapa guarda nombres, relaciones y límites, no código decompilado.

## Componentes Android del producto

| Componente | Papel observado | Estado de reconstrucción |
|---|---|---|
| `com.record.dv.apC.AllContext` (`Application`) | Inicializa WifiManager y la fachada `W18DVToolApi` | pendiente |
| `com.record.dv.atv.StartActivity` | Launcher/splash; muestra `activity_start_atv`, espera 1500 ms | pendiente |
| `com.record.dv.MainActivity` | Actividad principal, consentimiento de privacidad y navegación inferior | pendiente |
| `com.record.dv.atv.ScanAtv` | Escáner Huawei ScanKit; interpreta SSID/contraseña del QR y devuelve resultado | solo especificación; no incorporar credenciales reales a trazas |
| `com.record.dv.atv.CamConAtv`, `CamConQZAtv`, `CamConYZAtv` | Actividades de conexión/cámara para variantes | relaciones entre variantes pendientes |
| `com.record.dv.atv.SkipTransferAtv` | Flujo indicado por `startType=14` desde Acerca de | semántica pendiente |
| `com.record.dv.atv.OtherActivity`, `JjlGreActivity` | Pantallas auxiliares registradas en el manifest | propósito y rutas pendientes |
| `com.record.dv.RecStatusTextView`, `view.PtzJoystickView`, `util.BatteryView`, `wto.ScrollingLineScanView` | Widgets de estado/controles/escaneo | comportamiento pendiente |

El manifest también registra dos servicios de FileDownloader y proveedores/servicios de Huawei/AndroidX. Son dependencias auxiliares; todavía no se reconstruyen.

## Navegación de `MainActivity`

La activity mantiene tres fragmentos y tres IDs de navegación inferior:

| ID | Clase de implementación | Lectura funcional |
|---|---|---|
| `connectionFragment` | `p112w3.u` | conexión / onboarding Wi‑Fi-cámara |
| `localAlbumFragment` | `p112w3.p` | álbum local, ViewPager2 y categorías/medios |
| `aboutFragment` | `p112w3.C0609b` | versión, transferencia/soporte y enlaces |

El mapa se verificó siguiendo el array de fragmentos registrado por `MainActivity` y el tipo de cada clase. Los nombres de paquetes/clases ofuscados se preservan como símbolos de referencia; no deben convertirse en nombres de producto de la app nueva.

## Alta Wi‑Fi a partir de QR

1. El usuario accede al flujo de conexión desde `p112w3.u`.
2. La pantalla comprueba Wi‑Fi activo y permisos (cámara, estado/cambio Wi‑Fi y ubicación); si faltan, muestra el diálogo correspondiente.
3. Abre `ScanAtv` (request code 321). El escáner Huawei extrae patrones `ssid:[...]` y `pass:[...]`.
4. Si ambos valores existen, devuelve extras `ssid` y `password`, result code 123, y cierra tras ~1 s.
5. El `p112w3.u` procesa el resultado y coordina el paso siguiente. El flujo exacto de cambio a la red de cámara y el puente a descubrimiento nativo requieren terminar el seguimiento de callbacks.

Los pasos 1–4 están sustentados por las clases JADX leídas; el paso 5 sigue parcialmente abierto. No guardar el QR ni su contraseña en fixtures, logs o trazas compartidas.

## Contrapartida limpia prevista

La app nueva no usará los nombres ofuscados ni copiará layouts/código del APK. Se pretende modelar el producto como módulos independientes:

```text
app
├── onboarding: permiso → escáner QR → datos Wi-Fi efímeros
├── home: navegación Connection / Local Album / About
├── connection: familia → descubrimiento → sesión → live view
├── controls: ajustes, grabación, captura, PTZ, batería
├── media: catálogo, preview, descarga y reproducción
└── diagnostics: eventos correlacionados y export de trazas saneadas
core: Device, DeviceFamily, Session, AppEvent, Evidence, Trace
protocol-aw / protocol-tutk / protocol-mstar01: transportes aislados y testeables
```

Solo `core` y los primeros contratos offline de protocolo están creados en esta fase. Los nombres de módulos anteriores son un diseño de destino, no una afirmación de comportamiento ya implementado.

## Evidencia fuente y cobertura

Se leyó directamente el código fuente temporal de `MainActivity`, `StartActivity`, `ScanAtv`, `p112w3.u`, `p112w3.p`, `p112w3.C0609b` y el AndroidManifest de apktool. Esos archivos están fuera del repositorio, por lo que codebase-memory no puede verificar cobertura para ellos; afirmaciones no cubiertas se mantienen explícitamente como pendientes. No se añadió ningún fichero decompilado al repo.

## Asociación a la red de cámara (análisis nuevo desde smali)

La coroutine `p127z3.f0` recibe SSID/contraseña del resultado QR y delega a un singleton Wi‑Fi (`Y3.g`). El comportamiento depende de Android:

- **API < 29:** busca configuraciones existentes por SSID; construye `WifiConfiguration` con PSK, `hiddenSSID=true` y WPA-PSK, y primero intenta actualizar/activar una red existente. Si falla, elimina y recrea la configuración; si no existía, la añade y activa. Devuelve un booleano al flujo.
- **API ≥ 29:** crea `WifiNetworkSpecifier` con SSID y passphrase; solicita red Wi‑Fi con `INTERNET` retirado y capacidades `TRUSTED` y `NOT_RESTRICTED`; `NetworkCallback.onAvailable` llama `bindProcessToNetwork()` y entrega éxito, `onUnavailable` informa fallo y desregistra callback.
- **Después:** si el resultado es true, espera ~1 s y llama `h0.m()` para avanzar a la etapa siguiente; si es false, detiene el indicador de carga. El destino de `h0.m()` a partir de ahí sigue en curso.

El código usa credenciales QR para configurar/conectar una red local de cámara. El detalle de WPA está confirmado por los bits de `WifiConfiguration`, pero no se debe asumir que todas las cámaras usan ese modo. Esta ruta no se ha ejecutado contra hardware y no se implementará en el prototipo hasta modelar cancelación, liberación de callback, pérdida de red, permisos y selección de transporte por API.

### Salto después de asociarse a la Wi‑Fi

`h0.m()` ejecuta una coroutine de transición (`p127z3.g0`). La lectura JADX muestra estas acciones observables:

- activa el estado de carga;
- espera resultados de dos flujos/estados (`p059m.a.f(h0)` y `G3.a.f1549b`), con un resultado de conexión `W3.d`;
- si falla, muestra un mensaje de conexión y vuelve a lanzar el selector Wi‑Fi;
- si progresa, invoca el helper `p127z3.j0.a(...)`, consulta otro estado y navega según el tipo de `G3.a.f1549b`: `p063m3.G → CamConAtv`, `p074o3.x → CamConYZAtv`, `p069n3.I → CamConQZAtv`.

La lógica exacta de los estados `W3.d`, `p059m.a` y el origen de `G3.a.f1549b` falta por resolver. Se conserva la relación de despacho observada sin asignar nombres semánticos inventados a esos tipos.

### Botones de conexión

El botón principal `butGotoCon` (`p112w3.u`) desactiva temporalmente el botón y comprueba los predicados de red `p059m.a.m/n/o`: si ya se reconoce una red de cámara llama `h0.m()` para continuar; si no, abre `android.settings.WIFI_SETTINGS` con request code 665. `conskdNea` también abre Wi‑Fi Settings. `conBuTSadac` comprueba Wi‑Fi y permisos de cámara/ubicación/estado/cambio Wi‑Fi y abre `ScanAtv` (request 321), o solicita los permisos necesarios.

#### Selector de pantalla por IP de ruta local

`G3.a.a(context, true)` consulta `Y3.g.b().b()` y clasifica el IP de ruta obtenido. La tabla `B3.d` identifica la familia local; el factory crea el adaptador de cámara y `p127z3.g0` elige la Activity:

| IP de ruta observado | Caso del adaptador | Activity seleccionada |
|---|---|---|
| `192.168.1.1` | `MStar` | `CamConAtv` |
| `192.168.10.1` | `QZ` | `CamConQZAtv` |
| `192.168.169.1` | `YZ` | `CamConYZAtv` |

Esta es una clasificación por ruta local de tres variantes; no debe confundirse con el selector JNI `V3/V316/MSTAR01 → 0/1/2`, que el coordinador SDK usa para elegir stack de transporte.

### Inicialización del preview

#### Detección activa de la réplica (2026-10-30)

La réplica prueba las respuestas de control MStar/QZ/YZ en la `Network` Wi-Fi seleccionada y clasifica por respuesta; el gateway de cada interfaz ordena su primera sonda, pero los fallos no impiden probar las demás familias. MStar requiere `device_name` y configuración RTSP, QZ una respuesta RTSP 2xx utilizable para continuar (401 corta el autodescubrimiento como servicio que exige credenciales), e YZ JSON válido de producto/medios con URL RTSP. “Entrar en vídeo” prueba redes Wi-Fi activas; si no hay respuesta HTTP, ejecuta discovery AW por broadcast 21600/receive 21601 y ofrece UID/IP. Al volver de Ajustes vuelve a sondear. La sesión AW usa TCP/6666, índice 0, UDP local 6669 y peer remoto `6669+selector`; el loopback verifica retorno UDP con sockets reales. RTSP por familia valida `OPTIONS` y Media3 usa `Network.getSocketFactory()` con RTP/TCP.

Si las sondas HTTP no clasifican la red, la réplica envía discovery AW: consulta de 80 bytes al broadcast UDP 21600, escucha de 21601 por 2,2 s, parseo de respuesta 0x115 y deduplicación por UID. Tras seleccionar UID vuelve a verificarla, conecta TCP/6666, negocia `0x110/0x111`, prepara UDP/6669, envía `0x1ff` tras Surface/listener y reensambla media hacia `MediaCodec`. Esta ruta AW clean-room tiene prueba loopback de sockets; el ACK/primer H.264 y estabilidad no están validados con cámara física.

Este código llega a intentar reproducir; no acredita primer frame ni estabilidad sin cámara conectada. Tampoco emula SID/sesión JNI ni transporte TUTK para V3/V316.

La UI original no comienza con una URL RTSP aislada: `p127z3.U.y(FrameLayout)` inicializa el reproductor IJK `E3.d` y conserva su referencia; `p127z3.G.f()` lee `G3.a.f1549b.m().f3663d` y se la pasa al reproductor. `p127z3.U.p(...)` registra el ViewModel ante el controlador de familia e inicia la carga asíncrona de estado/configuración.

Para YZ, `p074o3.Q` forma la base `http://192.168.169.1/app/`; su método `S(endpoint)` ejecuta GET. La coroutine de inicialización de `p074o3.x` consulta `getproductinfo`, luego `getmediainfo` (lee `rtsp`), `getsdinfo` y la propiedad `all`. En el arquetipo, `initializeYz` hace las dos primeras llamadas para recuperar el producto y la dirección; las consultas SD/propiedad se necesitan para completar estado y preferencias de UI, no para obtener el endpoint de vídeo.

### Conexión del cliente local MSTAR01 (smali verificado)

`p011c.e.k()` es el punto de entrada del flujo local del SDK. Para firmware `MSTAR01` llama `v.yt(2)`, crea un `p005b.b` local con SID `0`, emite `W18_TYPE_CONNECT_DEV_SUCCESS` (`12289`) y manda mensaje interno `3`; no llama `jni_connectDevice`. Luego `p011c.d.handleMessage(3)` procesa el éxito y entra en `p026f.c.n()` (ruta de refresco MSTAR01). El controlador `p063m3.G.e()` carga `Camera.Preview.*`, consulta `device_name` y extrae URL RTSP/cámara/fuentes; `G.m()` forma la dirección local final. En V3/V316 `k()` sí activa búsqueda por el hilo `2` y conexión por IOControl. Así que la apertura local MSTAR01 depende de HTTP de control y configuración, mientras las otras familias usan los estados de conexión del stack SDK.

Historial: las sondas iniciales de MStar/YZ solo comprobaban respuestas HTTP y QZ consultaba `192.168.10.1:8082/`; ese método se sustituyó por validación actual de `device_name` + configuración RTSP, `getproductinfo/getmediainfo.info.rtsp` y respuesta `OPTIONS` del endpoint RTSP QZ. La conexión aún no recrea el modelo de sesión/eventos SDK ni equivale al callback `12289` del APK. La red se pasa por `Network` a cada operación y no se modifica el binding global del proceso.
