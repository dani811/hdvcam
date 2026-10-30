# hdvcam

Reconstrucción clean-room de la aplicación de cámara HDVCam a partir de análisis estático del APK local. El APK de referencia es material de análisis y está excluido de Git.

## Estado actual

El objetivo del proyecto es una réplica fiel de la app original. La app tiene las pestañas Dispositivo / Archivos locales / Más, alta QR/manual y asociación Wi‑Fi local, detección de familias, RTSP para MStar/QZ/YZ y un recorrido AW LAN→TCP/UDP→H.264/MediaCodec en desarrollo. También hay transporte parcial de grabación/foto MStar y PTZ QZ. **Ninguna ruta se ha validado con una cámara física**: siguen sin medirse primer frame real, codec/controles aceptados, recuperación y estabilidad larga. La galería remota de cámara, la ruta propietaria V316/TUTK, los ajustes dinámicos y audio siguen incompletos. Consulta [el estado de reversing](reverse/STATUS.md) y [la matriz de trazabilidad](docs/traceability.md) para ver los límites por función.

## Compilar y probar

Requisitos: JDK 17 o superior y Android SDK con API 36. Gradle 8.13 se usa mediante el wrapper.

```sh
./gradlew test :app:assembleDebug
```

El APK de desarrollo queda en `app/build/outputs/apk/debug/app-debug.apk`. En macOS, si `ANDROID_HOME` no está exportado, se puede ejecutar:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew test :app:assembleDebug
```

## Módulos

- `app`: réplica progresiva de la interfaz Android.
- `core`: familias, niveles de evidencia y trazas.
- `protocol-aw`: contratos y framing AW de discovery, login, IOControl, media y prefijo NetCore.
- `protocol-mstar01`: constructor `Config.cgi` y parser de eventos de texto.
- `protocol-tutk`: contrato medido; no implementa ni sustituye el SDK nativo.
- `reverse/`: evidencia, mapa de símbolos, protocolos y checkpoints.
- `docs/traceability.md`: vínculo entre afirmaciones, evidencia, implementación y pruebas.

Cada comportamiento debe etiquetarse como confirmado, parcial, hipótesis o simulación. Se reutilizan los recursos visuales del APK local para lograr fidelidad de interfaz; el APK completo se mantiene fuera del control de versiones.
