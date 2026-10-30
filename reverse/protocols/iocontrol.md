# JNI e IOControl binario

## Frontera JNI observada

`com.weapons18.api.v` carga `aw_net_client` y `ipcamera`; métodos nativos ofuscados incluyen `cd`, `dd`, `dt`, `gd`, `gn`, `it`, `pd`, `sa`, `sac`, `sam`, `sbm`, `sda`, `sed`, `sp`, `spb`, `spbm`, `spm`, `ss`, `ssm`, `st`, `sv`, `vii`, `vr`. Nombres descriptivos aún desconocidos.

La tabla dinámica de `JNI_OnLoad` se extrajo de `libipcamera.so` arm64 en RVA `0x589018`: registra 11 natives en `com/weapons18/api/v`. Emparejamientos confirmados por firma, nombre Java y dirección del wrapper:

| Java | Firma | Wrapper nativo |
|---|---|---|
| `it` | `(String, int) -> String` | `jni_initNetClient` (RVA 0x81f7c) |
| `dt` | `() -> int` | `jni_deInitNetClient` (0x81c98) |
| `cd` | `(String, String) -> int` | `jni_connectDevice` (0x81cec) |
| `dd` | `(int) -> int` | `jni_disConnectDevice` (0x81ddc) |
| `sed` | `() -> int` | `jni_searchDevice` (0x81c04) |
| `sac` | `(int, int, byte[], int) -> int` | `jni_awSendIOCtrl` (0x81e08) |
| `ss` | `(int, String) -> int` | `jni_startIpcamStream` (0x8227c) |
| `ssm` | `(int) -> int` | `jni_stopIpcamStream` (0x81f00) |
| `st` | `(Object) -> int` | `jni_initApplication` (0x825a8) |
| `vii` | `(long, int, int) -> void` | `jni_initVideoView` (0x81ee8) |
| `vr` | `(long) -> void` | `jni_renderVideoView` (0x81ef8) |

El bridge nativo incluye más funciones exportadas (p.ej. audio) que no están en esa tabla registrada por la clase `v`; podrían entrar por otra clase/tabla o no ser utilizadas por este build.

## Selección del transporte en el bridge

El código AArch64 de `jni_connectDevice` lee un selector global y tiene dos ramas reconocibles: estado `0` llama al símbolo importado `connectDevice`; estado `1` llama `tutk_connectDevice`; otros valores devuelven `-1`. El primer argumento se convierte desde `jstring` a UTF-8 y el segundo también, así que Java entrega UID + la cadena de autenticación observada `12345` a una de esas capas.

El start-stream tiene ramas coherentes: estado `0` usa `startIpcamStream`, estado `1` `tutk_startIpcamStream`; estado `2` inicializa decodificador local y llama al parser RTSP incluido en `libipcamera.so`.

En `libaw_net_client.so`, `tutk_connectDevice(uid,password)` recibe el índice de sesión como primer argumento y guarda ambas cadenas en el slot correspondiente; acepta tres slots (0–2) y crea el worker en RVA `0x123e4`. El worker obtiene un session ID con `IOTC_Get_SessionID()`, llama `IOTC_Connect_ByUID_Parallel(uid, sessionID)`, comprueba el resultado con `IOTC_Session_Check` y lanza un hilo auxiliar. Después llama `avClientStart` con session ID IOTC, usuario `admin`, contraseña embebida `123456` y timeout `20000`; si retorna un AV index no negativo lo guarda en el slot y emite callback de conexión `0x3001`/12289, y ante error emite `0x3002`/12290. El segundo argumento guardado por el wrapper (`password`, desde JNI observado como `12345`) no aparece en las llamadas de conexión visibles de este worker; no se concluye que sea irrelevante en otras rutas. El wrapper no recibe IP ni consume un registro de `tutk_searchDevice`: conecta por UID a través de IOTC. Estos argumentos son estáticos del APK; no prueban conectividad ni autenticación válida de una cámara.

Para iniciar medios, `tutk_startIpcamStream(sid)` envía primero IOType `0x00ff` con un payload de dos bytes cero y luego `0x01ff` con ocho bytes cero; tras aceptar ambos, crea el worker receptor en RVA `0x12bec`. El worker llama `avRecvFrameData2` usando el AV index almacenado y un buffer de datos de `0x4b000` bytes; la llamada aporta además punteros auxiliares, un literal `16` y un puntero adicional en stack (la semántica completa de cada salida no se atribuye sin header compatible). El frame recibido se entrega al callback JNI de vídeo quitando 16 bytes del comienzo. No asumir que ese prefijo sea idéntico al header AW: solo está confirmado el mismo offset al callback del decoder. Estas observaciones provienen del wrapper AArch64, no son validación de cámara.

