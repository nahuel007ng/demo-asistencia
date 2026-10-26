# Municipal BLE Lab

Laboratorio nativo del sistema de asistencia del municipio de Catamarca. Mide, en dispositivos físicos, si un identificador BLE que cambia cada 15 segundos se puede emitir desde Android y recibir en Android e iOS durante una fichada corta, con la pantalla encendida.

El diseño del sistema está en [`assets/Especificacion_Sistema_Asistencia_Municipal.md`](assets/Especificacion_Sistema_Asistencia_Municipal.md). Las mediciones del 25 de octubre de 2026 están en [`results/INFORME.md`](results/INFORME.md). El estado del proyecto y los pasos siguientes están en [`results/ESTADO.md`](results/ESTADO.md).

Este repositorio es la app de laboratorio, no la aplicación municipal ni la API. La demo ya medida clasifica el BLE con el índice de la ventana UTC. El modo Kiosco suma el QR con un secreto mock. El receptor lee el QR y el BLE, y un mock en el teléfono comprueba el par. No hay llamada a un servidor. El detalle está en [`results/ESTADO.md`](results/ESTADO.md).

El contrato de bytes está en [`protocol/SPEC.md`](protocol/SPEC.md). El procedimiento de campo está en [`results/PLAN_DE_PRUEBAS.md`](results/PLAN_DE_PRUEBAS.md).

## Alcance del laboratorio

- Android emite y recibe. iOS solo recibe.
- Dos protocolos independientes, medidos para elegir uno: iBeacon y un anuncio BLE legacy con *service data*. La demo se quedó con el anuncio propio.
- Identificador de 32 bits derivado de la ventana UTC. También se puede fijar para la prueba estática.
- Dos métodos de emisión en Android: reiniciar el anuncio, o actualizar el payload con `AdvertisingSet` sin reiniciar el conjunto. La demo se quedó con reiniciar.
- Métricas con el reloj monotónico del equipo que mide. La resta de relojes de pared entre dos teléfonos no es una latencia.
- CSV y JSON de aciertos y de fallos. Un arranque rechazado queda en su propia sesión.

## Estructura

```text
AGENTS.md    Instrucciones para quien modifica este repositorio
android/     Kotlin. :core sin Android, :app con emisor, receptor y pantalla
ios/         Swift y SwiftUI. Solo receptor. Proyecto Xcode y paquete xtool
protocol/    Especificación y vectores compartidos
results/     Informe, estado del proyecto, plan de pruebas y plantillas
assets/      Especificación del sistema y sesiones exportadas de la demo
```

Identificador de aplicación, en las dos plataformas: `dev.municipal.blelab`. Versión `0.1.0`.

`results/exports/` no se versiona. Las sesiones de esta demo están en `assets/` y en `assets/analizados/`.

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
| Xcode de referencia | 15 o superior para el `.xcodeproj`, sin `CLMonitor`. La instalación en el iPhone 11 usó el SDK de Xcode 27.1 con xtool |

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

El camino para abrir la app en un Mac es `ios/MunicipalBleLab.xcodeproj`, scheme `MunicipalBleLab`, un iPhone o iPad con iOS 15 o superior, y un Development Team en Signing. El identificador ya es `dev.municipal.blelab`. xtool no compila un `.xcodeproj`.

Los tests leen `protocol/fixtures` desde el árbol del repositorio, no desde una copia. En el scheme de test:

```text
xcodebuild test -project ios/MunicipalBleLab.xcodeproj -scheme MunicipalBleLab -destination 'platform=iOS Simulator,name=iPhone 15'
```

