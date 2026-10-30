# Inventario de pantallas, controles y ajustes

Estado: lectura estática de layouts, bindings y código JADX del APK. Los nombres de menú son claves internas del SDK, no siempre el texto visible localizado. Los tres layouts de cámara comparten el conjunto principal de controles; QZ añade joystick PTZ y panel de reinicio.

## Pantallas de cámara

| Control / vista | Función visible inferida del layout | Estado del flujo en reversing |
|---|---|---|
| `viewSelectVideo` | Seleccionar modo vídeo | Marca vídeo seleccionado y lanza la selección de `MODE0_VIDEO` |
| `viewSelectPhoto` | Seleccionar modo foto | Marca foto seleccionada y lanza la selección de `MODE4_PHOTO` |
| `viewAction` | Acción principal según modo | `CamConAtv.f(viewId, mode)` asigna `rcd_stop_ac` a modos de vídeo y `start_pic_start` a modos de foto; el listener decide start/stop/disparo por el drawable/tag y deriva al ViewModel `U` |
| `viewSett` | Abrir ajustes de cámara | Menú construido por `B0.d`/`p081q.c`; catálogo inicial abajo |
| `viewCamFile` | Abrir archivos de cámara | APIs de lista/descarga existen; flujo de navegación pendiente |
| `viewFullScreen`, `full_action`, `full_viewSett` | Controles en modo pantalla completa | El listener alterna orientación solicitada 1/6 y la visibilidad de `viewMainBack`; hay controles fullscreen separados |
| `viewPlayerContainer` | Superficie de vídeo 16:9 o 4:3 | Player `E3.d` (TextureView/IJK); ver `protocols/video.md` |
| `picTime` | Cuenta atrás/tiempo de captura o grabación | Se actualiza desde estados de cámara; semántica por modo pendiente |
| estado batería / tarjeta SD | Indicadores de estado | API de batería y consulta SD local identificadas; unión de cada vista pendiente |
| `ptzJoystick` (QZ) | Joystick de movimiento PTZ | APK: lo muestra si `f9088i` reporta soporte y puede ocultarlo si no. Réplica: cruceta siempre visible y habilitada, con movimientos QZ cableados al comando recuperado |
| `cvResetTips` (QZ) | Aviso/panel asociado a reset | Vista confirmada; evento que lo muestra pendiente |

## Selector de modos y botón central

Los layouts `mode_layout_video.xml` y `mode_layout_photo.xml`, junto con `values-es/strings.xml`, recuperan las etiquetas españolas visibles:

| ID | Etiqueta | Ajuste relacionado |
|---|---|---|
| `mode0_rec` | Grabación | Grabación normal |
| `mode1_slow_rec` | Vídeo en cámara lenta | `video_slowp` |
| `mode2_loop_rec` | Grabación en bucle | `video_time_length` |
| `mode3_timelapse_rec` | Grabación time-lapse | `video_delayp` |
| `mode4_pic` | Tomar foto | Foto normal |
| `mode5_auto_pic` | Foto automática | `Automatically_take_pictures` |
| `mode6_continuous_pic` | Modo ráfaga | `Motion_pictures` (correlación nominal; confirmar regla de firmware) |
| `mode7_time_pic` | Foto con temporizador | `Time_take_photos` |

El enum `B3.e` confirma `MODE0_VIDEO`…`MODE7_PHOTO_TIMER` además de los estados `CASE_MODE_*`. `CamConAtv.f(viewId, mode)` reinicia iconos, marca el modo, selecciona el drawable del botón y configura `viewPlayerContainer` en `h,16:9` para modos 0–3 y `h,4:3` para modos 4–7. `e(C3.e)` consume cambios de modo del modelo. El listener central consulta el drawable guardado como tag para decidir si pulsa inicio, parada o captura.

