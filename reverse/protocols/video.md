# Vídeo en directo y estabilidad

Estado: reconstruido estáticamente desde JADX; aún no se ha conectado a una cámara ni se ha medido fluidez real.

## Rutas de URL observadas

| Caso | Construcción observada | Confianza / límite |
|---|---|---|
| `p069n3.I` | `rtsp://<IP local>:8554/ch00` | Endpoint generado; IP procede del estado/configuración local (`d0.f8920h`) |
| `p063m3.G` | `rtsp://<host>/liveRTSP/av` + propiedad `Camera.Preview.RTSP.av` vía `d0.Y(...)` | Host/path base observados; falta recuperar propiedad final y su origen |
| `p074o3.x` | devuelve el campo almacenado `f9700i` | Fuente que rellena el campo pendiente |

### Resolución por familia recuperada de JADX

| Familia de pantalla | Cómo obtiene la URL | URL/base observado |
|---|---|---|
| MStar / `192.168.1.1` | GET `Camera.Preview.*`, toma `Camera.Preview.RTSP.av`, concatena el valor a `d0.Y(...)` | `rtsp://192.168.1.1/liveRTSP/av` + sufijo devuelto |
| QZ / `192.168.10.1` | `p069n3.I` genera URL local fija | `rtsp://192.168.10.1:8554/ch00` |
| YZ / `192.168.169.1` | GET `/app/getmediainfo`, lee `info.rtsp` del JSON | URL entregada por el dispositivo |

En YZ, el arranque del controlador (`p074o3.x`, coroutine `C0567c`) consulta en orden `getproductinfo`, `getmediainfo`, `getsdinfo` y una propiedad `all`. La dirección se conserva en `x.f9700i`; `x.m()` entrega ese valor al reproductor. Para abrir el preview basta recuperar y validar el campo RTSP, mientras el resto alimenta información/estado UI y no se considera requisito de transporte en este arquetipo. El cliente HTTP concatena los nombres directamente bajo `http://192.168.169.1/app/` y usa GET.

En MStar (`p063m3.G.e()`), la inicialización consulta `Camera.Preview.*`, obtiene `device_name`, extrae `Camera.Preview.RTSP.av`, `Camera.Preview.Source.1.Camid` y `Camera.Preview.Source.Totals` (si este último falta/viene vacío usa `1`). `d0.Y(sufijo)` forma `rtsp://192.168.1.1/liveRTSP/av` + sufijo; `G.m()` entrega la URL al player. El arquetipo conserva esos campos en `MstarStartup` y usa el RTSP validado.

En QZ (`p069n3.I.m()`), el controlador devuelve directamente `rtsp://192.168.10.1:8554/ch00`; no se observa un endpoint HTTP de resolución de URL.

### Comando de grabación del botón central

`p127z3.U.t(start)` llama `G3.a.f1549b.z(modo,start)`; los handlers del controlador recuperados en smali usan estos transportes:

| Familia | Petición observada | Réplica |
|---|---|---|
| MStar | GET `Config.cgi?action=set&property=Video&value=recordon|recordoff` | Enviada por `CameraCommandDispatcher` |
| QZ | POST `http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=1100&par=1|0` con body vacío | Enviada por `CameraCommandDispatcher` |
| YZ | GET `/app/setparamvalue?param=rec&value=1|0` | Enviada por `CameraCommandDispatcher` |

Las tres llamadas son sobre la Wi‑Fi local. La réplica actual trata HTTP 2xx como aceptación de transporte para actualizar el icono; la confirmación de grabación debe venir del estado/evento del dispositivo (`action=rec`, callbacks equivalentes) y aún no se conecta.

### Captura de foto: contratos recuperados

| Familia | Evidencia estática | Estado en la réplica |
|---|---|---|
| MStar01 | `G.c(boolean)` ignora el booleano y hace GET `Config.cgi?action=set&property=Video&value=capture` mediante `d0.v0` | Conectado al botón central al seleccionar cualquier modo de foto; 2xx solo acredita respuesta HTTP |
| QZ | `I.C(event)` construye el JSON `{"cmd_id":"4016"}` y lo manda por el objeto `Ln3/f` ya abierto | No enviado: el APK confirma payload/canal existente, pero aún no están identificados el transporte ni su puerto de inicialización |
| YZ | `x.c(boolean)` obtiene `capability`, guarda `value` y rechaza una variante si el carácter 8 vale `1`; no aparece un comando de captura en ese método | No enviado: el disparo sigue sin resolverse |

