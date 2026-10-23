# Municipal BLE Lab

Laboratorio nativo para medir, en dispositivos físicos, si un identificador BLE que cambia cada 15 segundos se puede emitir desde Android y recibir en Android e iOS con la estabilidad que exigiría una fichada.

No es una aplicación de asistencia. No hay usuarios, backend, QR ni criptografía. La meta de 99 % de capturas correctas en 5 segundos es una hipótesis para medir, no un resultado.

El contrato de bytes está en [`protocol/SPEC.md`](protocol/SPEC.md). El procedimiento de campo está en [`results/PLAN_DE_PRUEBAS.md`](results/PLAN_DE_PRUEBAS.md).

## Alcance

- Android emite y recibe. iOS solo recibe.
- Dos protocolos independientes: iBeacon y un anuncio BLE legacy con *service data*.
- Identificador de 32 bits derivado de la ventana UTC. También se puede fijar para la prueba estática.
- Dos métodos de emisión en Android: reiniciar el anuncio, o actualizar el payload con `AdvertisingSet` sin reiniciar el conjunto.
- Métricas con el reloj monotónico del equipo que mide. La resta de relojes de pared entre dos teléfonos no es una latencia.
- CSV y JSON de aciertos y de fallos. Un arranque rechazado queda en su propia sesión.

## Estructura

```text
android/     Kotlin. :core sin Android, :app con emisor, receptor y pantalla
ios/         Swift y SwiftUI. Solo receptor. Proyecto Xcode objectVersion 56
protocol/    Especificación y vectores compartidos
results/     Plan de pruebas y plantillas. Las exportaciones reales no se versionan
```

Identificador de aplicación, en las dos plataformas: `dev.municipal.blelab`. Versión `0.1.0`.

## Tecnologías

| Pieza | Versión en este repositorio |
| --- | --- |
| Kotlin | 2.1.20 |
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.13, con wrapper |
| compileSdk / targetSdk | 36 |
| minSdk | 24 (Android 7.0) |
| Jetpack Compose | BOM 2025.01.00 |
| JDK usado para compilar aquí | 21, el JBR de Android Studio |
| iOS deployment target | 15.0 |
| Swift | 5 |
| Xcode de referencia | 15 o superior, sin `CLMonitor` |

No hay Flutter, React Native ni bibliotecas BLE de terceros.

## Compilar Android

