# Estado del proyecto — Sistema de asistencia municipal

Cierre de la primera demo BLE, 25 de octubre de 2026. Este texto une el diseño del sistema con lo que el laboratorio midió. No reemplaza ninguna de las dos fuentes.

- Diseño: [`assets/Especificacion_Sistema_Asistencia_Municipal.md`](../assets/Especificacion_Sistema_Asistencia_Municipal.md)
- Mediciones: [`results/INFORME.md`](INFORME.md)

## De dónde sale

El municipio de Catamarca necesita un registro de entrada y salida para del orden de 5.000 empleados y 50 o más edificios, más cuadrillas. El agente ficha cuando llega y cuando se retira. La captura no impone turnos ni rechaza por llegar tarde. Las políticas de personal quedan para después.

Lo ya acordado en la especificación, y que esta demo no reabre:

- Cada agente ficha desde su celular. El kiosco, fijo o de cuadrilla, es Android. La municipalidad puede prestar un Android si el encargado no tiene uno compatible. No hay kiosco iOS.
- La evidencia de sede es un QR dinámico y una baliza BLE, los dos generados por el mismo kiosco, con valores distintos, cada 15 segundos. El BLE se anuncia durante toda la ventana. El teléfono no se empareja con el kiosco.
- El secreto queda en el kiosco y en la API. La app del agente no lo conoce y no calcula los códigos.
- No se usa GPS en la fichada normal, en la contingencia ni en las cuadrillas.
- El kiosco no necesita Internet para emitir. Sí necesita una hora confiable.
- Sin red, la app guarda la fichada y la presenta después. El límite inicial de admisión es 72 horas. Eso no significa aceptación automática.
- Si el par no cierra en una escucha de hasta 15 segundos, la app pide reintentar. Si falla otra vez, sugiere la fichada asistida y deja seguir intentando. No usa ubicación.

## Alcance de este trabajo

Este repositorio lo lleva quien hace la app móvil. Las decisiones y las pruebas de la API quedan fuera de su criterio. Esas diferencias siguen escritas en la especificación, abiertas, y no son una tarea de esta etapa.

Lo que hay que validar ahora es el funcionamiento en los dispositivos. Con las pruebas BLE ya hechas alcanza para sumar el QR al flujo y seguir midiendo en hardware. No hace falta cerrar antes el contrato con el servidor, el enrolamiento, las 72 horas ni la operación de las sedes.

La etapa siguiente es una simulación dentro de la app:

- El modo kiosco, solo Android, tiene un secreto mock de laboratorio. Con ese secreto genera el QR y el BLE de la ventana de 15 segundos. Los dos valores son distintos. El secreto no es el de un kiosco municipal y no se provisiona contra ningún servidor.
- El modo receptor, Android e iOS, lee el QR con la cámara y captura el BLE propio. No llama a la red.
- La comprobación también es simulada en el teléfono, con el mismo secreto mock, para poder ver si el par leído corresponde a la ventana. El receptor arma un payload de ensayo y se lo entrega a un mock en el mismo proceso. Ese mock no es la API. En el producto la app del agente no tiene el secreto y quien valida es la API. Esa regla no cambia. El secreto está en los dos modos solo porque este ensayo no tiene servidor.

## Qué es este repositorio

Es el laboratorio nativo que la especificación pide en el §11 antes de afirmar compatibilidad. Android emite y recibe. iOS solo recibe. La demo ya medida usa como identificador el índice de la ventana UTC, el mismo en los dos teléfonos, para clasificar cada paquete sin criptografía.

No es la aplicación de asistencia del municipio. No hay usuarios, base municipal ni llamada a una API. El QR y el secreto mock están en la app: el kiosco Android los muestra y el receptor, Android o iOS, ficha contra el mock del teléfono. Falta la pasada en los dispositivos.

## Qué cerró la demo

Medido el 25 de octubre de 2026 con la app `0.1.0`, identificador `dev.municipal.blelab`. Los dos equipos tenían la pantalla encendida y la app visible. La muestra es un Redmi Pad Pro y un Redmi Note 14, ambos Android 16, y un iPhone 11 con iOS 26.3.1.