El flujo de captura normal/ráfaga se traduce a `ActionOneShot`, el temporizado a `ActionOneShotTimer` y el automático a `ActionAutoCaptureStart/Stop`. Eso identifica la semántica UI, pero todavía no cierra cómo temporizador, ráfaga y captura automática se descomponen en comandos por firmware. La réplica solo dispara MStar; no presenta una orden ficticia para QZ/YZ.

La réplica implementa esta resolución por familia en `CameraStreamResolver` y usa Media3 RTSP con RTP interleaved por TCP. Es una ruta de reproducción clean-room distinta al IJK del original. `CameraActivity` registra sonda OPTIONS, endpoint saneado, primer frame renderizado, resolución/rotación, buffering, errores, reintentos, frames descartados (`AnalyticsListener`) y ventanas de reproducción; persiste una traza de máximo 1 MiB por intento en `filesDir/traces/stream-*.log`. Aún no contiene mediciones reales de cámara.

Política actual de recuperación del clon (decisión propia, no atribuida al APK): watchdog comprueba cada segundo, vuelve a resolver/reprobar RTSP si no llega el primer frame en 12 s desde `prepare/play`, o si hay un hueco mayor de 3 s entre callbacks de procesamiento de vídeo. Los reintentos continúan con backoff de 1, 2, 4, 8, 16 y hasta 30 s; una ventana continua de 60 s reinicia el backoff. Registrar traza `rtsp-watchdog`, causa y hueco medido. Este umbral debe calibrarse con cámaras reales para evitar reconexiones durante buffering legítimo; aún no se ha medido.

El FFmpeg embebido de `libipcamera.so` incluye solicitudes SETUP para ambos perfiles: `RTP/AVP/TCP;unicast;mode=receive;interleaved=%d-%d` y `RTP/AVP/UDP;unicast;mode=receive;source=%s;client_port=%d-%d;server_port=%d-%d`. El demuxer pide DESCRIBE, exige HTTP-like 200, parsea SDP y luego negocia SETUP; los datagramas `$` interleaved se manejan en `ff_rtsp_tcp_read_packet`. En la ruta de cámara observada, `E3.d` inicializa `f1357a=true`; el perfil normal llama `a()`, y el perfil 8K llama `c()`, donde ambos activan `rtsp_transport=tcp`. Por tanto el reproductor usa TCP en los dos perfiles recuperados. Media3 también fuerza RTP/TCP, alineado con esa evidencia; faltan contrastar las cabeceras RTSP reales y el comportamiento de buffering/decoder con hardware.

Antes de entregar el endpoint a Media3, el arquetipo envía un `OPTIONS <URI> RTSP/1.0` por el `Network` de cámara y requiere que la primera línea sea una respuesta `RTSP/...`. Es una sonda de diagnóstico de transporte/protocolo propia de la réplica; no se ha confirmado que el APK original emita `OPTIONS` antes del player. Quedan por capturar hardware real el código de respuesta, cabecera `Public`, `DESCRIBE/SETUP/PLAY`, primer frame, rotación y estabilidad.

Para identificar QZ, el clon usa el endpoint RTSP fijo observado (`192.168.10.1:8554/ch00`) y requiere una línea de estado RTSP bien formada. Se retiró el probe anterior al puerto HTTP 8082, que aceptaba cualquier código 200–499 y podía clasificar una página genérica como cámara. La sonda distingue `RTSP/1.0 401`: la interfaz informa que hay cámara QZ con RTSP protegido y no abre preview; la autenticación queda por resolver. OPTIONS/PLAY autorizado y primer frame siguen pendientes de hardware.

No se deben combinar estas variantes como si fuesen un único endpoint. El selector de pantalla por gateway (`MStar/QZ/YZ`) tampoco es el selector JNI de transporte (`V3/V316/MSTAR01`).

