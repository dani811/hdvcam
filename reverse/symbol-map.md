# Mapa de símbolos y alias de análisis

Los alias descriptivos de esta tabla son nuestros nombres para retomar el análisis; no son nombres reales recuperados del APK. Rutas de fuente se refieren al árbol temporal `/tmp/hdvcam-jadx/sources`.

| Alias de análisis | Símbolo original | Responsabilidad / evidencia | Estado |
|---|---|---|---|
| AppMain | `com.record.dv.MainActivity` | Contenedor de navegación y callback de conectividad Wi-Fi. | Confirmado |
| AppInitializer | `com.record.dv.apC.AllContext` | Application, obtiene WifiManager e inicializa `W18DVToolApi`. | Confirmado |
| QrWifiProvisioning | `com.record.dv.atv.ScanAtv` | Parsea QR `ssid:[...]`, `pass:[...]`; devuelve valores en Intent. | Confirmado |
| CameraConnectionScreen | `com.record.dv.atv.CamConAtv` | Pantalla de cámara; flujo fino pendiente. | Pendiente |
| DeviceSdkFacade | `com.weapons18.api.W18DVToolApi` | API pública de alto nivel: conexión, control, stream, medios. | Confirmado |
| DeviceControlContract | `com.weapons18.api.DVCtrlApi` | Contrato de opciones/control cámara. | Confirmado |
| NativeBridge | `com.weapons18.api.v` | Carga bibliotecas y declara bridge JNI; nombres abreviados. | Confirmado |
| NativeMessageQueue | `libipcamera.so: messageListThread` (RVA `0x82960`) | Hilo nativo consumidor de cola; reenvía mensajes a `W18CallbackFunc`/listener Java. | Confirmado por AArch64 |
| AwLanDiscovery | `libaw_net_client.so: CNetManage::search` / `UdpBroadCast::SendFindMsg` | Búsqueda local IPv4 broadcast; solicitud UDP 80 bytes, puerto destino 21600. | Confirmado por AArch64 |
| TutkLanDiscovery | `libaw_net_client.so: tutk_searchDevice` | Llama `IOTC_Lan_Search` con timeout 1900 ms; transforma el buffer de resultados. | Confirmado por AArch64 |
| DeviceCoordinator | `p011c.e` | Singleton y estado global; configura conexión, callbacks y adaptadores. | Confirmado |
| NativeCallbackRouter | `p011c.c` | Decodifica callback JNI 12322/12289 y distribuye eventos. | Confirmado |
| SdkEventDispatcher | `p011c.d` | Dispatch de mensajes y creación de adaptadores tras conexión. | Confirmado |
| DeviceMetadataLoader | `p036h.b` | Descarga menús y strings UI desde el dispositivo. | Confirmado |
| ControlMstar01 | `p026f.c` | `Config.cgi`, control de vídeo, hora, tarjeta SD y estado UDP. | Confirmado |
| MstarUdpWorker | `p026f.b` | Un receptor UDP MSTAR01 en el puerto 49142 (buffer 1024); procesa estados y keepalive HTTP cada ~2 s. `run()` recuperado con baksmali. | Confirmado por smali |
| ControlV3 | `p026f.d` | Inicialización y mensajes binarios V3; comando 40977 para hora. | Confirmado parcial |
| ControlV316 | `p026f.e` | Inicialización y mensajes binarios V316; comando 1599 para hora. | Confirmado parcial |
| DeviceModelBase | `p005b.a` | UID, SID, dirección, puerto y estado; `toString()` fija literal password. | Confirmado |
| LiveVideoWidget | `p017d.f` (relación parcial) | Estado/view del stream, callbacks de frames. | Inferido |

## Métodos del contrato `DVCtrlApi`

Agrupación semántica por nombre; los retornos/códigos siguen por catalogar:

- Sesión/dispositivo: `launch`, `abort`, `changeCam`, `changeMode`, `resetDevice`, `syncDeviceTime`, `setKeepAliveThreadOpen`.
- Grabación/captura: `isRecording`, `isCapturing`, `getRecordingTime`, `getCaptureTime`, getters/setters de captura automática, por movimiento y temporizada, loop, lapse.
- Imagen/vídeo: exposición, resolución por modo, orientación, balance de blancos, frecuencia de luz, marca de tiempo, sensor, luz indicadora, sonido, salvapantallas, modo actual.
- Conectividad/almacenamiento: `getWifiOnOff`, `setWifiOff`, `getBatteryInfo`, `formatDeviceCard`, tamaños tarjeta, borrar archivo, modo RTSP.
- Valores genéricos: `getSetValue`, `setSetValue`; objetos JSON/strings para opciones de menús.

## Próximas relaciones caller → callee

- `ScanAtv` resultado → pantalla que pide onboarding → conexión Wi-Fi.
- `W18DVToolApi.initTool` → `p011c.e.c(Application)` → `v.st(Application)`.
- descubrimiento nativo → `p011c.c.callbackMsgRtn(type=12322)` → `v.cd(uid, ...)`.
- conexión nativa → `callbackMsgRtn(type=12289)` → `p011c.e` → `p011c.d` → adaptador según familia.

El callback 12322 se interpreta en el clean-room `core/NativeDiscoveryRecord`: V3 (UID[64], dirección[16], port LE @80), V316 (UID[21], dirección[16], port LE @37), orden leído directamente por `p087r1.f.h(byte[],offset)` en Java. El helper AW `Tools::BBytes2Short` es big-endian para campos del paquete UDP y no debe trasladarse a este callback. Es parseo de fixture/contrato; los stacks AW/TUTK y sus paquetes reales aún no están implementados.
- evento UI JSON → `p026f.c` o `p026f.d/e` → HTTP/IOControl → callback/UI.
| MainTabs | `MainActivity` → `p112w3.u`, `p112w3.p`, `p112w3.C0609b` | Navegación connection / local album / about enlazada mediante los IDs `connectionFragment`, `localAlbumFragment`, `aboutFragment`. | Confirmado por JADX |
| WifiQrOnboarding | `p112w3.u` → `com.record.dv.atv.ScanAtv` | Comprueba Wi-Fi/permisos y abre QR; ScanAtv devuelve extras ssid/password (request 321, result 123). | Confirmado parcial; siguiente paso de conexión pendiente |
| SplashLauncher | `com.record.dv.atv.StartActivity` | Launcher con vista de inicio y demora 1500 ms antes de callback de navegación. | Confirmado |
| LocalAlbum | `p112w3.p` | Fragment de álbum con ViewPager2, modos/categorías de media y acciones sobre elementos. | Confirmado parcial |
| AboutScreen | `p112w3.C0609b` | Muestra versión `1.23.20261012`, transferencia, soporte y enlace web. | Confirmado |
| LocalRouteClassifier | `G3.a.a(Context, true)` + `B3.d` | IP local `.1.1 → MStar`, `.10.1 → QZ`, `.169.1 → YZ`; crea adaptador chip y se refleja en Activity. No es el selector JNI V3/V316/MSTAR01. | Confirmado por JADX |
| LocalCameraScreens | `CamConAtv` / `CamConQZAtv` / `CamConYZAtv` | Pantallas de control con layouts propios por chipset local; QZ integra `PtzJoystickView` y `BatteryView`. | Confirmado parcial; event handlers por mapear |