| Decisión | Resultado en esta muestra |
| --- | --- |
| Formato | BLE propio. Service data de 9 bytes, service UUID `C4A15E20-6B17-4D3E-9F80-11A2B3C4D5E6`. iBeacon dio cero observaciones en el par Android, aunque el emisor registró sus callbacks. |
| Emisión | Reiniciar el anuncio en cada borde de ventana. Actualizar el payload sin reiniciar dejó el código viejo en el aire después de apagar el Bluetooth de la tablet. |
| Período | 15 segundos. No hizo falta pasar a 30 s para esta visita. |
| Un anuncio para las dos plataformas | El Note 14 recibió al Pad. El iPhone 11 recibió al Note 14, en primer plano, con Core Bluetooth. |
| Gracia del laboratorio | 0. El receptor de la demo acepta el código de su ventana UTC actual. |
| Resto de la radio | Intervalo 100 ms, anuncio no conectable, sin identificador fijo, potencia alta. |

De cerca, el Note 14 vio todas las ventanas del Pad: 585 observaciones, sin código viejo. La captura de 5 segundos dio 20 de 20, mediana 55 ms, P95 163 ms. A 3–3,5 m, Reiniciar también dio 20 de 20, mediana 55 ms, con un peor caso de 1929 ms en un límite de ventana.

En el iPhone, de cerca, la captura fue 18 de 20, mediana 118 ms. Los dos fallos fueron `not_current`: el Note 14 volvió a anunciar el código de la ventana anterior y después saltó una. El código faltante no estuvo en el aire. En la emisión siguiente, nueve rotaciones seguidas, el salto no se repitió. A 3,5 m hubo una visita exportada de 29,4 s: el primer paquete llegó a los 90 ms, `missedWindows` quedó en 0 y el código anterior se vio unos 0,2 s en cada cambio. Los reinicios previos de esa serie no están en el JSON.

Al apagar el Bluetooth, Reiniciar volvió en unos 8 s en el emisor y no dejó el código anterior. Actualizar tardó unos 21 s y el código viejo siguió junto con los nuevos. Con el receptor Android apagado unos 10 s, la recepción volvió sin código viejo.

La visita de referencia, con el kiosco ya anunciando, queda en unos 15 segundos en Android y unos 30 segundos en iOS.

Eso alcanza para sumar el QR a la misma radio y seguir las pruebas en estos dispositivos. En Android la serie dio 20 de 20 de cerca y a 3–3,5 m, y 19 de 20 en las recuperaciones. En iOS, 18 de 20 de cerca, por el salto del emisor. La pasada de 3,5 m del iPhone es una visita, no la serie de 20.

## Cómo se leen el 20, la ventana y el 99 %

Son tres cosas distintas. Cruzarlas fue el error de lectura.

**La ventana de 15 segundos** es el tiempo en que el código no cambia. En el producto, el QR y el BLE rotan juntos y duran esos 15 segundos. Durante ese tiempo el kiosco repite el anuncio BLE cada 100 ms: del orden de 150 anuncios con el mismo código. Una fichada necesita una lectura vigente del QR y una del BLE. Con eso arma el payload. No necesita que lleguen los 150 anuncios.

**El intento de laboratorio** no es uno de esos anuncios ni es una fichada. La serie por defecto tiene 20 intentos. Cada intento espera como máximo 5 segundos a que llegue el código de la ventana actual. Si llega antes, el intento termina ahí: en esta muestra la mediana fue 55 ms en Android y 118 ms en el iPhone. Después hay 2 segundos de pausa y empieza otro intento. El intento no ocurre cada 5 segundos. Los 5 segundos son el tope de espera, y la pausa entre intentos es de 2 segundos.

Esos 20 intentos no caben dentro de una ventana. Si un intento acierta enseguida, el ciclo dura poco más de 2 segundos y varios intentos caen sobre el mismo código. Si agota los 5 segundos, el ciclo dura 7 segundos y en una ventana de 15 segundos entran dos. Esa cuenta es del ensayo. La app de fichada no hace dos o tres intentos por ventana: escucha mientras el código está en el aire, y la primera lectura buena alcanza.

**El 99 % de la especificación (§11.3)** es una hipótesis de laboratorio: que el 99 % de esos intentos de hasta 5 segundos salga bien. 18 de 20 es el 90 % de una serie de intentos. No es el 90 % de los teléfonos ni el 90 % de las fichadas. P95 y P99, en la misma pantalla, son percentiles de latencia de los intentos que sí salieron. Tampoco son ese porcentaje.