## Reproductor `E3.d`

Usa `TextureView` e `IjkMediaPlayer`, llama `setDataSource(url)` y `prepareAsync()`. El constructor fija `f1357a=true`, por lo que `a()` y el perfil 8K `c()` seleccionan RTSP sobre TCP. Perfil normal observado:

| Grupo | Opciones |
|---|---|
| Demux/buffer | `fflags=nobuffer`, `analyzemaxduration=5000`, `flush_packets=1`, `probesize=AV_CH_TOP_FRONT_LEFT` |
| Player | `start-on-prepared=1`, `packet-buffering=0`, `infbuf=1`, `framedrop=30`, `fps=30`, `fast=1`, `no_delay=1` |
| Codec | `flags=low_delay`, `skip_loop_filter=0`, `mediacodec=0`, auto-rotate/resolution-change de MediaCodec desactivados |
| Red/cola | `dns_cache_clear=1`, `http-detect-range-support=0`, `max-buffer-size=AV_CH_SIDE_RIGHT`, `min-frames=3`, `max_cached_duration=3000`, `rtsp_transport=tcp` |

El camino especial cuando el caso es `p063m3.G` y un campo de perfil contiene `8K` cambia valores: `framedrop=3`, `max_cached_duration=100`, `infbuf=0`, `an=1`, `render-wait-start=0`, `find_stream_info=0`, `reorder_queue_size=0`, `max_delay=100000`, `probesize=AV_CH_SIDE_RIGHT`, `analyzeduration=500000`, `fpsprobesize=0`, `buffer_size=262144`, manteniendo transporte RTSP TCP y low delay. Algunos enteros aparecen ofuscados como constantes IjkMediaMeta; hay que verificar valores byte/unidad contra la versión exacta de IJK antes de reproducirlos literalmente.

El arquetipo usa `device_name` de MStar para seleccionar `VideoStreamProfile`: `8K` aplica 100 ms y el resto 3000 ms. Media3 recibe esos máximos mediante `DefaultLoadControl.setBufferDurationsMsForStreaming(100,max,100,100)`. Es una aproximación al `max_cached_duration`/`min-frames` del APK; el control de carga Media3 no reproduce las colas internas de FFmpeg/IJK y requiere medición con cámara para calibrar latencia y estabilidad.

## Ciclo de vida y recuperación

- `b(url)` prepara el origen asíncronamente y arranca el watchdog.
- Si se detectó al menos un frame y no llega otro durante más de 3000 ms, el watchdog para/reinicia el player con la misma URL.
- Antes del primer frame existe otra ruta de reintento cuando el estado sigue activo.
- `d()` detiene, resetea y elimina callbacks; al separar la vista se libera el player.
- callbacks diferencian primer frame (`info=3`), buffering (`701/702`), live/no seek (`801`), rotación (`10001`) y error.

## Backend nativo del APK

El wrapper `jni_startIpcamStream` de `libipcamera.so` (RVA `0x8227c`) vuelve a separar los selectores: `0 → init_video_dec + startIpcamStream` (AW/IOTC), `1 → init_video_dec + tutk_startIpcamStream`, `2 → startRtspVideoStream` con URL UTF-8. El stop usa `destroy_video_dec` más stop AW/TUTK, o `stopRtspVideoStream` en modo 2. Los handles/dimensiones de vista se pasan a `ogl_display_init` y los ciclos de render a `ogl_display_render`; la función OpenGL configura viewport, textura y shaders, y protege buffers con mutex. `init_video_dec` crea un worker con `pthread_create`, reserva 0x4b000 bytes de buffer por bloque y bloques de control de 0x428 bytes por canal; el layout interno de paquetes aún no está identificado. El puente `android_ogl_display_set_yuv` recibe tres punteros/planos más ancho y alto, llama `ogl_display_set_dec_out_size` y `ogl_display_set_yuv_to_display`, y envía el primer YUV por el callback de plataforma `0xd012`; `0xd010` enlaza el display y `0xd011` lo destruye. El formato de color/strides exactos de esos planos y callback de frame hacia Java aún faltan. La réplica Media3 no reproduce este backend nativo.

