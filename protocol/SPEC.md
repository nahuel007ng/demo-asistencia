# Protocolo Municipal BLE Lab

Norma de interoperabilidad entre el emisor Android y los receptores Android e iOS. La versión de paquete es `1`. Un receptor que vea otra versión registra `unsupported_version` y no interpreta el identificador.

Los UUID están fijos para que dos builds del laboratorio se vean entre sí:

| Uso | UUID |
| --- | --- |
| Proximity UUID de iBeacon | `7B2E4A10-9C3D-4F58-A1E6-2D8B5C04F917` |
| Service UUID del anuncio propio | `C4A15E20-6B17-4D3E-9F80-11A2B3C4D5E6` |

Solo *legacy advertising* (PDU de 31 bytes). No se usa *extended advertising* ni *scan response*: el identificador viaja en el anuncio principal.

## Identificador rotativo

El identificador es el índice de la ventana UTC, no un contador local. Así el receptor puede calcular el valor esperado con su propio reloj, sin un canal compartido.

```text
windowIndex = floor(unixEpochSeconds / periodSeconds)
identifier  = windowIndex mod 2^32
major       = (identifier >> 16) & 0xFFFF
minor       = identifier & 0xFFFF
```

`periodSeconds` es 10, 15 o 30. El valor por defecto del experimento es 15.

En el instante exacto del límite, el identificador ya es el de la ventana nueva y el remanente hasta el próximo cambio es el período completo. Un segundo antes del límite, el remanente es 1.

El modo de identificador fijo sustituye ese valor por uno elegido por el operador. Sigue ocupando 32 bits y se parte en major y minor con la misma regla. El bit 0 de `flags` vale 1 cuando el identificador es temporal y 0 cuando es fijo.

El identificador es predecible. Esta etapa no usa TOTP ni cifrado.

Los vectores de `fixtures/windows.json` son normativos. El caso de referencia es:

```text
unixEpochSeconds = 1699999995
periodSeconds    = 15
windowIndex      = 113333333
identifier       = 113333333 = 0x06C15455
major            = 1729      = 0x06C1
minor            = 21589     = 0x5455
```

## A. iBeacon

Formato de fabricante Apple (`company id` `0x004C`), que es el que Core Location reconoce como iBeacon. El uso es de laboratorio, para poder comparar contra *beacon ranging*.

`BluetoothLeAdvertiser` recibe el payload de 23 bytes. El stack antepone el company id.

```text
offset  tamaño  campo
0       1       0x02
1       1       0x15
2       16      proximity UUID, orden de red
18      2       major, uint16 big-endian
20      2       minor, uint16 big-endian
22      1       measured power, int8, dBm a 1 m. Valor inicial -59 (0xC5)
```

Estructura AD resultante, sin contar flags:

```text
1A FF 4C 00 | payload de 23 bytes
```

Son 27 bytes. Si el stack agrega flags (`02 01 06`, 3 bytes) el total es 30, dentro de 31. No se incluye el nombre del dispositivo ni el AD de TX power de Android: no entran y no forman parte de iBeacon.

El anuncio por defecto no es conectable. En ese modo Android puede omitir flags. iOS suele hacer ranging con la firma `4C 00 02 15` igual. El emisor tiene un interruptor "conectable", apagado por defecto, para medir esa variante. Si se activa, el anuncio legacy pasa a ser conectable y escaneable, porque el controlador no acepta un legacy conectable que no sea escaneable. El identificador sigue estando en el PDU principal.

Filtro de escaneo Android: company id `0x004C`, máscara en `02 15` y en los 16 bytes de UUID. Major, minor y measured power van con máscara 0. Si se enmascaran major y minor, la rotación deja de verse.

iOS recibe este protocolo solo con Core Location (`CLBeaconIdentityConstraint` por UUID, sin fijar major ni minor) y `startRangingBeacons(satisfying:)`. Core Bluetooth no entrega a la aplicación los bytes de un iBeacon. No se usa `CLMonitor`: el ranging clásico es la API que Apple describe como la de menor demora.

## B. BLE propio

Un único AD de *service data* de 128 bits (tipo `0x21`). No se anuncia además la lista de servicios de 128 bits: las dos estructuras juntas superan 31 bytes.