**El 99 % operativo**, el que importa para la asistencia, es otra escala: de las personas frente al kiosco, cuántas obtienen el QR y el BLE vigentes y pueden marcar. En esta muestra, con la app abierta y el Bluetooth encendido, el primer paquete bueno llegó enseguida. Los 2 intentos fallidos del iPhone fueron `not_current`: el emisor no puso ese código en el aire, y el teléfono vio los que sí salieron. Un intento siguiente, ya en otra ventana, vuelve a escuchar. Un aviso de “no se pudo marcar, intentá de nuevo” cubre ese fallo transitorio y también un cruce de borde. Bluetooth apagado, permiso denegado, cámara inutilizable o un kiosco que anuncia mal durante toda la visita no se arreglan con el mismo aviso: esos quedan para la fichada asistida.

Tres equipos y series de 20 no miden todavía ese 99 % de visitas del municipio. Sí alcanzan para no leer 18/20 como una radio que apenas funciona.

## Espera del receptor

Regla de la app, para el ensayo y para la fichada. Durante los 15 segundos en que el código no cambia, el kiosco repite el mismo BLE unas 150 veces. El receptor no reparte esa ventana en intentos de 5 segundos.

Escucha de corrido hasta tener el QR y el BLE, con un techo de 15 segundos. Corta en cuanto el par cierra. Si el primer paquete bueno llega a los 100 ms, la espera termina ahí. Los 15 segundos son el máximo antes del aviso, no lo que dura una fichada que salió bien. En la muestra ese primer paquete llegó en menos de un segundo, y el peor acierto rondó los 2 segundos. Una espera de 3–5 segundos también habría alcanzado en esos aciertos. El techo queda en 15 segundos porque dura lo mismo que el código y permite cruzar un cambio de ventana.

El reloj de esa espera y el reloj del código son distintos. Una escucha empezada al final de una ventana sigue en la siguiente. El receptor acepta el código que está en el aire, el actual o el de la ventana contigua, y no se queda con el que había al abrir la pantalla. Un salto aislado del emisor también queda cubierto por la ventana que viene.

Si se cumplen los 15 segundos sin un par válido, la app avisa que no se pudo marcar y ofrece intentar de nuevo. Cada toque abre otra escucha de hasta 15 segundos. Si esa también falla, sugiere ir a la fichada asistida y deja el reintento disponible. La sugerencia no obliga. Bluetooth apagado, permiso denegado, cámara que no lee o un kiosco que anuncia mal durante toda la visita no se arreglan con otro toque: para eso está el responsable.

En este ensayo el aviso de asistencia asistida es solo texto en el receptor. No abre el flujo del encargado ni llama a la API.

## Fuera de alcance de esta etapa

Siguen abiertos. No hay que resolverlos para probar el QR y el BLE en los teléfonos, y no los decide este trabajo.

| Tema | Por qué queda afuera |
| --- | --- |
| API y backend | Autenticación, persistencia, idempotencia, auditoría y cualquier prueba contra un servidor. La simulación no llama a la red. |
| Alineación del documento de API del 23/10/2026 | GPS, iBeacon, validación histórica y umbrales de 5 y 60 minutos quedan escritos en la especificación. Cerrarlos es del equipo de API. |
| Contrato definitivo con el servidor | Algoritmo, longitud, vectores homologados y si seis dígitos alcanzan en producción. El ensayo usa la concreción del §4.2 solo para poder generar y leer en el dispositivo. |
| Firma del agente, enrolamiento, attestation y recambio de teléfono | Identidad de producto. El ensayo no tiene usuarios. |
| Cola offline, 72 horas y estados de RR. HH. | Dependen de la API. |
| Reloj operativo del kiosco | Deriva máxima, resincronización y arranque sin hora válida. El ensayo usa el reloj UTC del equipo, como la demo BLE. |
| Fichada asistida | Procedimiento de negocio ya descrito. Su contrato con la API no se prueba acá. |
| Más modelos, más locales, sesión de horas, lote de hardware, ESP32 | La muestra actual alcanza para diseñar el flujo QR + BLE. Ampliarla no es el paso que sigue. |