En esta PC no hay Mac. La instalación en el iPhone 11 se hizo con el paquete SwiftPM (`ios/Package.swift`, `ios/xtool.yml`) y [xtool](https://github.com/xtool-org/xtool) 1.23.0 dentro de Ubuntu 24.04 en WSL2, con el SDK extraído de Xcode 27.1. La firma usó una clave de API de App Store Connect. El `.ipa` se instaló por USB. El bundle que queda en el teléfono lleva un prefijo que xtool agrega al identificador original. El nombre visible es BLE Lab.

Ese scheme de Xcode no se corrió en un Mac durante esta entrega. Los tests XCTest tampoco.

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

La captura espera como máximo 5 segundos y, si el código vigente llega antes, termina en ese momento. Después hay 2 segundos de pausa. El valor por defecto es 20 repeticiones, editable de 1 a 500. Esos 20 son intentos de laboratorio, no anuncios dentro de la ventana de 15 segundos: el anuncio sale cada 100 ms y una fichada necesita una sola lectura vigente. Cada intento escribe `capture_attempt` con éxito o con `no_signal` / `not_current`. No hay reintentos silenciosos.

Android escucha con `BluetoothLeScanner` en modo de baja latencia, solo anuncios legacy, y reporta duplicados. iBeacon se filtra por company id `0x004C`, tipo `02 15` y el UUID del laboratorio; major y minor quedan fuera de la máscara. El BLE propio se filtra por service data del UUID propio.

iOS no ve el fabricante de un iBeacon por Core Bluetooth. El ranging es el de Core Location y suele rondar un aviso por segundo. Puede repetir el major y minor anterior: cada entrega se guarda y se clasifica. El BLE propio escanea sin filtro de servicio, porque el anuncio no incluye la lista de 128 bits (no entra en 31 bytes junto con el service data), y se queda con los paquetes cuyo `CBAdvertisementDataServiceDataKey` trae el UUID del laboratorio. El interruptor de duplicados pide `AllowDuplicates`. iOS puede ignorarlo si baja el brillo o si la app deja de estar activa. Al pasar a segundo plano se escribe `APP_INACTIVE`; al volver, `recovery`.

El agrupado de la lista es solo visual. El archivo guarda cada anuncio.

## Métricas y exportación

En la pantalla, el resumen muestra intentos, porcentaje, media, P50, P95 y P99 de las capturas exitosas, el tiempo hasta un código mayor que el anterior, ventanas omitidas estimadas con el reloj local, clases `current` / `previous` / `next` / `other`, errores y recuperaciones. El porcentaje es de intentos de esa serie. P95 y P99 son latencias de los intentos que salieron bien. Ninguno de los dos es la proporción de fichadas del municipio. Si el porcentaje de la muestra llega a 99, el texto lo dice. Una muestra chica no decide el sistema. La lectura operativa está en [`results/ESTADO.md`](results/ESTADO.md).

Los percentiles son nearest-rank: posición `ceil(p/100*N)` desde 1. Los timeouts no entran en el percentil. Las ventanas omitidas se estiman entre la primera y la última observación; si el tramo supera 20 000 ventanas, el resumen dice que no está estimado.

Android guarda la sesión en `Documents/sessions` de la app (`getExternalFilesDir`). iOS la guarda en Documents del sandbox. Cada carpeta tiene `events.csv`, `events.jsonl`, `summary.json` y, al exportar, `session.json`. El esquema es `blelab.session` versión 1. La plantilla vacía está en [`results/templates/session.json`](results/templates/session.json). La hoja para comparar equipos es [`results/templates/comparacion.csv`](results/templates/comparacion.csv).

Para la latencia de una captura hay que usar `capture_latency_ms` o la resta de `monotonic_ms` del mismo archivo. `wall_time_utc` sirve para ver en qué ventana cayó el código, no para restar el emisor contra el receptor.

## Configuración que hereda el prototipo

Medida el 25 de octubre de 2026. Los números están en el [informe](results/INFORME.md).

| Parámetro | Valor |
| --- | --- |
| Protocolo | BLE propio |
| Método de emisión | Reiniciar el anuncio en cada ventana |
| Período | 15 s |
| Intervalo | 100 ms |
| Conectable | No |
| Identificador fijo | No |
| Gracia | 0. En el laboratorio solo vale el código de la ventana actual |
| Potencia | Alta en el emisor |
| Pantalla | Encendida en el kiosco y en el agente |

La visita de referencia, con el kiosco ya anunciando, es de unos 15 segundos en Android y unos 30 segundos en iOS.

## Estado al 25 de octubre de 2026

| Pieza | Implementado | Compilado | En hardware |
| --- | --- | --- | --- |
| Codec, ventanas, percentiles y exportación | sí, Kotlin y Swift | Kotlin sí, `:core:test` | la lógica, contra `protocol/fixtures`; la radio, en la demo |
| Emisor Android, reinicio y `AdvertisingSet` | sí | sí, `:app:assembleDebug` | sí. El prototipo usa reinicio. `AdvertisingSet` exige API 26 y quedó medido |
| Escáner Android de los dos protocolos | sí | sí, el mismo APK | sí. iBeacon dio cero observaciones en el par Android de la demo |
| Pantalla Android y servicio en primer plano | sí | sí, el mismo APK | sí, con la pantalla encendida. El servicio es `connectedDevice` |
| Receptor iOS, ranging y Core Bluetooth | sí | sí, con xtool 1.23.0, instalado en el iPhone 11 | sí para el BLE propio, en primer plano. El `.xcodeproj` no se abrió en un Mac |
| Tests XCTest del receptor iOS | sí, los mismos vectores | no en un Mac | el algoritmo de Swift está escrito contra los mismos vectores; XCTest no se ejecutó |

`:core:test` y `:app:assembleDebug` terminaron en `BUILD SUCCESSFUL` con el JDK 21 de Android Studio, en Windows. El APK de depuración se instaló por `adb` en el Redmi Note 14 y en el Redmi Pad Pro.

## Límites que siguen vigentes

- El modo medido sigue anunciando el índice de la ventana UTC. El modo Kiosco, solo Android, deriva el QR y el BLE del secreto mock. La app del producto no guarda el secreto: lo valida la API, y esa validación queda fuera de este repositorio.
- Al cruzar la ventana, el iPhone vio el código anterior unos 0,2 s. Core Bluetooth no entrega el fabricante de un iBeacon. El prototipo usa el anuncio propio.
- `AllowDuplicates` se degrada con la pantalla atenuada o la app en segundo plano. No hay `UIBackgroundModes`. La demo se hizo con la pantalla encendida y la app visible.
- El mínimo público de `AdvertisingSetParameters.setInterval` es 160 unidades de 0,625 ms, o sea 100 ms.
- Reiniciar el advertising puede cambiar la dirección privada resoluble. Se eligió ese método porque actualizar el payload dejó el código viejo en el aire después de apagar el Bluetooth del emisor.
- Android puede omitir los flags en un anuncio no conectable. El interruptor conectable sigue en el laboratorio para comparar ese caso. El prototipo anuncia no conectable.
- Un equipo con Android 7 puede no tener modo periférico. La app lo muestra y no emite. Los equipos medidos son Android 16.
- Algunos fabricantes filtran duplicados en el escáner aunque se pidan todos los anuncios.
- Los relojes de dos equipos no están sincronizados. Restar `wall_time_utc` entre emisor y receptor no es una latencia.
- El emisor iOS no existe. El kiosco es Android.
- No se usa extended advertising, para no dejar afuera radios viejos.
- La muestra es un Pad Pro, un Note 14 y un iPhone 11, a unos 3–3,5 m. En iOS la serie de cerca fue 18 de 20 intentos, por un salto de ventana del emisor. Eso no es la proporción de teléfonos que no pueden fichar.

## Próximos pasos

La radio BLE ya está elegida. La simulación QR + BLE está en la app y no llama a la red. La codificación está en [`protocol/SPEC.md`](protocol/SPEC.md), sección C. El detalle operativo está en [`results/ESTADO.md`](results/ESTADO.md).

En Android, el protocolo Kiosco muestra el QR y anuncia el BLE propio con Reiniciar. En el receptor de Android y de iOS, Fichar abre la cámara y el BLE a la vez. Si el mock del teléfono acepta el par, la pantalla dice `Ingreso correcto`. El registro se exporta como hasta ahora.

Falta la pasada en el Pad Pro, el Note 14 y el iPhone 11, con los relojes a menos de unos 15 segundos.

## Fuera de alcance de este repositorio

Usuarios, base municipal, API, validación en un servidor, enrolamiento, cola offline, fichada asistida, segundo plano real, extended advertising, emisor iOS y publicación en tiendas.

El QR entra solo como simulación de laboratorio. Las decisiones de backend siguen abiertas en la especificación y no las cierra esta app.