Hace falta Android Studio reciente, el SDK 36 y un JDK 17 o 21. En esta máquina el `java` del PATH es JDK 25 y no debe correr Gradle. El JBR de Android Studio sí compiló.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location android
.\gradlew.bat :core:test :app:assembleDebug
```

El APK de depuración queda en `android/app/build/outputs/apk/debug/`. `android/local.properties` apunta al SDK local y no se versiona. Instalar con Android Studio o `adb install -r` ese APK. Hace falta un equipo con radio BLE. `minSdk 24` no implica que el chip pueda anunciar: la pantalla muestra las capacidades y deshabilita lo que el sistema niega.

## Compilar iOS

Hace falta un Mac con Xcode 15 o posterior. Abrir `ios/MunicipalBleLab.xcodeproj`, elegir el scheme `MunicipalBleLab`, un iPhone o iPad con iOS 15 o superior, y un Development Team en Signing. El identificador ya es `dev.municipal.blelab`.

Los tests leen `protocol/fixtures` desde el árbol del repositorio, no desde una copia. En el scheme de test:

```text
xcodebuild test -project ios/MunicipalBleLab.xcodeproj -scheme MunicipalBleLab -destination 'platform=iOS Simulator,name=iPhone 15'
```

Esta máquina es Windows. El proyecto iOS está escrito y no fue compilado aquí. No hay que tomar el scheme como prueba de que abre en Xcode hasta correrlo en un Mac.

## Permisos

Android no usa `neverForLocation`. El escaneo puede servir para acreditar presencia y no se filtran balizas de la API.

- Hasta API 30, el receptor pide ubicación precisa en ejecución. Bluetooth y Bluetooth Admin son de instalación, limitados a ese API. Si el servicio de ubicación del sistema está apagado, el escaneo no arranca.
- Desde API 31, el receptor pide dispositivos cercanos (`BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT`) y además ubicación precisa. El emisor pide `BLUETOOTH_ADVERTISE` y `BLUETOOTH_CONNECT`, sin ubicación.
- Desde API 33 se pide el permiso de notificaciones para el servicio en primer plano.
- La emisión y la recepción largas corren en un servicio `connectedDevice` con la pantalla encendida. Si el sistema lo corta, el evento queda registrado: es un dato del experimento, no un fallo a esconder.

iOS pide ubicación When In Use solo para iBeacon, mediante Core Location y `startRangingBeacons(satisfying:)`. El BLE propio usa Core Bluetooth. No hay modos de segundo plano. La pantalla permanece despierta mientras la recepción está activa. Si el permiso queda denegado, el botón abre los ajustes de la app.

## Emisor Android

La pestaña Emisor elige protocolo, método, período (10, 15 o 30 segundos), intervalo (100, 250 o 1000 ms) y si el anuncio es conectable. Apagado, el anuncio es legacy no conectable. Encendido, también es escaneable, que es lo que la API exige para un anuncio conectable legacy.

El identificador de cada ventana es `floor(unixUtc / período) mod 2^32`. Major son los 16 bits altos y minor los bajos. Con identificador fijo no hay rotación: el mismo payload queda al aire, para la prueba estática. Sin ese interruptor, el código cambia en el borde UTC y los anuncios se repiten durante la ventana.

- `Reiniciar` llama a `BluetoothLeAdvertiser.startAdvertising()`. En cada rotación detiene y vuelve a emitir, con una pausa corta.
- `Actualizar` usa `startAdvertisingSet()` y `setAdvertisingData()`. Solo se ofrece si la API es 26 o superior y existe anunciante. El intervalo mínimo público de `AdvertisingSet` es 100 ms.

Si el chip informa que no admite advertising múltiple, la opción igual se puede probar. `FEATURE_UNSUPPORTED` queda en el registro. Bluetooth apagado durante una sesión abierta se anota, se corta la radio y se reanuda al volver, con un evento `recovery`. Una revocación de permiso no se reanuda sola.

## Receptores

Los dos receptores eligen iBeacon o BLE propio, el período esperado, una gracia de borde de 0, 1 o 2 segundos y, si hace falta, el código esperado. Vacío, la captura acepta la ventana actual o un `previous` dentro de la gracia.

La captura dura 5 segundos, con 2 segundos de pausa entre intentos. El valor por defecto es 20 repeticiones, editable de 1 a 500. Cada intento escribe `capture_attempt` con éxito o con `no_signal` / `not_current`. No hay reintentos silenciosos.

Android escucha con `BluetoothLeScanner` en modo de baja latencia, solo anuncios legacy, y reporta duplicados. iBeacon se filtra por company id `0x004C`, tipo `02 15` y el UUID del laboratorio; major y minor quedan fuera de la máscara. El BLE propio se filtra por service data del UUID propio.

iOS no ve el fabricante de un iBeacon por Core Bluetooth. El ranging es el de Core Location y suele rondar un aviso por segundo. Puede repetir el major y minor anterior: cada entrega se guarda y se clasifica. El BLE propio escanea sin filtro de servicio, porque el anuncio no incluye la lista de 128 bits (no entra en 31 bytes junto con el service data), y se queda con los paquetes cuyo `CBAdvertisementDataServiceDataKey` trae el UUID del laboratorio. El interruptor de duplicados pide `AllowDuplicates`. iOS puede ignorarlo si baja el brillo o si la app deja de estar activa. Al pasar a segundo plano se escribe `APP_INACTIVE`; al volver, `recovery`.

El agrupado de la lista es solo visual. El archivo guarda cada anuncio.

## Métricas y exportación

En la pantalla, el resumen muestra intentos, porcentaje, media, P50, P95 y P99 de las capturas exitosas, el tiempo hasta un código mayor que el anterior, ventanas omitidas estimadas con el reloj local, clases `current` / `previous` / `next` / `other`, errores y recuperaciones. Si el porcentaje de la muestra llega a 99, el texto lo dice. Una muestra chica no decide el sistema.

Los percentiles son nearest-rank: posición `ceil(p/100*N)` desde 1. Los timeouts no entran en el percentil. Las ventanas omitidas se estiman entre la primera y la última observación; si el tramo supera 20 000 ventanas, el resumen dice que no está estimado.

Android guarda la sesión en `Documents/sessions` de la app (`getExternalFilesDir`). iOS la guarda en Documents del sandbox. Cada carpeta tiene `events.csv`, `events.jsonl`, `summary.json` y, al exportar, `session.json`. El esquema es `blelab.session` versión 1. La plantilla vacía está en [`results/templates/session.json`](results/templates/session.json). La hoja para comparar equipos es [`results/templates/comparacion.csv`](results/templates/comparacion.csv).

Para la latencia de una captura hay que usar `capture_latency_ms` o la resta de `monotonic_ms` del mismo archivo. `wall_time_utc` sirve para ver en qué ventana cayó el código, no para restar el emisor contra el receptor.

## Estado de esta entrega

Nada está verificado en hardware. Compilar no demuestra que un teléfono entregue el código a tiempo.

| Pieza | Implementado | Compilado | Según la documentación de la API | En hardware |
| --- | --- | --- | --- | --- |
| Codec, ventanas, percentiles y exportación | sí, Kotlin y Swift | Kotlin sí, `:core:test` | sí, contra `protocol/fixtures` | no |
| Emisor Android, reinicio y `AdvertisingSet` | sí | sí, `:app:assembleDebug` | sí. `AdvertisingSet` exige API 26 | no |
| Escáner Android de los dos protocolos | sí | sí, el mismo APK | sí, `BluetoothLeScanner` legacy | no |
| Pantalla Android y servicio en primer plano | sí | sí, el mismo APK | sí. En Android 15 un FGS `connectedDevice` puede cortarse si no hay un dispositivo conectado | no |
| Receptor iOS, ranging y Core Bluetooth | sí | no en esta máquina | sí. Ranging clásico desde iOS 13; el proyecto pide iOS 15 | no |
| Tests XCTest del receptor iOS | sí, los mismos vectores | no | el algoritmo coincide con el de Kotlin | no |

`:core:test` y `:app:assembleDebug` terminaron en `BUILD SUCCESSFUL` con el JDK 21 de Android Studio, en Windows, el 23 de octubre de 2026. No se instaló el APK en un teléfono durante esta entrega.

## Límites conocidos

- iOS puede retener el major/minor anterior y no entregar cada rotación al instante. Es el riesgo principal del experimento y hay que medirlo, no suponerlo.
- Core Bluetooth en iOS no entrega el fabricante de un iBeacon. Por eso el protocolo propio existe.
- `AllowDuplicates` se degrada con la pantalla atenuada o la app en segundo plano. No hay `UIBackgroundModes`.
- El mínimo público de `AdvertisingSetParameters.setInterval` es 160 unidades de 0,625 ms, o sea 100 ms.
- Reiniciar el advertising puede cambiar la dirección privada resoluble. `setAdvertisingData` no debería. Hay que medirlo.
- Android puede omitir los flags en un anuncio no conectable. El interruptor conectable existe para comparar ese caso.
- Un equipo con Android 7 puede no tener modo periférico. La app lo muestra y no emite.
- Algunos fabricantes filtran duplicados en el escáner aunque se pidan todos los anuncios.
- Los relojes de dos equipos no están sincronizados. El identificador es predecible a propósito: es el índice de la ventana, no un TOTP.
- El emisor iOS no existe. El kiosco futuro, si BLE sirve, sería Android.
- No se usa extended advertising, para no dejar afuera radios viejos.

## Próximos pasos

1. Instalar el APK en el Android que haría de kiosco y anotar el modelo, la API y si `canAdvertise` es verdadero.
2. Abrir el proyecto iOS en un Mac, firmarlo y correr `MunicipalBleLabTests` antes de la prueba de campo.
3. Ejecutar las seis pruebas de [`results/PLAN_DE_PRUEBAS.md`](results/PLAN_DE_PRUEBAS.md) con los dos protocolos y, en Android, los dos métodos de emisión.
4. Completar `results/templates/comparacion.csv` con exportaciones reales. Recién ahí se puede decir si alguno de los dos protocolos se acerca al 99 % en 5 segundos.

## Fuera de alcance

TOTP, cifrado, QR, usuarios, base municipal, backend, segundo plano real, extended advertising, emisor iOS y publicación en tiendas.
