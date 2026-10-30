# Protocolo local MSTAR01

## HTTP de configuración

Host fijo en el código Java: `192.168.1.1`. Requests construidos como:

```text
GET /cgi-bin/Config.cgi?action=get&property=<propiedad>
GET /cgi-bin/Config.cgi?action=set&property=<propiedad>&value=<valor>
```

Propiedades observadas: `Camera.Menu.*`, `Camera.Menu.CardInfo.*`, `TimeSettings`, `Video`, `FactoryReset`, `SD0`, `StreamStatus`, además de nombres derivados de tags de menú.

Acciones explícitas:

- grabación: `property=Video&value=recordon|recordoff`
- captura: `property=Video&value=capture`
- hora: seis campos fecha/hora separados por `$`, en orden año, mes, día, hora, minuto, segundo.
- menús: parsea líneas `Camera.Menu...=valor`; incluye modo de trabajo, retardo y opciones de captura/grabación.
- capacidad SD: parsea `LifeTimeTotal` y `RemainLifeTime`, multiplica por 1024.

El callback de botón `p127z3.U.t(boolean)` llega a `p063m3.G.z(mode,start)`. Este consulta estado de almacenamiento, llama `d0.v0("Video", "recordon"|"recordoff")` y espera 500 ms antes de publicar `ShutterResult`. `d0.R("set", ...)` forma un GET `action=set&property=Video&value=...`. La réplica ya envía esa petición por la `Network` Wi‑Fi seleccionada; un HTTP 2xx solo acredita respuesta HTTP, el estado real debe venir del protocolo/evento de cámara y aún no se integra.

Este formato es la URL que construye la app, no la confirmación de todos los detalles HTTP del servidor.

## UDP de estado

El app lanza dos `Thread(new p026f.b(this, 0|1))`, pero solo uno recibe UDP. El worker de recepción crea `DatagramSocket(0xBFF6)`, es decir, puerto **49142**, y recibe datagramas de hasta 1024 bytes; decodifica texto con el largo recibido, aplica `trim()` y lo entrega a `p026f.c.m`. Parser espera `action=<nombre>\n...` y reconoce `status=` para `sd_format`, `sd_status`, `cap`, `rec`, `rtsp`; para `rec_time` y `cap_countdown` reconoce `time=`. El otro worker mantiene un ciclo keepalive: espera ~100 ms por vuelta y solicita `StreamStatus=ON` cada ~2 s mientras el flag global está activo. No se ve un paquete UDP de solicitud en este worker; queda por encontrar quién emite el estado desde el dispositivo.

No afirmar que UDP esté autenticado/cifrado: no observado. No mandar datagramas de escritura a una cámara fuera de un entorno controlado.

## Valores a recuperar

Fuente temporal: `/tmp/hdvcam-jadx/sources/p026f/c.java`, `b.java`; smali en `/tmp/hdvcam-apktool/smali*/p026f/b.smali`.

- [x] puerto bind: UDP 49142; dirección de envío/broadcast no observada
- [ ] ciclo de vida y parada de hilos
- [ ] mapping de mensajes UDP a eventos
- [ ] formatos de `FactoryReset`, `SD0`, `StreamStatus`
- [ ] auth/headers del `Config.cgi`
- [ ] encontrar desde firmware/cámara qué emite los datagramas de estado
