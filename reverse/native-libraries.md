# Inventario de bibliotecas nativas

Bibliotecas de la APK arm64/armeabi-v7a; foco del reversing marcado con ★:

- ★ `libipcamera.so` (5.8 MB arm64 / 6.9 MB armv7): carga explícita desde `com.weapons18.api.v`; JNI de cámara/stream probable.
- ★ `libaw_net_client.so` (144 KB arm64 / 79 KB armv7): carga explícita junto con `ipcamera`; transporte/red.
- `libIOTCAPIs.so`, `libAVAPIs.so`: nombres de API indican stacks IOTC/AV; uso/exports por mapear.
- `libijkplayer.so`, `libijksdl.so`, `libijkffmpeg.so`: reproducción.
- `libavcodec.so`, `libavformat.so`, `libavfilter.so`, `libavutil.so`, `libavdevice.so`, `libswscale.so`, `libswresample.so`, `libmobileffmpeg.so`: medios/FFmpeg.
- `libscannative.so`: dependencia Huawei ScanKit.
- `libconceal.so`: Facebook Conceal crypto (presencia de librería no prueba uso sobre protocolo de cámara).
- `libc++_shared.so`, `libcpufeatures.so`, `libmsoptimize.so`, `libmobileffmpeg_abidetect.so`, `libxfffmpeg_mediametadataretriever_jni.so`: runtime/optimizaciones y metadata.

## Herramientas disponibles en el Mac (observadas el 2026-10-30)

`jadx 1.5.6`, `apktool 3.0.3`, Java, Python con `capstone 5.0.9`, `lief 1.0.0`, `pyelftools 0.33`. Verificar versiones al retomar porque el entorno puede cambiar.

## Secuencia sugerida

1. Extraer APK a `/tmp/hdvcam-apktool`, Java a `/tmp/hdvcam-jadx`.
2. Hacer inventario ELF por ABI: Build ID, arquitectura, secciones, imports/exports, símbolos dynsym.
3. Buscar `JNI_OnLoad`, `RegisterNatives`, strings de puertos/hosts/comandos.
4. Desensamblar solo funciones relacionadas a JNI; mapear de forma incremental a alias de `protocols/iocontrol.md`.
5. Registrar cada hallazgo con offset RVA, ABI, función y nivel de certeza. No publicar copias grandes de disassembly ni fuente derivada.

Resultados de `strings` ya sugieren logs de RTSP dentro de `libipcamera.so`, incluidos `startRtspVideoStream`, errores de demux y opción `rtsp_transport=tcp`; esto es compatible con reproducción RTSP, no revela URL/credenciales.

## Primera inspección ELF (2026-10-30)

`libipcamera.so` de ambas ABI es ELF dinámico stripped, pero retiene símbolos de exportación de JNI. La tabla `RegisterNatives` AArch64 ya está recuperada y documentada en `protocols/iocontrol.md`. RVA arm64: `JNI_OnLoad` 0x82c30, search 0x81c04, connect 0x81cec, IOControl 0x81e08, start stream 0x8227c. RVA armv7: `JNI_OnLoad` 0x61f34, search 0x61148, connect 0x6120c, IOControl 0x6131c, start stream 0x617a0.

`libaw_net_client.so` mantiene muchos exports C/C++ legibles y referencias explícitas a TUTK/IOTC/AVAPI. El selector Java quedó asociado a las familias: `V3→AW/IOTC`, `V316→TUTK`, `MSTAR01→modo local/RTSP`. AW tiene búsqueda LAN broadcast por UDP `21600/21601` con cabecera `AB CD 00 48 00 00 01 14`; TUTK llama `IOTC_Lan_Search` con timeout de 1900 ms. La rutina JNI `jni_searchDevice` resultó ser el consumidor de callbacks, no el escáner. Queda encontrar el caller efectivo de los exports de búsqueda, parsear completamente respuestas y contrastar estos hallazgos con una cámara autorizada.