### Selección confirmada por familia

El coordinador `p011c.e.k()` invoca `v.yt()` con un valor fijo según el tipo de firmware: `V3 → 0`, `V316 → 1`, `MSTAR01 → 2`. `v.yt(int)` llama `it(packageName, int)`, registrado como `jni_initNetClient`, de modo que el segundo argumento es el selector nativo persistido en el estado global del bridge.

En `jni_initNetClient`, selector `0` inicializa `initNetClient` y registra su callback; selector `1` hace lo mismo con `tutk_initNetClient` y el registro TUTK. La rama `2` no llama a ninguno de esos inicializadores en el código observado: prepara la tabla de callbacks/utilidades para el camino local. Correlacionado con el coordinador, esto asigna AW/IOTC a V3, TUTK a V316 y el modo local/RTSP a MSTAR01. No se ha observado una conexión física ni tráfico.

La rutina registrada para el `pthread` de `jni_searchDevice` es `messageListThread` (RVA `0x82960`), no el escáner. Este hilo espera en una cola con mutex/condición, extrae mensajes y los entrega mediante `postMessageToApp` → callback estático Java `v.W18CallbackFunc` → `W18JniCallbackListener.callbackMsgRtn`. Al apagarse limpia la cola y desacopla el hilo de la JVM. `p011c.d` recibe el mensaje de control 2 y llama `v.sed()`; `p011c.e.k()` programa ese mensaje tras inicializar el selector de transporte, y el handler lo reenvía cada 500 ms hasta 20 veces si no se ha marcado fin de búsqueda. `W18DVToolApi.searchDevice()` prepara/descarga recursos de UI, pero no se encontró una llamada directa a ese método en los árboles JADX/smali. Por tanto el ciclo de sondeo está identificado, pero su primer disparador de producto sigue pendiente.

`jni_deInitNetClient` apaga el flag del consumidor, destruye el mutex y delega la limpieza al stack AW (selector 0) o TUTK (selector 1). El estado 2 no usa esas rutinas de deinit en esta rama.

### Callback Java de descubrimiento y conexión

`p011c.c.callbackMsgRtn(sid, addrBytes, type=12322, payload, len)` decodifica un layout distinto por `devType`; el UID y la dirección vienen del payload, no del nombre de SSID/gateway.

| Familia | UID | Dirección | Puerto | Conexión posterior |
|---|---:|---:|---:|---|
| V3 | bytes 0–63 (UTF‑8, trim) | 64–79 (UTF‑8, trim) | bytes 80–81, little-endian | guarda modelo y llama `v.cd(uid, "12345")` |
| V316 | bytes 0–20 | 21–36 | bytes 37–38, little-endian | guarda modelo y llama `v.cd(uid, "12345")` |
| MSTAR01 | no usa callback 12322 para su conexión local | — | — | construye `p005b.b` con SID 0 |

UID vacío detiene búsqueda y emite `W18_TYPE_SEARCH_OVERTIME` (`52993`). En callback `12289`, SID negativo se transforma en fallo `12290`; SID no negativo se almacena en el modelo y se emite conexión exitosa. El callback 12322 obtiene su puerto con `p087r1.f.h(byte[],offset)`, que calcula `b[offset] | b[offset+1]<<8` (little-endian). Separadamente, el parser del paquete AW usa `Tools::BBytes2Short` big-endian. `core/NativeDiscoveryRecord` implementa el layout Java; sus pruebas usan bytes sintéticos, no capturas reales. No implementa búsqueda AW/TUTK, callbacks JNI ni handshake.

