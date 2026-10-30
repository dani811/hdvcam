# Matriz de protocolos por firmware

| Familia | Control | Dirección local observada | Medios | Certeza |
|---|---|---|---|---|
| V3 | IOControl por bridge JNI, comandos 16-bit; inicializa status/config y fecha. | DB `http://192.168.100.1:80/data/sunxi.db` | IOTC/AV APIs + stream; esquema pendiente. | Parcial |
| V316 | IOControl por bridge JNI, comandos 16-bit; inicializa config y fecha. | DB `http://192.168.10.1:8082/tmp/data/.data/sqlite/sunxi.db`; media `/file/media` | IOTC/AV APIs + stream; esquema pendiente. | Parcial |
| MSTAR01 | HTTP `Config.cgi` y estado por UDP; contrato alto nivel `DVCtrlApi`. | `192.168.1.1`, puertos HTTP 80 usados en URLs | DB `http://192.168.1.1:80/tmp/DCF.db`; RTSP local probable, URL exacta pendiente. | HTTP confirmado; UDP parcial |

## Medios locales y galería remota de la cámara

`p112w3.p` es el álbum local del teléfono; no equivale a los ficheros de la tarjeta SD de la cámara. La galería de cámara está en `W18DVToolApi`: tras conectar, `refreshFileData()` (`p011c.e.l()`) inicializa el caché `xfdb` y descarga una base SQLite elegida por tipo de firmware. V3 obtiene `/data/sunxi.db` en `192.168.100.1:80`; V316 obtiene `/tmp/data/.data/sqlite/sunxi.db` en `192.168.10.1:8082`; MSTAR01 obtiene `/tmp/DCF.db` en `192.168.1.1:80`. El helper `K2.e` pagina los registros por `id/ID DESC` en `MediaFile100`/`DCF` y por `time DESC` en `CdrFile`.

El mapper `K2.e` construye los elementos que consume la UI. Contrato recuperado de las consultas y de `Cursor` en JADX:

| Familia / tabla | Consulta observada | Campos usados por fila | Conversión a elemento |
|---|---|---|---|
| MSTAR01 / `DCF` | `SELECT * FROM DCF ORDER BY ID DESC LIMIT <limit> OFFSET <offset>`; también filtra `type IN (0,5,6)` o `type=<n>` y ordena por `ID DESC` | índice 1: ruta media; 2: ruta `.sec`; 3: ruta thumbnail; 7: tamaño. La UI además usa `type` para filtros | `name` es basename de la ruta; `type` se infiere por extensión `JPG`/`MP4`; URL `http://192.168.1.1` + ruta; `.sec` solo para MP4; thumbnail si no vacío; tamaño del índice 7 |
| V316 / `MediaFile100` | `SELECT * FROM MediaFile100 ORDER BY id DESC LIMIT <limit> OFFSET <offset>`; consulta filtrada usa `type=<n>` (un caso de modo 2 fuerza `type=1`) | índice 2: ruta relativa; otro mapper usa columna `file`; el adaptador SQL usa `fileName`, `subFileName`, `thumbpath`, `size` y opcional `ctime` | URL `http://192.168.10.1:8082/file/media/` + ruta; extensión `.mp4` se sustituye por `.thm` y `.sec`, `.jpg` por `.thm`; JSON del API de índice 2 no incluye size en el método observado |
| V3 / `CdrFile` | `SELECT * FROM CdrFile ORDER BY time DESC LIMIT <limit> OFFSET <offset>` | índice 0: ruta; 1: tiempo (leído como `long`); 2: tipo | `name` basename; `type` pasa del valor de DB; URL `http://192.168.100.1:80/` + ruta |

El flujo también calcula `COUNT(*)` sobre la tabla activa y `getAllFilesOrderByTimeDESC()` retorna el listado completo en orden descendente. `downloadFile(remoteName, localName, targetDir)` entrega el trabajo al gestor reanudable y toma el tamaño ya descargado del mapa local. El borrado MSTAR01 delega a `DVCtrlApi`; V3 usa IOControl `41004` con prefijos `/mnt/extsd/photo/` o `/mnt/extsd/video/`; V316 normaliza ruta y usa `1828`. Esto recupera índices y transformaciones que usa el binario, no DDL completo ni significado del payload `.sec`; proviene de JADX y aún no se ha contrastado con una base o cámara real.

La réplica conserva por separado el selector del proveedor de documentos del teléfono (MediaStore no se enumera sin permiso). Esto no implementa todavía esta galería remota por firmware.

## Capa de descubrimiento

La capa común carga `libaw_net_client.so` + `libipcamera.so`, registra `W18JniCallbackListener` y recibe callback tipo `12322`. Java parsea campos binarios de respuesta y pasa UID a `cd(uid, "12345")`; callbacks `12289/12290` confirman resultado/sesión con SID. El selector de transporte usa `V3→0` (AW), `V316→1` (TUTK), `MSTAR01→2` (local/RTSP); para V3/V316 el handler llama `sed()` cada 500 ms, hasta 20 intentos, hasta que llega el callback de búsqueda o vence el timeout. El callback contiene UID/dirección/puerto, pero no se debe confundir con el registro AW LAN que se parsea desde broadcast. La ruta usa SDK/JNI y no es una especificación completa de autenticación ni cifrado.