### Ruta de vídeo AW en la réplica (sin validar con cámara)

El callback nativo `handleRecvVideoData(channel, data, len, original, offset, flags)` alimenta una cola por canal; el decoder busca NAL H.264 SPS/PPS/IDR en offsets de start code de 3/4 bytes. `jni_initNetClient` registra en `libaw_net_client` el objeto de callbacks `g_callback`; su segundo miembro es la dirección exacta de `handleRecvVideoData`. `pushVideoData` recorta `frame+16`, longitud `len-16`, y llama la ranura vtable `+8` con seis argumentos, incluido el `flags` del estado del manager. En TUTK, `tutk_startIpcamStream` arranca un worker que recibe con `avRecvFrameData2` y llama la misma ranura con offset 16 y un flag calculado; `jni_initNetClient` registra en TUTK el mismo objeto. El tercero (`handleRecvAudioData`) corresponde a `pushAudioData`/ranura `+16`. Las familias convergen en el decoder, no en el transporte. El clon reproduce el recorte y la ruta hasta MediaCodec con fixtures; aún falta captura física para comprobar que el frame reensamblado, su prefijo y los parámetros in-band son los que emite una cámara.

La réplica entrega frames reensamblados por `AwTcpSession` a `CameraActivity`; el modo AW crea `SurfaceView` y alimenta `MediaCodec` AVC. El formato se configura a partir de SPS/PPS in-band, sin resolución fija. El diagnóstico separa datagramas y frames de vídeo, bloques ensamblados de audio, access units encoladas y buffers de salida liberados hacia `Surface`, con resolución reportada por MediaCodec. Los buffers liberados no constituyen por sí solos verificación visual del usuario; falta observar una cámara autorizada y comparar con la imagen esperada. Quedan por confirmar con hardware el formato exacto del UDP, límites entre access units, perfil/resolución, timestamps, primer frame visible, orientación y estabilidad. No se considera operativo el vídeo AW hasta esa prueba.

En Java, `p011c.c.callbackMsgRtn` traduce eventos del backend de vídeo: `53264` habilita renderer y marca stream activo; `53265` deshabilita renderer, limpia pantalla y marca stream inactivo; `53266` envía mensaje de “primer frame mostrado”. `p017d.e` implementa `GLSurfaceView.Renderer`: `onSurfaceChanged` guarda dimensiones y programa `v.svi(width,height)`; en `onDrawFrame` renderiza con `v.svr()`, y si una captura quedó solicitada lee framebuffer por `glReadPixels`. `W18DVToolApi.getShotCut()` exige SID válido, vista inicializada y stream activo antes de marcar esa captura; el archivo es PNG bajo `shotcut` y se notifica con broadcast `W18_TYPE_SHOTCUT`. Estos eventos dan un primer-frame observable para la réplica conceptual, pero los IDs no se emiten desde Media3 y la estabilidad debe medirse aparte.

Orientación: `CamConAtv`, `CamConQZAtv`, `CamConYZAtv` declaran `configChanges` con `orientation|screenSize`, sin orientación fija en el manifest. El botón fullscreen alterna a `setRequestedOrientation(6)` (landscape) y restaura la anterior/portrait. El ajuste de imagen `Image_rotation` es una preferencia diferente y para V3/V316 llama IOControl `41046` con el índice del catálogo; para MStar01 el setter retorna `-15` (no soportado). Así no debe confundirse el giro del teléfono con la rotación configurada en el sensor.

La UI original no comienza con una URL RTSP aislada: `p127z3.U.y(FrameLayout)` inicializa el reproductor IJK `E3.d` y conserva su referencia; `p127z3.G.f()` lee `G3.a.f1549b.m().f3663d` y se la pasa al reproductor. `p127z3.U.p(...)` registra el ViewModel ante el controlador de familia e inicia la carga asíncrona de estado/configuración. El controlador de familia se escoge antes por `G3.a.a(Context,true)` a partir de la puerta de enlace local. La sesión/controlador propietario gobierna así qué dirección recibe el player.