La inspección inversa de AArch64 confirma que `searchDevice` (export en `0xb1dc`) salta a `NCsearchDevice`, que pasa el singleton `CNetManage*`, buffer de salida y `maxCount` al método `CNetManage::search`. Este método ejecuta `UdpBroadCast::SendFindMsg()`, espera 2 segundos y copia el registro de dispositivo desde el offset interno `+0x122` a un slot de salida de `0x54` bytes si el contador de resultados es positivo. El receptor `UdpBroadCast::Recv_Local` llama `CNetManage::addSearchDev(ip_origen, payload_en_+0x7c)`. En `addSearchDev`, el primer argumento se copia como clave a `+0x122` (UID) y el segundo, IP origen, a `+0x162`; la identidad se deduplica comparando el UID. Esto prueba que al menos 64 bytes desde `+0x7c` del datagrama forman el UID NUL-terminated (la rutina usa `strcpy`), que `protocol-aw/AwDiscoveryResponse` extrae junto a la IP. `CNetManage::search` devuelve 0x54 bytes de memoria del registro `(UID, IP, timestamp, estado...)`; no se ve un puerto en ese slot. No conocemos aún la semántica restante del datagrama ni el camino separado que crea el callback Java 12322 con puerto. No hay xrefs de `tutk_searchDevice` dentro de `libaw_net_client.so`: sigue siendo un export llamado desde fuera de esa biblioteca, y el llamador del wrapper SDK aún no aparece en Java/smali.

### Conexión TCP AW y negociación UDP (estático, AArch64)

`NCconnectDevice(uid,password)` vuelve a llamar `SendFindMsg`, exige que la UID coincida con el registro descubierto, toma la IP almacenada y llama `CNetManage::ConnectDevice(uid,6666,password)`. `TcpClient::ConnectBy` abre TCP a esa IP/puerto, obtiene el índice de sesión devuelto por `CNetManage::addConInfo` y lo pasa como tercer argumento a `sendLogin`. En el primer slot `addConInfo` devuelve 0. `sendLogin` escribe ese índice (byte bajo) en body+128; no escribe el puerto TCP. El frame mide 137 bytes: header `AB CD 00 81 00 00 01 10` (body 129, tipo `0x110`), `admin\0` en body+0 y literal `12345\0` en body+64. El argumento `password` llega a `sendLogin`, pero el serializador observado usa el literal estático `12345`.

`UdpClient::init(CNetManage*,0)` llama `create_UDP_socket(NULL, 6669, 0)`. En esa rutina, flag 0 toma la rama `bind()`; por tanto el primer slot localiza el socket UDP del móvil en puerto 6669. La rama alternativa con flag no cero hace `connect()` y no es la llamada de esta inicialización. Al procesar login `0x110`, el servidor copia el byte de índice del cliente y configura su peer hacia la IP TCP del cliente en `6669 + índice`; luego responde con `0x111` y su índice/selector en body+128. Al procesar el ACK `0x111`, el cliente construye el peer UDP con la IP de la cámara y puerto `6669 + selector`, y lo guarda en `UdpClient::setAddr`. Así, el primer slot usa local UDP 6669 y el peer remoto de cámara `6669+selector`. `handleMsg` marca conexión activa y emite `0x3001` (`12289`). No se observa comparación de `body+64`; no se concluye que el servidor ignore autenticación globalmente. La réplica y el test loopback siguen sin demostrar aceptación en hardware.

`UdpClient::create_UDP_socket` aplica `fcntl(F_SETFL,0x800)` y configura `SO_SNDBUF` (`SOL_SOCKET`, opción 7) y `SO_RCVBUF` (opción 8) a `0x100000` bytes. En la rama de recepción estos `setsockopt` se hacen tras `bind`; el clon ahora solicita 1 MiB para `DatagramSocket` y registra el tamaño efectivo informado por Android. El valor efectivo puede estar limitado por el kernel del teléfono y debe verificarse allí; la prueba loopback del host obtiene al menos 1 MiB. El clon conserva recepción bloqueante con timeout en hilo dedicado, no el `fcntl` no bloqueante nativo.

Esto cierra el primer handshake TCP a nivel de estructura. `CNetManage::sendMesg` (RVA `0xef8c`) construye el sobre de 8 bytes (`AB CD`, longitud BE16, tipo BE32) y encola mensajes para `processOUTMsg`; `handleMsg` despacha por tipo. Una petición `0x112` recibe `0x113` vacío. `AwIoControlFrame` implementa ese sobre; el comando `0x1ff`/`0x2ff` se usa para iniciar/detener vídeo.