Para AW LAN, la búsqueda nativa envía broadcast UDP al puerto 21600 y recibe localmente en 21601. La consulta de 80 bytes comienza `AB CD 00 48 00 00 01 14`; una respuesta tipo `0x115` contiene UID NUL-terminated desde `0x7c` y usa la IP de origen. `AwLanDiscovery` implementa este sobre de consulta/recepción sobre la `Network` Wi-Fi seleccionada y deduplica por UID; `MainActivity` lista los UID/IP. En primer slot el cliente abre TCP a 6666, envía login `0x110` con índice 0 en body+128 y bindea UDP local 6669. El ACK `0x111` da selector; el cliente configura peer UDP a la IP de cámara en `6669+selector`. `0x1ff` se envía tras crear la superficie/decoder y `0x2ff` al destruirla. La prueba loopback cubre ese intercambio y entrega un datagrama desde el puerto ACK al receptor local; aceptación de cámara, primer frame válido y estabilidad aún requieren hardware. Las familias TUTK/V316 y el callback propietario V3 siguen sin discovery clean-room conectado.

El wrapper `connectDevice` para AW vuelve a buscar y verifica UID, y el binario abre TCP a `<IP>:6666`. El login `0x110` tiene header de 8 bytes + 129 de payload (`admin` en 0, `12345` en 64, índice de sesión 0 en byte 128 para primera conexión). El socket UDP local enlaza 6669; el ACK `0x111` indica selector y el peer remoto cliente es `<IP-cámara>:6669+selector`. El callback nativo de éxito es `0x3001` (`12289`). La prueba loopback ahora valida login/ACK, destino remoto derivado y recepción de paquete UDP desde el puerto seleccionado, además de `0x1ff/0x2ff`; aceptación y H.264 real aún requieren cámara.

### Diferencias V3/AW y V316/TUTK

El export `tutk_searchDevice` existe dentro de `libaw_net_client.so`, llama `IOTC_Lan_Search(buffer,maxCount,1900)` y copia registros contiguos de 42 bytes a slots de salida de 84 bytes. El SDK incluido valida cada respuesta de discovery a nivel de `IOTC_Lan_Search`; luego `tutk_connectDevice(uid,password)` guarda UID/contraseña en una tabla nativa de tres slots e inicia un worker en `0x123e4`. Este pide `IOTC_Get_SessionID`, llama `IOTC_Connect_ByUID_Parallel(uid, sessionID)`, valida la sesión y llama `avClientStart(sessionID, "admin", "123456", 20000, ...)`. El retorno AV no negativo dispara `0x3001`/12289; el error dispara `0x3002`/12290. El argumento password almacenado (JNI le pasa `12345`) no se observa en esas llamadas del worker. El wrapper recibe SID como índice de slot y no consume registros de discovery. El worker de stream en `0x12bec` llama `avRecvFrameData2` con buffer de `0x4b000` bytes. La conversión `iotc_convert_to_IPv4_addr` incluida es un stub que retorna 0, el APK no incluye headers del SDK, y ningún caller `tutk_searchDevice` es visible en Java/smali. Se conserva el registro binario bruto: aún no hay parser clean-room ni UI de resultados V316.

El wrapper `tutk_startIpcamStream(sid)` acepta índices 0–2 y envía IOType `0x00ff` con dos bytes cero y luego `0x01ff` con ocho bytes cero; si ambos pasos pasan, lanza el worker en `0x12bec`. Este extrae de la ranura el AV index generado por `avClientStart`, llama `avRecvFrameData2` con buffer principal de `0x4b000` bytes y entrega el frame por el callback común con offset 16. El retorno `-20012` tiene una ruta de reintento que espera 100 ms; otros errores tienen ramas distintas que aún requieren clasificación completa. Audio usa otro worker: `tutk_startIpcamAudio` envía `0x300` y crea el target `0x12174`, que consulta `avCheckAudioBuf` sobre el AV index guardado. Un worker adicional llama `avClientStart2`, pero no es el receptor de vídeo identificado. Audio y vídeo comparten la tabla de sesión AV, pero el framing de control/retorno no es intercambiable con AW pese al callback decoder común. Evidencia: símbolos ELF y disassembly AArch64 de `libaw_net_client.so`; el protocolo de sesión y la compatibilidad real siguen sin validar.

## Mensajes de alto nivel globales

Conexión OK `12289`; fallo `12290`; desconexión `8196`; actualización de estado `53000`; refresh settings OK/fail `52997/52998`; refresh files OK/fail `52995/52996`; eventos SD `49165–49169`; eventos frame `53265–53267`/enable `53504`.

Ver detalle: `mstar01.md`, `iocontrol.md`.