El salto de ventana del Note 14 sí entra en el ensayo: el kiosco no puede dejar en el aire un código de una ventana vencida. Eso se mira al probar la rotación del QR y del BLE juntos.

## Simulación QR + BLE

La radio ya está elegida: BLE propio, Reiniciar, 15 segundos, 100 ms, no conectable, pantalla encendida en los dos equipos. El `uint32` del service data deja de ser el índice de ventana. Pasa a ser el valor derivado del secreto mock. El QR lleva otro valor, de la misma ventana.

La codificación del ensayo ya está fijada en [`protocol/SPEC.md`](../protocol/SPEC.md), sección C, con el vector [`protocol/fixtures/simulation.json`](../protocol/fixtures/simulation.json). No aprueba el contrato con la API.

```text
secreto    = lab-secreto-mock
kiosco_id  = lab-kiosco-01
T          = floor(unix_s / 15), como entero de 8 bytes, big-endian

QR         = v2|lab-kiosco-01|{token}|{T}
token      = TOTP HMAC-SHA1 de 6 dígitos, período 15

BLE        = primeros 4 bytes de HMAC-SHA256(K_ble, T)
K_ble      = HMAC-SHA256(secreto, "asis-ble-v1")
```

Ese valor BLE entra en el `uint32` del anuncio de 9 bytes que ya se midió. El modo que anuncia el índice de ventana queda como está.

Un par es válido si el QR trae ese `kiosco_id`, el token es el TOTP de su `step`, el BLE es el valor de ese `step` o de uno contiguo, y el `step` está a lo sumo a una ventana del reloj del teléfono. Así una foto vieja no pasa, y una lectura en el cambio de ventana sí. Antes de probar en hardware, el reloj del kiosco y el del receptor tienen que estar a menos de unos 15 segundos. Si difieren un minuto, el ensayo falla aunque la radio esté bien.

Los 15 segundos de espera empiezan cuando la persona inicia la fichada, con la cámara y el BLE a la vez, y terminan al primer par válido. Este corte no pide entrada ni salida, legajo ni firma, y no arma una cola para un servidor. Si el mock acepta, la pantalla dice `Ingreso correcto` y muestra el texto del QR, el identificador BLE y la ventana.

En Android el modo Kiosco está en el emisor. Deja la radio en BLE propio, Reiniciar, 15 segundos, 100 ms y no conectable. El modo que anuncia el índice de ventana sigue en iBeacon y en BLE propio. En el receptor, Android e iOS, el botón Fichar abre la cámara y el BLE propio. La serie de capturas de 5 segundos no se usa para esa fichada. Kiosco y receptor exportan el mismo CSV y JSON. `qr_shown` y `checkin` quedan en ese registro.

## Pasos siguientes

1. Hecho en la app: el kiosco Android genera el QR y el BLE con el secreto mock, y reinicia el anuncio en cada ventana de 15 segundos.
2. Hecho en la app: el receptor de Android y el de iOS leen el QR junto con el BLE propio.
3. Hecho en Kotlin y en Swift, contra `protocol/fixtures/simulation.json`, antes de abrir la cámara. En esta máquina corrió `:core:test`; sigue sin haber Mac local. Desde el 28 de octubre de 2026, el flujo de CI ejecuta también XCTest en un simulador iOS de GitHub. Los resultados y reportes de cada revisión están en su PR; no prueban BLE ni cámara físicos. El flujo de trabajo está en [CONTRIBUTING.md](../CONTRIBUTING.md).
4. Probar en los dispositivos que ya se usaron: el kiosco muestra un QR que rota, el receptor escucha hasta 15 segundos, corta al primer par válido y, si el techo se cumple, ofrece reintentar y después sugiere la asistencia asistida. Sin red. Los relojes tienen que estar a menos de unos 15 segundos.
5. Tras corregir los errores detectados por Android Lint, comprobar en una instalación nueva de Android API 31 o superior la solicitud conjunta de ubicación precisa y aproximada: conceder precisa y probar receptor BLE y cámara; conceder solo aproximada debe mantener la solicitud de precisa. Esta comprobación física sigue pendiente. El GPS no se incorpora a la fichada.