`startIpcamStream(sid, url)` envía `awSendIOCtrl(sid, 0x1ff, ocho_ceros, 8)`; `stopIpcamStream` envía tipo `0x2ff` con el mismo payload. `CNetManage::sendMesgOut` para `NETCORE_TYPE=0` transmite estos frames por TCP; UDP queda para media. El sobre IOControl es `ABCD + len16BE + type32BE + payload`; una petición `0x112` recibe `0x113` vacío. `UdpClient::init` liga el socket local a `sid+6669`, y `create_UDP_socket` usa una rama `bind` para esta llamada. La otra rama de esa función acepta `connect`, pero no es la que selecciona `UdpClient::init`. Tras login/ACK, el cliente usa peer UDP `<IP-cámara>:6669+selector`. `AwTcpSession` ahora reproduce bind local 6669 y conecta al peer derivado del ACK; el loopback valida el envío de un datagrama desde el puerto seleccionado al puerto local. El APK también mantiene un `UdpClient` distinto de discovery en 21601/21600.

El datagrama media tiene 8 bytes: magic fijo `0xBCDE` BE16, longitud BE16, secuencia BE16, byte 6 y marcador byte 7. `UdpClient::SendVideoData` copia el SID almacenado en `UdpClient+0x74` al byte 6 y actualiza un contador BE16 persistente por datagrama en `+0x49c`; `SendAudioData` también copia SID, pero su contador comienza en cero dentro de cada llamada y aumenta en continuaciones. `Recv_Local` no consulta byte 6: usa el marcador. El marcador `2` inicia y `1` continúa; entrega cuando el fragmento actual mide <1000 bytes (1000 continúa). Inicio corto completa; continuación puede cerrar desde buffer de longitud cero. Audio usa `4`/`3`, límite 256. El marcador `5` llama `updateHeartTime` y sale antes del comparador de secuencia, sin afectar el buffer. Para el resto de datagramas, compara la secuencia con la global anterior (diferencia modular esperada 1), notifica pérdida pero sigue ensamblando. `AwFrameHeader`/`AwMediaFragment` guardan byte 6 como `sessionIndex`; `AwFrameAssembler` ignora ese byte y `AwMediaSequenceTracker` observa una secuencia compartida audio/vídeo, ignorando heartbeat. Frames solo se envían al decoder si el payload tras el prefijo tiene start code y tipo NAL plausible; sigue pendiente confirmar con captura que sea Annex-B.

La ruta AW no invoca `avClientStart2`/`avRecvFrameData2` desde `startIpcamStream`; esos imports corresponden al flujo `tutk_*`. Así, no se debe reemplazar el camino AW por el handshake AV/TUTK sin evidencia.

El literal `"12345"` se ha observado como argumento al bridge. No se debe tratar como clave universal ni incluir en trazas del arquetipo. Los campos extraídos tampoco prueban el mecanismo de cifrado/autenticación ni que una cámara acepte la conexión.

## Sesión de vídeo del SDK

`jni_startIpcamStream` (RVA `0x8227c`) confirma las ramas del bridge: selector 0 prepara `init_video_dec(channel,JNIEnv,URL,...)` y llama al export `startIpcamStream`; selector 1 inicializa decoder con flag adicional 0 y llama `tutk_startIpcamStream`; selector 2 llama `GetStringUTFChars`, invoca `startRtspVideoStream(env, JavaString, url)` y libera el string. `jni_stopIpcamStream` empareja con `destroy_video_dec` + `stopIpcamStream` (AW), `destroy_video_dec(...,0)` + stop TUTK, o `stopRtspVideoStream` (local). `jni_initVideoView(handle,width,height)` delega a `ogl_display_init`, y `jni_renderVideoView(handle)` a `ogl_display_render`. `W18DVToolApi.initVideoView(context, relativeLayout, width, height)` requiere vista y dimensiones positivas; crea el renderer IJK 16:9, zoom 1x, desplazamiento 0 y caché `shotcut`. `startVideoStream(url)` retorna `-3` si falta dispositivo/SID válido, `-8` si no se inicializó la vista y `-9` si ya está activo. Para V3/V316 marca el stream activo y llama `v.ss(sid, "")`; para MSTAR01 requiere URL no vacía y `DVCtrlApi.isRtspDisable()==false`, luego llama `v.ss(sid,url)`. `stopVideoStream()` delega al controlador y en bridge se asocia con `ssm(sid)`. Esto confirma el orden sesión → vista → stream y los límites nativos, pero no revela todavía el contrato de frames de IJK/OpenGL ni pruebas con dispositivo.

