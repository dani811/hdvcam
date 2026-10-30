# HDVCam: arquetipo reconstruido

Este directorio es el cuaderno de reconstrucción mantenible de la app HDVCam. Separa lo que sabemos del comportamiento de la app, lo que sigue ofuscado y la implementación limpia que podamos escribir más adelante.

## Reglas para retomar el trabajo

1. Lee primero `STATUS.md`, luego `architecture.md` y el protocolo pertinente.
2. Cada hallazgo nuevo debe llevar una fuente (APK/hash, archivo decompilado + símbolo/línea, recurso, o captura controlada) y nivel: **confirmado**, **inferido** o **pendiente**.
3. Registra clases/métodos renombrados en `symbol-map.md`; conserva el nombre original ofuscado y asigna un alias descriptivo solo como alias nuestro.
4. No copies código decompilado al repo. Describe el comportamiento y escribe una implementación independiente cuando toque.
5. El APK, salidas JADX/apktool, bases de datos de cámara, capturas y credenciales permanecen locales y excluidos de Git.

## Índice

- `STATUS.md`: checkpoint y siguiente acción concreta.
- `architecture.md`: componentes y flujo de extremo a extremo.
- `symbol-map.md`: clases/métodos importantes, incluidos alias descriptivos.
- `protocols/overview.md`: tabla comparativa V3/V316/MSTAR01.
- `protocols/mstar01.md`: HTTP local, UDP y control MSTAR01.
- `protocols/iocontrol.md`: JNI e IOControl binario V3/V316.
- `native-libraries.md`: inventario ELF/JNI y plan de reversing.

## Entorno reproducible observado

En el Mac estaban disponibles `jadx 1.5.6`, `apktool 3.0.3`, Java y Python con `capstone 5.0.9`, `lief 1.0.0` y `pyelftools 0.33`. Los directorios de trabajo actuales son `/tmp/hdvcam-jadx` y `/tmp/hdvcam-apktool`; no son persistentes. Regenera con:

```sh
apktool d -f -s app-newCam-release.apk -o /tmp/hdvcam-apktool
jadx -d /tmp/hdvcam-jadx app-newCam-release.apk
```

Identidad del APK analizado: SHA-256 `61c61a8f101618d2d9035f2561c0f49bacc1616dd266a56f6b1e0a547d24d3cd`, paquete `com.record.dv`, versión `1.23.20261012`. Se dispone también de baksmali 3.0.9 dentro del Android Command Line Tools; puede desensamblar el DEX cuando JADX no recupera métodos. En este host funciona con la clase `com.android.tools.smali.baksmali.Main` y los jars de `smali-baksmali`, `smali-dexlib2`, `smali-util`, Guava y JCommander.
