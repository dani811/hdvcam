# AW arm64: login y peer UDP

## Artefacto inspeccionado

- APK: `app-newCam-release.apk`
- SHA-256: `61c61a8f101618d2d9035f2561c0f49bacc1616dd266a56f6b1e0a547d24d3cd`
- Biblioteca: `lib/arm64-v8a/libaw_net_client.so`
- SHA-256 de la biblioteca extraída: `c88f9d5ccddb5a70e2dd01d9929a7c41da5816f140ad154e09263331ebca327b`.
- Análisis: radare2 `aaa`, `pdf @ RVA` sobre ELF AArch64, base RVA 0.

## Evidencia de flujo

| Función/RVA | Instrucciones relevantes | Deducción acotada |
|---|---|---|
| `CNetManage::addConInfo` `0xea98` | Primer slot cae en rama sin entradas; al crear `UdpClient`, llama `init(this, 0)`; termina devolviendo `w4=0`. | Índice inicial de sesión 0. |
| `UdpClient::init` `0xdfd0` | Conserva índice en `w20`; calcula `index + 0x1000 + 0xa0d`; pone `x1=0` y `w3=0`; llama `create_UDP_socket`. | Puerto calculado `index+6669`; primer slot 6669; dirección nula y flag 0. |
| `UdpClient::create_UDP_socket` `0xde3c` | Si flag (`w21`) es cero, ejecuta `bind(fd, sockaddr, 16)` en `0xdf94–0xdfa0`. La ruta `connect()` está en `0xdec0–0xdec8` y requiere flag no cero. | La llamada de `UdpClient::init` enlaza localmente el puerto calculado; la rama `connect` no aplica a esa inicialización. |
| `TcpClient::ConnectBy` `0x103d0` | Llama `addConInfo`; guarda retorno en `w19`; pasa `w19` como cuarto argumento de registro (`w3`) a `sendLogin` en `0x10520`. | `sendLogin` recibe el índice de sesión, no el puerto TCP. |
| `TcpClient::sendLogin` `0x102b4` | `uxtb w24,w3` en `0x102d8`; escribe `w24` en body+`0x80` en `0x10318`; `admin` y `12345` son literales en los campos 0 y 64. | El byte 128 del login es el byte bajo del índice; primer slot = 0, no `6666 & 0xff`. |
| `CNetManage::CheckLogin` `0xf1f8`, rama `0x111` | Copia el byte body+`0x80`; toma IP de `Con_Info+0x58`; calcula `selector+0x1000+0xa0d`; guarda sockaddr con `UdpClient::setAddr` en `0xf450–0xf46c`. | Tras ACK, peer UDP cliente = IP de cámara y `6669+selector`. |
| `CNetManage::CheckLogin` `0xf1f8`, rama `0x110` | Lee índice de login, calcula `6669+index` y aplica peer remoto a la IP del par TCP; ACK copia el registro y el byte de `Con_Info+4` en body+128. | La rama de aceptación configura el retorno de la cámara hacia el índice anunciado por cliente. |

La llamada `getsockname()` de `0xf3c4` consulta el descriptor TCP (`Con_Info+0`) para la dirección que se devuelve en ACK; no es evidencia de que el puerto UDP cliente sea efímero ni de una fórmula `puerto local + selector`.

## Implementación y prueba clean-room

- `AwLoginRequest.create(0)` serializa índice de primer slot 0.
- `AwTcpSession` liga UDP local en 6669 antes del login y conecta el peer UDP tras parsear ACK `0x111`.
- `AwTcpSessionLoopbackTest` emplea ACK selector 7, socket falso de cámara en UDP 6676 y receptor local en 6669. Envía un datagrama desde 6676 a 6669 y verifica que el hilo de medios lo recibe; además valida `0x1ff`/`0x2ff` en TCP.
- Esta prueba confirma que la implementación clean-room sigue el contrato de sockets observado. No confirma aceptación del firmware, selector real, framing media real, H.264 ni estabilidad de cámara.

## Reproducir el análisis

```sh
unzip -p app-newCam-release.apk lib/arm64-v8a/libaw_net_client.so > /tmp/hdvcam-libaw-arm64.so
radare2 -q -e bin.relocs.apply=true -c 'aaa; pdf @ 0xdfd0; pdf @ 0xde3c; pdf @ 0xea98; pdf @ 0x103d0; pdf @ 0x102b4; pdf @ 0xf1f8' /tmp/hdvcam-libaw-arm64.so
```