La API `W18DVToolApi.startVideoStream(url)` requiere sesión válida y `initVideoView`; esta última crea el renderer IJK con relación 16:9, zoom 1x, desplazamiento cero y carpeta `shotcut`. V3/V316 marcan el stream activo y llaman al native bridge sin URL; MSTAR01 exige URL RTSP y comprueba `isRtspDisable()`. Errores observados incluyen `-3` sin sesión, `-8` sin vista, `-9` stream activo y `-11` RTSP deshabilitado. En la réplica actual, Media3 intenta abrir RTSP directamente para las tres familias sin recrear el controlador/sesión, así que no implementa aún el flujo SDK de V3/V316 ni demuestra que la cámara acepte el cliente.

## Para una validación de estabilidad reproducible

Registrar en cada ejecución: familia y firmware, URL saneada (sin credenciales), transporte, resolución, timestamps de primer frame y frames posteriores, huecos >3 s, buffering/error/retry, orientación, ancho/alto y resultado visual. Probar 10 min por combinación; declarar estable solo el caso realmente medido. El emulador no puede validar el stream de la cámara.


### Transporte multimedia AW (estático, AArch64)

El datagrama media AW usa header de 8 bytes: magic BE16 `0xBCDE`, longitud BE16, secuencia BE16, byte 6 y marcador byte 7. `UdpClient::SendVideoData` lee e incrementa un contador BE16 por `UdpClient` (campo `+0x49c`) para cada datagrama; `UdpClient::SendAudioData` inicia secuencia 0 y aumenta un contador local para sus continuaciones. Ambos escriben el `sid` del `UdpClient` (campo `+0x74`) en byte 6. Vídeo se divide en trozos máximos de 1000 bytes; marcador 2 empieza frame y 1 continúa. `Recv_Local` guarda inicio/continuadores y llama a `pushVideoData` solo si el tamaño del fragmento actual es <1000 (`cmp ..., #0x3e7; b.hi` salta entrega: 1000 continúa, 0–999 cierra). Un inicio corto completa; una continuación también puede completar desde buffer de longitud cero. En salto de secuencia ejecuta `lostPkgNotify`, pero sigue acumulando. Audio usa marcadores 4/3, límite 256 y la misma regla. El marcador 5 es heartbeat: llama `updateHeartTime` y evita tanto el comparador de secuencia como el reensamblado. El receptor compara secuencia global entre paquetes no-heartbeat, sin filtrar por byte 6. El clon refleja SID en `sessionIndex`, reconoce heartbeat/otros marcadores, reensambla sin depender de byte 6 y cuenta huecos una sola vez a través de paquetes audio/vídeo; no cuenta el primer paquete como pérdida porque establece baseline local. En cambio, el assembler clean-room descarta el bloque de frame incompleto al detectar hueco/desorden antes de aceptar el payload posterior; esta política defensiva evita concatenar datos corruptos, pero difiere del original, que llama `lostPkgNotify` y continúa. Audio aún no se decodifica; formato/códec deben confirmarse con captura. Ninguno de estos headers/payloads ha sido observado en una cámara física.


### Diferencia AW frente a TUTK

`libaw_net_client.so` muestra que `tutk_startIpcamStream` llama `tutk_awSendIOCtrl` para IOType `0x00ff` (dos bytes cero) y `0x01ff` (ocho bytes cero), marca el canal activo y crea un worker en RVA `0x12bec`. Este worker lee frames mediante `avRecvFrameData2`, pasando un buffer de `0x4b000` bytes, y llama el callback de vídeo registrado en `tutk_registerNotificationHandler`. La conexión previa de V316 crea el IOTC session ID por UID y usa `avClientStart` con `admin`/`123456`, timeout 20 s; ese AV index es el que consume el receptor. En cambio, `startIpcamStream` de AW solo transmite IOControl `0x01ff` y su export no llama `avClientStart` ni `avRecvFrameData2`; AW recibe por su flujo NetCore/UDP. Ambos transportes entregan finalmente al mismo `handleRecvVideoData`, con firma común de seis argumentos. La réplica debe conservar transportes distintos y converger al decoder H.264; el camino AW clean-room ya hace UDP → ensamblador → callback/listener → MediaCodec, y el TUTK todavía no está implementado.