La función estática `B3.c.F(controlador, modo, start, accepted)` convierte ordinal de modo/estado en acción `B3.a` y publica un evento `D3.i`. Para `CASE_MODE_VIDEO/SLOW/LOOP/TIMELAPSE` (ordinales 1–4), genera `ActionVideoStart` si `start=true`, en otro caso `ActionVideoStop`; `CASE_MODE_PHOTO` (5) y `CASE_MODE_PHOTO_BLURT` (7) generan `ActionOneShot`; `CASE_MODE_PHOTO_TIMER` (6) genera `ActionOneShotTimer`; `CASE_MODE_PHOTO_AUTO` (8) genera `ActionAutoCaptureStart/Stop`. Para enum `MODE*` la función tiene ramas explícitas solo en ordinals 18–23: vídeo 18–21, foto 22, auto 23. No asumir que MODE6/MODE7 alimentan este dispatcher directamente: la UI parece traducir a CASE_MODE y hace falta confirmar todos los callers.

`p127z3.U.t(boolean)` delega a `controller.z(modo, start)`: MStar consulta estado SD, escribe `Video=recordon|recordoff` por `Config.cgi` y espera 500 ms antes de publicar el resultado; QZ manda `setdeviceinfo` cmd 1100 con `par=1|0`; YZ llama `setparamvalue?param=rec&value=1|0`. Es el comando de grabación del botón principal. El setter separado es `p127z3.U.B(boolean) → controller.O(boolean)`: MStar no-op, QZ cmd 4004, YZ opción `frame_zoom`.

Estos tres comandos de grabación ya están conectados al botón central de la réplica mediante `CameraCommandDispatcher` y la red Wi‑Fi seleccionada. El estado local cambia solo con respuesta HTTP 2xx; esto acredita aceptación HTTP, no confirma el estado real de grabación hasta procesar el callback del dispositivo. Los modos de foto siguen sin comando replicado.

`U.q()` y `U.r()` son lecturas del texto de ajustes por modo, no comandos de captura: delegan a controlador `q/r`. En QZ, `q` obtiene ajustes según la tabla de modo y `r` extrae el texto mostrado. El evento de shutter llega al callback `C(event)` del controlador. Smali confirma MStar `G.c(boolean) → Video=capture`, QZ `I.C(event) → {"cmd_id":"4016"}` por el canal `Ln3/f` ya abierto, y YZ `x.c(boolean)` consulta la capacidad antes de decidir; su orden de disparo aún no está identificada. La réplica envía solo MStar, y mantiene QZ/YZ sin ejecutar hasta recuperar el transporte/payload exactos.

Layouts fuente: `activity_cam_con_atv.xml`, `activity_cam_con_qz_atv.xml`, `activity_cam_con_yz_atv.xml` en el árbol apktool temporal. El binding de ATV/QZ/YZ es `p106v3.a/b/c`.

## Catálogo de ajustes del SDK

`B0.d.run()` registra tags y tipo de control; `p081q.c` compone el catálogo desde ficheros locales suministrados/actualizados por el SDK. Al inicializar lee `lang_v3`, `menu.cfg` y `zh-CN.bin` desde el directorio del SDK. Para cada tag genera `name`, `type` y `values`; opciones usan líneas y cantidad de `menu.cfg` con traducciones de los ficheros de idioma. `WiFi` tiene tipo 2 y no se incluye como ordinal de preferencia. Así que el menú exacto depende del paquete de idioma/catálogo disponible para el firmware y se debe poblar de esas fuentes, nunca de una lista fija inventada.

`p081q.c.a(tag,index)` consulta `values[index]`; `e(tag)` devuelve cantidad/clase de opciones (forzando dos para algunos booleanos como sonido/rotación/LED/marca); `f(tag)` resuelve el índice del nombre localizado en el catálogo. En el backend MSTAR01, `p026f.c` consulta `Camera.Menu.*`, guarda key/value devueltos y calcula los valores especiales de captura (`CPhotoFEQ`, `TPhotoCountDown`, `APhotoIntervals`, `LRecTime`). `V.e.setSetValue(tag,index)` confirma el despacho al setter nativo o a `Camera.Menu` del backend. Los índices siguen siendo ordinales, no valores semánticos del protocolo.