La tabla dinámica de `libipcamera.so` conserva símbolos C++ útiles. `JNI_OnLoad` registra los wrappers listados arriba; los nombres Java cortos se enlazan con sus firmas y direcciones en la tabla de este documento.

`libaw_net_client.so` exporta una capa de nombres explícitos: `searchDevice`, `connectDevice`, `disConnectDevice`, `awSendIOCtrl`, `awSendVideoData`, `awSendAudioData`, `startIpcamStream`, `stopIpcamStream`, `startIpcamAudio`, `stopIpcamAudio`, `startIpcamSpeeker`, playback start/pause/stop y `awHttpUploadFile`. Imports incluyen `IOTC_Connect_ByUID_Parallel`, `IOTC_Lan_Search`, `avClientStart2`, `avSendIOCtrl`, `avRecvIOCtrl`, `avRecvFrameData2`, `avSendAudioData` y `avRecvAudioData`. Esto prueba que el wrapper usa la pila IOTC/AVAPI y ofrece datos/media/audio, pero no revela los servidores/puertos efectivos ni cifrado.

Desensamblado AArch64: `startIpcamAudio(sid)` manda IOControl `0x300` con 8 bytes cero y crea el worker `0x12174`; este lee el AV index de la ranura y consulta `avCheckAudioBuf`, y cuando no supera el umbral espera 500 ms antes de volver. `stopIpcamAudio(sid)` manda `0x301`. `startIpcamSpeeker(sid)` manda `0x350`, actualiza una bandera por SID y `stopIpcamSpeeker(sid)` manda `0x351` y limpia esa bandera. La dirección probable es audio cámara→cliente para `IpcamAudio` y micrófono→cámara para `IpcamSpeeker`, inferida por los nombres; el sentido efectivo y el caller de producto requieren confirmación. El framing UDP de audio usa marcadores `4`/`3`, fragmentos máximos de 256 y cierre con fragmento <256; el reensamblador clean-room ahora tiene pruebas sintéticas del límite. `avClientStart2` aparece en un worker separado y no en los workers `0x12bec` (vídeo) ni `0x12174` (audio) ya trazados.

`libipcamera.so` contiene exports independientes `AudioDecodeInit/Frame/UnInit` y `AudioEncodeInit/Frame/UnInit`. El decoder y encoder piden codec ID `0x1100b` (G.726 en la tabla FFmpeg del host); el encoder configura 8 kHz, mono (`channel_layout=4`, canales=1) y `bit_rate=16000`. La rutina solicita formato de muestra interno 2; el resto del contrato de muestras y bitrate del decoder no está resuelto. El escaneo de instrucciones no encontró llamadas directas a estas rutinas; ningún otro `.so` del APK las importa, y `JNI_OnLoad` no las registra. Así que son capacidad disponible en la biblioteca, no prueba de que el protocolo AW o la pantalla original las use.

## Descubrimiento LAN AW (estático)

En `libaw_net_client.so`, export `searchDevice` (RVA `0xb1dc`) salta a `NCsearchDevice`, que llama `CNetManage::search` (RVA `0xecbc`). Esa función ejecuta `UdpBroadCast::SendFindMsg` y espera hasta dos segundos; si hay respuesta válida, copia el identificador/dispositivo y la IP encontrada al buffer proporcionado. La búsqueda deduplica entradas por identificador y renueva su marca de tiempo.

El `NETCORE_TYPE=0` AW crea por conexión un `UdpClient` local: `UdpClient::init(sid)` pasa a `create_UDP_socket` el puerto `sid + 4096 + 0xa0d = sid + 6669`; el primer slot `sid=0` liga UDP local a `6669`. `CheckLogin` asigna el peer remoto al puerto `6669 + selector_ACK`. El `NETCORE_TYPE=1` alternativo configura el receptor de broadcast local en `21601` y emite búsqueda a `21600`. La dirección de destino se obtiene consultando interfaces locales (ioctl `SIOCGIFCONF`/`SIOCGIFBRDADDR`); el envío usa el broadcast de interfaz y puerto UDP `21600`. No es el puerto 49142 del worker Java MSTAR01: son protocolos/rutas distintos.

Cabecera observada del datagrama de búsqueda (80 bytes enviados; offsets de 0):

