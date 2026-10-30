# Análisis inicial de `app-newCam-release.apk`

Este documento recoge observaciones estáticas preliminares. No se ha instalado ni ejecutado la aplicación.

## Identificación

- Paquete: `com.record.dv`
- Versión: `1.23.20261012` (versionCode `23`)
- SDK mínimo: 24; SDK objetivo: 36
- APK: `app-newCam-release.apk` (29 420 005 bytes)
- SHA-256: `61c61a8f101618d2d9035f2561c0f49bacc1616dd266a56f6b1e0a547d24d3cd`
- Arquitecturas: `arm64-v8a`, `armeabi-v7a`

## Hallazgos confirmados en código/manifest

- Permisos de red y Wi-Fi, cambio de conectividad, ubicación, cámara, almacenamiento antiguo y servicio en primer plano.
- Actividades principales `com.record.dv.atv.StartActivity` y `com.record.dv.MainActivity`.
- Bibliotecas nativas: `libipcamera.so`, `libIOTCAPIs.so`, `libAVAPIs.so`, FFmpeg/IJK y `libscannative.so`.
- Código Java contiene un cliente de configuración local con endpoints como `http://192.168.1.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*` y `action=set`.
- El mismo cliente construye operaciones para iniciar/detener grabación, capturar imagen, consultar estado de SD/RTSP y cambiar ajustes/fecha.
- Modelo de dispositivo `XFDevice` incluye UID, SID, IP, puerto y estado de grabación/captura. El texto de `toString()` imprime `password='12345'`; esto es una cadena literal observada, no evidencia de la contraseña real que use cada dispositivo.

## Próximos pasos

1. Trazar desde las pantallas de escaneo/conexión hasta el cliente `Config.cgi` y comprobar cómo se selecciona el SSID/IP de la cámara.
2. Localizar declaraciones `native` y llamadas JNI hacia `libipcamera.so`, `libIOTCAPIs.so` y `libAVAPIs.so` para reconstruir el protocolo remoto/P2P.
3. Extraer endpoints de nube, formatos de UID/credenciales y tipos de comandos desde recursos y bibliotecas nativas, distinguiendo valores predeterminados de secretos reales.
4. Validar el protocolo solo contra una cámara propia o autorizada y en una red controlada.

## Segunda pasada: aprovisionamiento y familias de protocolo

- El lector QR acepta texto con los campos `ssid:[...]` y `pass:[...]`; ambos valores se devuelven a la pantalla que inició el escaneo. El APK puede por tanto manejar credenciales Wi-Fi en claro durante el alta. No compartir capturas de esos QR ni logs con valores reales.
- La capa `com.weapons18.api.v` carga `libaw_net_client.so` y `libipcamera.so` y declara una API JNI ofuscada. `W18CallbackFunc` envía callbacks a un listener Java.
- En el callback de descubrimiento (tipo `12322`), el código interpreta bytes de respuesta como UID, campo de dirección y puerto: 21 + 16 + 2 bytes en una familia, y 64 + 16 + 2 bytes en otra. Después invoca `cd(uid, "12345")`. El significado de esos campos y la función de esa cadena fija aún requieren inspeccionar JNI/validar el SDK.
- El callback de conexión (`12289`) trata el ID de sesión negativo como fallo y un ID no negativo como conexión establecida. Los comandos IOControl pasan por JNI como `(sid, command, byte[], length)`.
- Hay tres rutas de control: `V3`, `V316` y `MSTAR01`. Las dos primeras preparan comandos binarios/IOControl; `MSTAR01` usa `Config.cgi` y mensajes UDP.
- La app descarga bases de datos de medios a través de HTTP local: `192.168.100.1:80/data/sunxi.db` (V3), `192.168.10.1:8082/tmp/data/.data/sqlite/sunxi.db` (V316) y `192.168.1.1:80/tmp/DCF.db` (MSTAR01). También usa `/file/media` en `192.168.10.1:8082` para miniaturas.
- `MSTAR01` espera respuestas UDP en texto con `action=` y acciones `sd_format`, `sd_status`, `cap`, `rec`, `rtsp`, `rec_time` y `cap_countdown`. El código espera dos receptores, pero sus puertos y el formato de solicitud requieren recuperar el `Runnable` que JADX no pudo decompilar.
- El SDK descarga del dispositivo recursos de UI/configuración (menús de grabación normal/lenta/bucle/timelapse, menús de foto y ajustes, y `strings`) mediante HTTP en `192.168.1.1/customer/UI/release/assets/default/raw/...`.
- Descarga bases locales de medios desde endpoints HTTP diferentes según la familia (detallados arriba); las miniaturas V316 usan `192.168.10.1:8082/file/media`.
- En V3, la inicialización Java emite IOControl `41022` (valor `1`), consulta `41012` y `41002`, y sincroniza la hora con ID `40977` y un búfer de 36 bytes. En V316 emite `1636`, `1607`, `1619`, sincroniza con `1599` y manda datos de 32 bytes; `1617` aparece para otra operación con búfer de 8 bytes. Los enteros se serializan con el helper `f.c(int)`; todavía falta confirmar orden de bytes y significado completo desde el helper y las respuestas.
- Los tipos de dispositivo están definidos literalmente como `V3`, `V316` y `MSTAR01`. `W18DVToolApi` publica una amplia API de control, incluidas opciones de grabación/foto y reproducción.
- La tabla de mensajes globales etiqueta conexión: éxito `12289`, fallo `12290`, desconexión `8196`; actualización de estado `53000`; refresco de ajustes `52997/52998`; refresco de archivos `52995/52996`; tarjeta SD `49165–49169`; frames de vídeo `53265–53267` y habilitación `53504`.
- Se encontró un endpoint GRS de Huawei (`grs.dbankcloud.*`) en assets de Huawei. Es infraestructura SDK de tercero y por sí sola no demuestra que la app envíe vídeo o credenciales a la nube.

## Límites de esta pasada

El análisis Java/JNI estático no revela por sí solo la negociación criptográfica ni el tráfico real. Las funciones JNI y `libipcamera.so` están ofuscadas. La constante `12345` aparece en el `toString()` del modelo y como argumento del `cd()` de conexión; no se ha verificado si es una contraseña por defecto, contraseña de cámara, semilla o compatibilidad del SDK.

Los puertos de escucha UDP para MSTAR01 y la implementación exacta de `cd`/`sac`/`ss` solo se aclararán con mejor descompilación nativa o una captura controlada de una cámara autorizada. Los IDs IOControl anteriores son observaciones directas del código de preparación, no una especificación completa del firmware.

## Reproducir el análisis local

El APK está ignorado por Git. Con `jadx` y `apktool` instalados:

```sh
apktool d -f -s app-newCam-release.apk -o /tmp/hdvcam-apktool
jadx -d /tmp/hdvcam-jadx app-newCam-release.apk
```

Los directorios de salida son temporales y no se deben subir al repositorio.