```text
offset  tamaño  campo
0       1       version = 0x01
1       1       flags. bit0 = 1 identificador temporal, 0 fijo. El resto reservado en 0
2       4       identifier, uint32 big-endian
6       2       periodSeconds, uint16 big-endian
8       1       measured power, int8
```

La estructura AD mide 1 + 1 + 16 + 9 = 27 bytes, o 30 con flags. Cabe en legacy.

Para el caso de referencia, con flags temporales, período 15 y potencia -59, el valor del service data es:

```text
01 01 06 C1 54 55 00 0F C5
```

Android filtra con `ScanFilter.setServiceData(serviceUuid, null)`. iOS no puede filtrar por service data en el controlador: escanea en primer plano sin filtro de servicio y acepta el anuncio solo si `CBAdvertisementDataServiceDataKey` contiene el service UUID del laboratorio. El resto de los anuncios se descarta y no entra en las métricas.

El anuncio propio no es conectable ni escaneable, salvo el interruptor de la variante conectable.

## Relojes

- El identificador se calcula con el reloj de pared UTC del dispositivo que lo calcula.
- Los intervalos de una misma prueba (latencia de captura, tiempo hasta el código siguiente, demora del callback de advertising) usan el reloj monotónico de ese dispositivo.
- `wall_time_utc` y `wall_epoch_ms` se exportan para poder inspeccionar el cruce de ventana. Una resta entre la marca de pared del emisor y la del receptor no es una latencia: los relojes no están sincronizados.
- En Android, la marca primaria de una observación es `ScanResult.timestampNanos` (misma base que `elapsedRealtimeNanos`). También se guarda el instante de entrada al callback.
- En iOS, la marca primaria es el reloj monotónico tomado al entrar al delegado. `CLBeacon.timestamp` se conserva como dato secundario del sistema.

## Clasificación

Con el período elegido por el operador y el reloj de pared local `T`:

| Clase | Condición |
| --- | --- |
| `current` | el código es el de la ventana de `T` |
| `previous` | el código es el de la ventana de `T - período` |
| `next` | el código es el de la ventana de `T + período` |
| `other` | cualquier otro valor |

`boundary_grace` es una marca adicional, no una clase. Vale cuando la clase es `previous` y el segundo dentro de la ventana es menor que el margen configurado. El margen por defecto es 0: no hay gracia. La prueba de borde puede usar 2 segundos.

Una captura de 5 segundos es correcta si llega un código que coincide con el esperado escrito por el operador. Si ese campo está vacío, es correcta cuando la clase es `current` o cuando `boundary_grace` está activo. Ver solo códigos viejos cuenta como fallo (`not_current`), no como éxito. No ver nada cuenta como `no_signal`.

## Percentiles

Nearest-rank sobre las latencias exitosas, ordenadas de menor a mayor. Con `N` muestras, el percentil `p` es el elemento en la posición `ceil(p/100 * N)` contando desde 1. Los timeouts no entran en el percentil; se cuentan aparte. `N = 0` no tiene percentil.

## Exportación

Esquema `blelab.session`, versión 1. Cada evento se guarda en CSV y en una línea JSON. El JSON de exportación envuelve esas líneas y agrega el resumen calculado con las mismas reglas. Los campos de cada evento son:

```text
session_id, role, manufacturer, model, os_version, app_version,
protocol, advertise_method, period_seconds, interval_ms, connectable,
event_type, monotonic_ms, wall_time_utc, wall_epoch_ms,
code, major, minor, rssi, classification, boundary_grace,
capture_latency_ms, success, error_code, error_message
```

`protocol` es `ibeacon` o `custom`. `advertise_method` es `restart`, `update_set` o vacío en el receptor. `role` es `emitter` o `receiver`.

Los tipos de evento usados por las apps son: `session_start`, `session_stop`, `advertise_started`, `advertise_start_failed`, `advertise_data_set`, `advertise_data_failed`, `advertise_stopped`, `rotation_scheduled`, `rotation_applied`, `bluetooth_off`, `bluetooth_on`, `permission_missing`, `permission_revoked`, `recovery`, `scan_started`, `scan_stopped`, `scan_failed`, `observation`, `capture_attempt`, `unsupported_version`, `error`.

Una versión de paquete distinta de 1 produce `unsupported_version` y no una observación con código inventado.