| Offset | Bytes/campo | Evidencia |
|---:|---|---|
| 0 | `AB CD` | `Short2BytesB(-0x5433)` |
| 2 | `00 48` | `Short2BytesB(0x48)` |
| 4 | `00 00 01 14` | `Int2BytesB(0x114)` |
| 8..79 | ceros | buffer inicializado y enviado con longitud `0x50` |

El socket receptor valida magic `0xABCD`. Mensaje tipo `0x114` se filtra como control; tipo `0x115` obtiene la IPv4 origen y un UID NUL-terminated de hasta 64 bytes desde offset `0x7c`, y los guarda como registro AW (UID, IP). `protocol-aw/AwDiscoveryResponse` reproduce esa extracción, pero todavía se desconoce la estructura adicional del datagrama y cómo el resultado nativo se convierte al callback Java 12322 (que incluye un puerto). `searchDevice` es un export de la biblioteca; el wrapper JNI registrado `jni_searchDevice` solo inicia `messageListThread`. Falta demostrar qué llamada del SDK invoca el export de búsqueda.

## Descubrimiento LAN TUTK (estático)

El export `tutk_searchDevice` recibe (buffer de salida, maxCount), reserva `0x1f8` bytes y llama `IOTC_Lan_Search(temp, maxCount, 0x76c)` (timeout 1900 ms). Toma `min(resultado, maxCount)` registros; cada registro fuente mide `0x2a` bytes y cada destino `0x54`, copiando bytes 0–41 y dejando el resto del slot sin inicializar aquí. El SDK valida en `IOTC_Lan_Search` que puntero/cantidad/timeout son positivos, exige que el stack IOTC esté inicializado, prepara un query asíncrono y llama `iotc_convert_to_IPv4_addr` sobre cada registro válido. En esta build ese helper está compilado como stub de 8 bytes (`mov w0,0; ret`), por lo que no transforma los registros. `tutk_connectDevice(uid,password)` guarda ambas cadenas y crea el worker de conexión en `0x123e4`; este pide SID IOTC, conecta por UID, llama `avClientStart(admin,123456,timeout=20000)` y emite callback 12289 si obtiene AV index válido (12290 ante fallo). No consume el registro de discovery ni una IP. Java selecciona `V316 → selector 1` e inicializa TUTK, pero no aparece caller Java/smali directo de `tutk_searchDevice`; por tanto el wrapper SDK de discovery sigue sin resolverse.

La implementación AArch64 de `IOTC_Lan_Search` confirma que la búsqueda no es el broadcast AW: limpia `maxCount * 42` bytes, repite el envío aproximadamente cada 50 ms hasta cubrir el timeout, y recibe los registros en el estado global del SDK. El constructor interno alterna dos formas de consulta: la primera lleva longitud `0x34` y comando `0x0601`; las posteriores llevan longitud `0x44` y comando `0x0603`. Sus datagramas comienzan `04 02` e incluyen el byte `0x14`; no están identificados los nombres/semántica de los campos restantes. La tabla de 42 bytes no se pudo asociar de forma fiable con el UID/IP/puerto del callback `12322`, así que la réplica conserva el registro como opaco y todavía no implementa el transporte TUTK.

Usos Java confirmados:

- `st(application)`: inicialización con contexto.
- `cd(uid, "12345")`: conexión después del descubrimiento.
- `sac(sid, commandId, payload, length)`: IOControl genérico.
- `ss(sid, url)` / `ssm(sid)`: aparecen al iniciar/detener stream en `W18DVToolApi` (interpretación probable por contexto, falta mapear a definición nativa).
- `sed()`: se invoca durante reintentos/búsqueda.

## V3

Mensajes observados en `p026f.d`:

| ID | Payload/uso observado |
|---|---|
| 41022 | valor 1 durante inicialización |
| 41012 | consulta inicial sin payload visible |
| 41002 | consulta/configuración sin payload visible |
| 40977 | hora local, 36 bytes / nueve enteros de 32-bit |

En 40977 el orden de campos escritos es segundo, minuto, hora, día, mes, año y tres ceros. Serialización por `f.c(int)`; endianness no confirmado.

## V316

Mensajes observados en `p026f.e`:

| ID | Payload/uso observado |
|---|---|
| 1636 | consulta/inicialización |
| 1607 | llamada helper sin datos |
| 1619 | 8 bytes; entero 8 seguido de cero |
| 1599 | fecha/hora en 32 bytes: año, mes, día, cero, hora, minuto, segundo, cero |
| 1617 | payload de 8 bytes en otro setter |