### Receptor TUTK: argumentos y reintentos observados

La entrada del hilo en RVA `0x12bec` recibe como argumento el índice de slot (0–2), obtiene de esa ranura el AV index retornado por `avClientStart` y lo pasa como primer argumento a `avRecvFrameData2`. El buffer principal tiene capacidad `0x4b000` (307200 bytes); el resto de los argumentos incluyen punteros de salida auxiliares, el literal 16 y un puntero pasado en stack. Sin headers de la versión exacta de AVAPI no asignamos nombres/semántica a esas salidas. El retorno `-20012` entra en una ruta de espera/reintento de 100 ms; otros rangos negativos se comparan por separado y pueden reintentar o salir. En una recepción aceptada, el worker entrega buffer, tamaño, datos auxiliares, offset 16 y un flag derivado del retorno a la ranura callback `+8`; el consumidor común elimina ese prefijo antes de validar/encolar H.264. El umbral temporal observado de 1000 ms gobierna una rama de notificación/entrega y aún no se ha correlacionado con una política visible de estabilidad de la UI.

`tutk_startIpcamAudio` envía IOType `0x300` y crea otro worker en RVA `0x12174`. Ese hilo consulta el AV index de la ranura mediante `avCheckAudioBuf`, lee el campo de activación de audio y espera cuando el búfer no alcanza el umbral; por tanto `avClientStart2` encontrado en un worker distinto no forma parte del receptor de vídeo `0x12bec`. El worker monitor creado en la conexión tampoco está atribuido a un keepalive: su función observable incluye sondeos de 500 ms y actualización de un campo temporal. Estos nombres de función y relaciones proceden de los call sites nativos; el sentido exacto del audio y la causa del campo temporal necesitan más trazado.


`UdpClient::Recv_Local` entrega frames ensamblados a `CNetManage::pushVideoData`; call sites `0xdb50` y `0xdbc4` cubren frame corto inicial y continuado. `pushVideoData` calcula `frame+16` y `len-16`, toma el callback de `CNetManage+0x220` y llama su vtable `+8` con `(sid, payload, longitud, frame_original, 16, flags)`. `initNetClient` inicializa `NETCORE_TYPE=1`, registra callback nativo `CNetManage` en el singleton global y limpia sus slots de conexión; `registerNotificationHandler` le entrega el objeto del caller. En `libipcamera`, ese objeto es `g_callback` y su ranura `+8` es `handleRecvVideoData`; el worker TUTK también invoca esa ranura después de `avRecvFrameData2`, con offset 16 y flag. La clase exportada `AWNetwork::init` inicializa `NETCORE_TYPE=0`, registra otra tabla interna y expone métodos de envío. `AWNetwork::awSendVideoData` llama a `NCSendVideoData`, que llega a `CNetManage::SendVideoData` y transmite UDP: esto es cámara → red de salida, no la recepción de vídeo.

En `libipcamera`, `handleRecvVideoData(channel,data,len,original,offset,flags)` comprueba canal/longitud y NAL SPS/PPS/IDR, copia los datos comprimidos a una cola circular por canal bajo mutex; `init_video_dec` crea 30 slots y arranca un worker. `jni_startIpcamStream` prepara esa cola antes de `startIpcamStream`. Aunque `libipcamera` no importa `NCSendVideoData` ni `AWNetwork::awSendVideoData`, sí tiene `DT_NEEDED: libaw_net_client.so`; su `jni_initNetClient` registra `g_callback` en AW y TUTK. El segundo miembro es `handleRecvVideoData`: AW lo llama desde `CNetManage::pushVideoData` por la ranura vtable `+8`; el worker TUTK usa `avRecvFrameData2` y entrega los buffers por la misma ranura, offset 16 y flag. El tercero es `handleRecvAudioData`, usado por la ranura `+16`. Queda confirmado el puente de ambos transportes hacia las colas del decoder. La réplica clean-room usa UDP → reensamblador Java → corte de 16 bytes → cola acotada → MediaCodec. Loopback valida solo fixtures; el framing real, SPS/PPS/IDR y la imagen aún requieren cámara.
