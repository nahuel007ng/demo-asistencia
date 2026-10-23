# Plan de pruebas de campo

Cada fila de `templates/comparacion.csv` es una combinación de dispositivo receptor, protocolo, método de emisión, período e intervalo. La meta del 99 % de capturas correctas en 5 segundos es una hipótesis. El porcentaje que vale es el de la exportación.

Hacen falta dos equipos como mínimo: un Android que emite y otro que recibe (Android o iPhone). El iPhone no emite.

Antes de empezar, anotar modelo, versión del sistema y si el emisor muestra advertising disponible. Dejar los dos equipos a una distancia fija, con la pantalla encendida y la app visible. En iOS, si la pantalla se atenúa, Core Bluetooth baja el ritmo de escaneo aunque `AllowDuplicates` esté activo.

Los relojes no están sincronizados. Comparar el código recibido con el código que el receptor calcula, no restar la hora del emisor y la del receptor.

## 1. Recepción estática

1. En el emisor, elegir protocolo, activar identificador fijo y escribir un valor corto, por ejemplo `1000`.
2. Iniciar emisión con intervalo 100 ms.
3. En el receptor, mismo protocolo, escribir `1000` como código esperado e iniciar el escaneo.
4. Confirmar que aparecen observaciones con ese código y un RSSI. Si no aparece nada en 30 segundos, detener y exportar igual: el fallo se conserva.

## 2. Rotación

1. Desactivar el identificador fijo. Período 15 s. Intervalo 100 ms.
2. Iniciar emisor y receptor. Dejar correr al menos 10 cambios (unos 3 minutos; mejor 15 minutos).
3. Anotar el tiempo hasta el código nuevo que muestra el receptor. Un código `previous` que sigue llegando después del límite es un dato, no un error de registro.

Repetir con 10 s y con 30 s si el resultado de 15 s no es concluyente.

## 3. Captura de 5 segundos

1. Con la rotación activa y el receptor ya sincronizado en pantalla (se ve un código `current`), pulsar captura.
2. La serie por defecto es 20 intentos, 5,0 s cada uno, 2 s de pausa visible entre ellos.
3. Un intento es correcto solo si el código es el esperado o, si ese campo está vacío, si la clase es `current` (o gracia de borde, si se configuró).
4. Exportar. Los intentos fallidos quedan como `capture_attempt` con `success=false`.

## 4. Cambio de ventana

1. Poner el margen de gracia en 0 para la pasada estricta y repetir con 2 s.
2. Mirar el remanente de ventana en las dos pantallas. Disparar la captura cuando queden 1 o 2 segundos.
3. El objetivo es ver si el primer código de la captura es el viejo, el nuevo, o si no llega ninguno antes de los 5 s.

## 5. Estabilidad

Dejar una sesión de rotación activa durante varias horas, con la pantalla encendida y la app en primer plano. En Android la sesión corre además en un servicio en primer plano con notificación. Si el sistema mata el servicio, eso es un resultado: queda en el registro o, si el proceso muere, en el CSV ya escrito.

No hace falta mirar la pantalla todo el tiempo. Al final, exportar y revisar errores del emisor, ventanas omitidas y la proporción de códigos `previous`.

## 6. Recuperación

Con una sesión activa, en este orden, y esperando a que la app registre cada paso antes del siguiente:

1. Apagar Bluetooth en el emisor. Volver a encenderlo. La reanudación tiene que aparecer como `bluetooth_off`, `bluetooth_on` y `recovery`, no como un silencio.
2. Apagar Bluetooth en el receptor y encenderlo.
3. Detener la emisión y volver a iniciarla.
4. Revocar el permiso de dispositivos cercanos o de ubicación, volver a la app y confirmar `permission_revoked`. Reconcederlo y reanudar a mano.

## Cómo llenar la planilla

Copiar una fila por prueba a partir de `templates/comparacion.csv`. Los números salen del resumen en pantalla o del objeto `summary` del JSON. `hardware_verified` queda en `si` solo cuando esa fila proviene de un dispositivo físico. Borrar la fila de ejemplo.

Columnas de latencia: media, P50, P95 y P99 de las capturas correctas, en milisegundos. Los timeouts no entran en esos cuatro números; sí entran en `attempts` y bajan `success_percent`.

`missed_windows` es una estimación con el reloj del receptor. Si el resumen dice que no se pudo estimar, dejar la celda vacía y explicarlo en `notes`.

## Qué decidir con los resultados

El protocolo candidato es el que, en los teléfonos y tablets que harían de kiosco y de fichador, sostenga la meta de captura sin depender de un modelo nuevo. Si iOS solo alcanza esa meta con iBeacon, o solo con el anuncio propio, esa asimetría es el resultado del laboratorio. Si un Android no puede emitir (`advertiser` ausente o `FEATURE_UNSUPPORTED`), ese equipo no sirve como kiosco aunque el receptor funcione.