Los primeros tres o cuatro bytes reciben la serialización de `f.c(int)` y el resto se rellena con ceros; endianness sin confirmar.

## Siguiente análisis

Seguir en `libaw_net_client.so` y `libtutk` el origen concreto de búsqueda/callback tras `initNetClient`/`tutk_initNetClient`; localizar constantes de puertos/servidores y asociar mensajes de callback con Java. No convertir los IDs anteriores en comandos de una herramienta hasta entender sus payloads/respuestas.


### AW media callback y decoder JNI

`libipcamera.so` declara dependencia ELF directa de `libaw_net_client.so` e importa `initNetClient`, `registerNotificationHandler`, `startIpcamStream`, `awSendIOCtrl` y sus variantes TUTK. En `jni_initNetClient`, después de inicializar AW, arma el objeto global `g_callback` y llama `registerNotificationHandler(-1, g_callback)`; selector TUTK registra el mismo objeto con `tutk_registerNotificationHandler(-1, g_callback)`. Sus tres miembros son, en orden, `handleNetCmd` (RVA `0x81b88`), `handleRecvVideoData` (`0x816c4`) y `handleRecvAudioData` (`0x81920`). `handleNetCmd` coloca eventos en una cola protegida por mutex y despierta el worker `messageListThread`, que los reenvía por el callback Java. El wrapper AW guarda el objeto en `CNetManage` y registra sus handlers NetCore; `tutk_registerNotificationHandler` lo asigna a los slots de sesión TUTK. En AW, `UdpClient::Recv_Local → CNetManage::pushVideoData` llama la ranura `+8` con `(sid, frame+16, len-16, frame, 16, flags)`, donde `flags` es el sexto argumento derivado del estado del manager; audio usa ranura `+16`. En TUTK, el worker de `tutk_startIpcamStream` invoca `avRecvFrameData2` y entrega el frame por la misma ranura `+8`/`handleRecvVideoData`, con metadatos de frame, offset `16` y flag booleano. Así ambas familias convergen en la cola del decoder nativo, aunque sus transportes son distintos. `AWNetwork::init`/`awSendVideoData → NCSendVideoData → CNetManage::SendVideoData` es además una API de envío separada; no confundirla con el callback de recepción. `init_video_dec` prepara colas/buffers y worker; `handleRecvVideoData` valida H.264 Annex-B (NAL 5/7/8), mientras `android_ogl_display_set_yuv` pasa planos al renderer. La réplica clean-room ya aplica ensamblado, corte de 16 bytes y decoder; quedan por verificar en cámara los bytes reales, configuración de codec y primer frame.

La réplica clean-room usa ahora el callback Java del transporte AW propio → cola UDP reensamblada → cola de decoder acotada a 8 access units → `MediaCodec` AVC → `SurfaceView`. El callback del receptor solo hace el enqueue; la configuración y alimentación de `MediaCodec` corre en un hilo dedicado para no frenar la lectura UDP. Si la cola se satura, se vacía y el decoder descarta hasta el siguiente IDR. Las pruebas unitarias cubren saturación/resincronización y cierre de cola; no miden pérdidas ni continuidad de un stream real. El assembler sigue en el transporte equivalente de `libaw_net_client`, no en la ruta JNI TUTK. Hay que validar con cámara si el payload entregado es Annex-B/AVC, si los límites coinciden con access units válidas y si el tamaño de cola da estabilidad suficiente.

### PTZ QZ recuperado desde JADX

`PtzJoystickView` emite direcciones cardinales. `CamConQZAtv` mapea `LEFT/RIGHT/FLIP/RESET/DOWN/UP` al enum `p069n3.L` y llama `S(L)`. La coroutine `p127z3.P` invoca `p069n3.I.s0(L)`; `p063m3.d0.s0` serializa `L.ordinal()` como `par=0..5` y ejecuta `POST http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=<n>` con body vacío. Mientras se mantiene la dirección, el widget repite cada 100 ms; al soltar deja de enviar y no se observa comando stop separado. La réplica muestra siempre la cruceta habilitada; mapea izquierda 0, derecha 1, abajo 4 y arriba 5. Código estático confirma endpoint/payload; el paneo mecánico aún requiere cámara QZ.