| Tag interno | Acción SDK | Grupo funcional |
|---|---|---|
| `video_resolution` | `setModeResolutionValue(0,index)` | Resolución de vídeo |
| `record_sound` | `setRecordSoundOnOff(index)` | Audio de grabación |
| `video_delayp` | `setRecordLapseTimeValue(index)` | Timelapse/lapso |
| `video_time_length` | `setRecordLoopTimeValue(index)` | Duración/bucle de grabación |
| `video_slowp` | `setModeResolutionValue(1,index)` | Vídeo lento |
| `photo_resolution` | `setModeResolutionValue(4,index)` | Resolución de foto |
| `Time_take_photos` | `setCaptureTimerTimeValue(index)` | Temporizador de foto |
| `Automatically_take_pictures` | `setCaptureAutoTimeValue(index)` | Captura automática |
| `Motion_pictures` | `setCaptureMotionTimeValue(index)` | Captura por movimiento |
| `exposure` | `setExposureValue(index)` | Exposición |
| `white_balance` | `setWhiteBalanceValue(index)` | Balance de blancos |
| `wifionoff` | `setWifiOff()` | Wi‑Fi (la rama observada solo expone apagar) |
| `WiFi` | se excluye de preferencias en `B0.d` | Elemento especial; no tratar como ajuste ordinal |
| `Image_rotation` | `setImageRotationOnOff(index)` | Rotación |
| `light_freq` | `setLightFreqValue(index)` | Frecuencia anti-parpadeo |
| `LED_lights` | `setIndicatedLightOnOff(index)` | Indicador LED |
| `screen_switch` | `setScreenSaverValue(index)` | Apagado/salvapantallas |
| `time_water_mark` | `setTimeWaterMarkOnOff(index)` | Marca de fecha/hora |
| `video_open_ctl` | `setSensorMode(index)` | Control de modo de sensor/vídeo (etiqueta semántica incierta) |
| `sync time`, `TF card`, `format`, `reset` | tags especiales de `W18Global` / `format` | Operaciones, no preferencias ordinales |

El DAO MSTAR01 (`p026f.c`) obtiene opciones como `Camera.Menu.*`; entre los valores parseados explícitamente están `sysworkmode`, `CPhotoFEQ`, `TPhotoCountDown`, `APhotoIntervals`, `LRecTime` y `Camera.Menu.CardInfo.{LifeTimeTotal,RemainLifeTime}`. La lista completa de propiedades y traducciones aún debe extraerse de los ficheros de recursos/localización y capturas de menú.

La orientación del teléfono en fullscreen es independiente de `Image_rotation`: el listener guarda/restaura la orientación previa y usa valor Android 6 para landscape; las Activities declaran `configChanges` para no recrearse en cada giro. `Image_rotation` manda IOControl 41046 en V3/V316 (el índice exacto y su significado on/off dependen del catálogo); MStar01 no implementa el setter (`-15`).

### Paneo e inclinación PTZ QZ

`PtzJoystickView` reporta cambios entre `NONE, UP, DOWN, LEFT, RIGHT`. `CamConQZAtv` convierte la dirección a `L={LEFT:0, RIGHT:1, FLIP:2, RESET:3, DOWN:4, UP:5}` y solicita `controller.s0(L)`. `p063m3.d0.s0` genera `setdeviceinfo/?custom=1&cmd=4005&par=<ordinal>` bajo `http://192.168.10.1:8082/api/` mediante POST vacío. Al mantener una dirección, el listener reenvía el mismo comando cada 100 ms; al soltar, deja de repetir y no envía un stop separado. La réplica muestra siempre la cruceta activa y repite con ese intervalo. Payload y endpoint están recuperados estáticamente; el movimiento mecánico requiere validación en cámara QZ.

La salida del vídeo se comunica a UI por callbacks `53264` (stream activo, habilita renderer), `53265` (fin/parada y limpia estado) y `53266` (primer frame mostrado). El botón screenshot requiere sesión+vista+stream activo; `GLSurfaceView.Renderer` lee los píxeles con `glReadPixels` y guarda PNG `shotcut`.

## Estado de cobertura

La build de réplica ya recrea la navegación principal y una primera estructura de previsualización con modos y diálogo de ajustes. Los datos estáticos de modos/ajustes están tipados en `core/CameraMode.java` y `CameraSetting.java`. Aún no se han conectado los valores dinámicos de firmware, los handlers reales de cada variante ni las operaciones de cámara; la UI evita mostrar opciones inventadas como si provinieran del dispositivo.
