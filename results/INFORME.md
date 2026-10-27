# Informe — Municipal BLE Lab

Laboratorio nativo para decidir si un identificador BLE que rota con la hora UTC se puede emitir desde un Android y recibir en Android y en iOS con la estabilidad de una fichada corta. No es la aplicación de asistencia: no hay usuarios, backend, QR, base municipal ni criptografía.

Con las pruebas de este informe, la arquitectura queda validada para construir un prototipo. Un kiosco Android emite BLE propio y el agente, Android o iPhone, lee el mismo código. No hace falta un formato por plataforma.

Fecha de las mediciones en hardware: 25 de octubre de 2026. Versión de la app: `0.1.0`. Identificador: `dev.municipal.blelab`.

## Cómo se creó

El trabajo se hizo en Windows, en un monorepo, sin Flutter, React Native ni bibliotecas BLE de terceros. Android usa la API del sistema. iOS usa Core Bluetooth y Core Location.

1. Se escribió el contrato de bytes en `protocol/SPEC.md`, con vectores compartidos en `protocol/fixtures`. Esos vectores fijan ventanas UTC, el payload propio, el payload iBeacon, la clasificación y los percentiles.
2. La lógica que no depende del teléfono se implementó dos veces, en Kotlin y en Swift, contra los mismos vectores: calendario de ventanas, codecs, clasificación, métricas y exportación.
3. La app Android se armó en dos módulos Gradle. `:core` no tiene Android. `:app` tiene la pantalla en Jetpack Compose, el emisor, el receptor y el servicio en primer plano. Se compiló con el JDK 21 del JBR de Android Studio, porque el `java` del PATH de esta máquina es JDK 25 y no puede correr este Gradle. El APK de depuración se instaló por `adb` en el Redmi Note 14 y en el Redmi Pad Pro.
4. Dos fallos de arranque se corrigieron antes de medir. El servicio en primer plano de tipo `connectedDevice` no puede arrancar en Android 14 o superior si todavía no hay un permiso de Bluetooth concedido: la pantalla ahora solo se vincula al servicio y lo promueve después del permiso. El directorio de la sesión tiene que existir antes de abrir `events.csv`: los inicializadores de propiedades de Kotlin corren antes del `init`.
5. La app iOS se escribió como proyecto Xcode, destino iOS 15, solo receptora. En esta PC no hay Mac. Para instalarla en el iPhone se agregó un paquete SwiftPM (`ios/Package.swift`, `ios/xtool.yml`) y se usó [xtool](https://github.com/xtool-org/xtool) 1.23.0 dentro de Ubuntu 24.04 en WSL2, con el SDK extraído de Xcode 27.1. La firma usó una clave de API de App Store Connect de la cuenta paga de Apple Developer. El `.ipa` se instaló por USB en el iPhone 11. El proyecto Xcode sigue siendo el camino para abrir la app en un Mac. xtool no compila un `.xcodeproj`.
6. Cada sesión se exporta como `blelab.session` versión 1. Los JSON de hardware están en `assets/`. No forman parte de la app.

## Arquitectura

Hay tres capas. El protocolo no conoce la plataforma. Cada app traduce ese protocolo a la radio del sistema y guarda la misma sesión.

```text
protocol/SPEC.md + fixtures
        |
        +-- android/core     Kotlin puro: ventana, codecs, métricas, JSON
        +-- ios/.../Core     el mismo algoritmo en Swift
        |
        +-- android/app      radio, servicio, pantalla, archivos
        +-- ios/MunicipalBleLab   radio, pantalla, archivos
```

El kiosco futuro, si este BLE se usa para fichar, es un Android con la pantalla encendida: tiene que mostrar el QR. El agente también tiene la pantalla encendida mientras abre la app. En el laboratorio, Android emite y recibe. iOS solo recibe. No hay segundo plano en iOS.

### Identificador

El código no es un contador ni un TOTP. Los dos equipos lo calculan con su reloj UTC:

```text
windowIndex = floor(unixEpochSeconds / periodSeconds)
identifier  = windowIndex mod 2^32
major       = bits 16..31
minor       = bits 0..15
```

El período del prototipo es 15 segundos. En el instante exacto del límite, el identificador ya es el de la ventana nueva. Un receptor acepta el paquete si el código coincide con la ventana actual de su propio reloj. La gracia quedó en 0.

La latencia se mide con el reloj monotónico del aparato que observa. Restar `wall_time_utc` del emisor contra el del receptor no es una latencia: los relojes no están sincronizados.

### Dos anuncios, una decisión

El laboratorio puede emitir dos formatos, para poder compararlos. El prototipo se queda con uno.

| Formato | Qué viaja en el anuncio | Quién lo recibe |
| --- | --- | --- |
| BLE propio | Service data de 9 bytes. Versión, flags, identificador uint32, período uint16 y potencia medida. Service UUID `C4A15E20-6B17-4D3E-9F80-11A2B3C4D5E6`. No se agrega la lista de servicios de 128 bits: no entra en los 31 bytes del anuncio legacy. | Android con `BluetoothLeScanner`. iOS con Core Bluetooth, en primer plano, filtrando en la app por el service UUID. |
| iBeacon | Payload Apple `02 15`, UUID `7B2E4A10-9C3D-4F58-A1E6-2D8B5C04F917`, major y minor. | Android filtrando el company id `0x004C`. iOS solo con Core Location, porque Core Bluetooth no entrega esos bytes a la app. |

El anuncio propio va no conectable, intervalo 100 ms, potencia de transmisión alta, sin identificador fijo. En cada borde de ventana Android corta el anuncio y lo vuelve a iniciar. Ese método se llama Reiniciar. Actualizar el payload de un `AdvertisingSet` sin reiniciarlo también se implementó y se midió. No queda para el prototipo: después de apagar el Bluetooth de la tablet, el código viejo siguió en el aire junto con el nuevo.

### Android

`:core` calcula y no toca la radio. `:app` hace el resto.

- `LabAdvertiser` arma el anuncio legacy y lo rota.
- `LabScanner` escucha en baja latencia, solo anuncios legacy, y pide duplicados.
- `LabController` es el estado de la sesión: configuración, rotación, captura de 5 segundos y recuperación si se apaga el Bluetooth.
- `LabSessionService` mantiene el trabajo en primer plano con el tipo `connectedDevice`. En API 34 o superior solo pasa a primer plano cuando ya hay `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` o `BLUETOOTH_ADVERTISE`.
- `EventStore` escribe `events.csv`, `events.jsonl`, `summary.json` y el `session.json` exportable bajo el directorio de documentos de la app.
- La pantalla, en Compose, está en español. Los identificadores del código están en inglés.

La captura de laboratorio dura como máximo 5 segundos por intento, con 2 segundos de pausa, 20 intentos por defecto. Si el código vigente llega antes, el intento termina en ese momento. Esos 20 no son 20 anuncios ni caben en una ventana de 15 segundos: el anuncio se repite cada 100 ms, y una fichada necesita una sola lectura vigente. Un intento vacío se anota `no_signal`. Un código que no es el de la ventana actual se anota `not_current`. Esos intentos no entran en los percentiles. Los percentiles son nearest-rank. El 18/20 o el 19/20 de más abajo es el resultado de esa serie de intentos, no el porcentaje de teléfonos ni de fichadas. Cómo se lee frente al 99 % está en [`ESTADO.md`](ESTADO.md).

### iOS

La pantalla es SwiftUI y también está en español. `CustomScanner` lee el BLE propio. `BeaconRanger` hace el ranging iBeacon. `LabController` y `EventStore` repiten el papel de Android, guardando el mismo esquema `blelab.session`. Mientras la recepción está pedida, el temporizador de bloqueo de pantalla queda desactivado. No hay `UIBackgroundModes`.

Para instalar sin Mac, xtool compila el paquete SwiftPM, lo firma con la clave de App Store Connect y lo copia por USB. El bundle que queda en el teléfono lleva un prefijo que xtool agrega al identificador original. El nombre visible es BLE Lab.

### Qué simplifica en el negocio

El kiosco calcula un código y lo anuncia. El agente calcula el mismo código y acepta el paquete si coincide. Android e iOS comparten la regla, el período y el formato. No hay un anuncio iBeacon para el iPhone y otro propio para Android, ni dos criterios para dar la fichada por válida.

## Árbol del repositorio

Se listan las fuentes. `android/**/build/`, el APK, `ios/xtool/*.ipa` y `ios/.build/` son productos de compilación y no se versionan como código.

```text
demo-asistencia/
├── README.md
├── protocol/
│   ├── SPEC.md
│   └── fixtures/
│       ├── windows.json
│       ├── custom-payload.json
│       ├── ibeacon-payload.json
│       ├── classification.json
│       └── percentiles.json
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── gradlew
│   ├── gradlew.bat
│   ├── gradle/wrapper/
│   ├── core/
│   │   └── src/
│   │       ├── main/kotlin/dev/municipal/blelab/core/
│   │       │   ├── Bytes.kt
│   │       │   ├── CaptureRunner.kt
│   │       │   ├── Classification.kt
│   │       │   ├── CustomCodec.kt
│   │       │   ├── IBeaconCodec.kt
│   │       │   ├── IdentifierSchedule.kt
│   │       │   ├── JsonText.kt
│   │       │   ├── LabConstants.kt
│   │       │   ├── Percentiles.kt
│   │       │   ├── RunningStats.kt
│   │       │   ├── SessionEvent.kt
│   │       │   ├── SessionExport.kt
│   │       │   └── UtcTime.kt
│   │       └── test/kotlin/dev/municipal/blelab/core/
│   │           ├── FixtureFiles.kt
│   │           ├── MeasurementTest.kt
│   │           └── ProtocolTest.kt
│   └── app/
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── java/dev/municipal/blelab/
│           │   │   ├── BleLabApp.kt
│           │   │   ├── MainActivity.kt
│           │   │   ├── ble/
│           │   │   │   ├── BleCapabilities.kt
│           │   │   │   ├── BleErrors.kt
│           │   │   │   ├── LabAdvertiser.kt
│           │   │   │   ├── LabScanner.kt
│           │   │   │   ├── Payloads.kt
│           │   │   │   └── ScanParse.kt
│           │   │   ├── service/LabSessionService.kt
│           │   │   ├── session/
│           │   │   │   ├── EventStore.kt
│           │   │   │   ├── LabController.kt
│           │   │   │   └── SessionConfig.kt
│           │   │   └── ui/
│           │   │       ├── LabApp.kt
│           │   │       └── LabViewModel.kt
│           │   └── res/
│           └── test/kotlin/dev/municipal/blelab/session/EventStoreTest.kt
├── ios/
│   ├── MunicipalBleLab.xcodeproj/
│   ├── Package.swift
│   ├── xtool.yml
│   ├── XtoolInfo.plist
│   ├── MunicipalBleLab/
│   │   ├── MunicipalBleLabApp.swift
│   │   ├── Info.plist
│   │   ├── PrivacyInfo.xcprivacy
│   │   ├── Ble/
│   │   │   ├── BeaconRanger.swift
│   │   │   └── CustomScanner.swift
│   │   ├── Core/
│   │   │   ├── Classification.swift
│   │   │   ├── Codecs.swift
│   │   │   ├── IdentifierSchedule.swift
│   │   │   ├── LabConstants.swift
│   │   │   ├── Metrics.swift
│   │   │   ├── SessionExport.swift
│   │   │   └── UtcTime.swift
│   │   ├── Session/
│   │   │   ├── EventStore.swift
│   │   │   └── LabController.swift
│   │   └── UI/LabScreen.swift
│   └── MunicipalBleLabTests/LabCoreTests.swift
├── results/
│   ├── PLAN_DE_PRUEBAS.md
│   ├── INFORME.md
│   ├── ESTADO.md
│   └── templates/
│       ├── session.json
│       ├── session.csv
│       └── comparacion.csv
└── assets/
    ├── session-ip11.json
    ├── session-ip11-3mts.json
    ├── session-nueva-prueba.json
    ├── session-testip11-redmi14.json
    ├── session-bt-apagar-14-14.json
    ├── session-bt-apagar-14-pad.json
    └── analizados/
```

`assets/` guarda las sesiones exportadas del laboratorio. `assets/analizados/` tiene las sesiones Android anteriores: par iBeacon, par BLE propio de cerca, captura de 5 s, distancia de 3–3,5 m y cortes de Bluetooth.

## Configuración que queda para el prototipo

El kiosco y el agente tienen la pantalla encendida. La visita de referencia es de unos 15 segundos en Android y unos 30 segundos en iOS.

| Parámetro | Valor |
| --- | --- |
| Protocolo | BLE propio |
| Método de emisión | Reiniciar el anuncio en cada ventana |
| Período | 15 s |
| Intervalo | 100 ms |
| Conectable | No |
| Identificador fijo | No |
| Gracia | 0. Solo vale el código de la ventana actual |
| Potencia | Alta en el emisor |

Actualizar, iBeacon, otros períodos, otros intervalos, el anuncio conectable, la gracia de 2 segundos, el identificador fijo, la emisión con la pantalla apagada y una sesión de varias horas quedaron fuera. No cambian esta decisión.

## Equipos

| Equipo | Modelo | Sistema | Papel en las pruebas |
| --- | --- | --- | --- |
| Redmi Pad Pro | 2405CRPFDG | Android 16 | Emisor hacia el Note 14 |
| Redmi Note 14 | 24117RN76L | Android 16, HyperOS | Receptor del Pad. Emisor hacia el iPhone |
| iPhone 11 | iPhone12,1 | iOS 26.3.1 | Receptor del Note 14 |

## Resultados

### Android, de cerca

BLE propio y Reiniciar. El Note 14 vio todas las ventanas del Pad Pro: 585 observaciones, códigos 119520736 a 119520742, RSSI mediano −46 dBm, sin código viejo y sin errores. El código nuevo apareció a unos 0,15 s del límite. La primera separación fue de 10,2 s porque la emisión arrancó a mitad de ventana. Las demás quedaron entre 14,8 y 15,1 s.

La captura de 5 segundos, 20 intentos, dio 20 de 20. Media 75 ms, mediana 55 ms, P95 163 ms, máximo 204 ms. RSSI mediano −48 dBm.

iBeacon, en el mismo par y de cerca, dio cero observaciones en el receptor. El emisor sí registró sus callbacks. Ese formato no sirve para este par.

### Android, a 3–3,5 m

BLE propio, los dos métodos, 20 de 20 en cada uno.

| Método | Mediana | Peor caso | RSSI mediano |
| --- | --- | --- | --- |
| Reiniciar | 55 ms | 1929 ms, en un límite de ventana | −68 dBm |
| Actualizar | 43 ms | 2005 ms, en un límite de ventana | −69 dBm |

En régimen, Actualizar no dejó código viejo. Al apagar el Bluetooth de la tablet, sí. Reiniciar tardó unos 8 s en volver y no dejó el código anterior: 19 de 20. Actualizar tardó unos 21 s y el código viejo siguió llegando junto con los nuevos hasta el final de la sesión: también 19 de 20, con un fallo `not_current` por ese código viejo. Por eso el prototipo usa Reiniciar.

Al apagar el Bluetooth del Note 14 durante unos 10 s, con el Pad en Reiniciar, la recepción volvió sin código viejo: 19 de 20. El fallo fue un paquete de la ventana siguiente que llegó mientras la radio se apagaba.

### iOS, de cerca

El Note 14 emitió BLE propio con Reiniciar. El iPhone recibió unos 75 segundos y vio todos los códigos que realmente salieron al aire. La captura fue 18 de 20, mediana 118 ms, RSSI mediano −55 dBm.

Los dos fallos fueron `not_current`. A las 22:01:45 el Redmi volvió a anunciar 119531046 en lugar de pasar a 119531047, y a las 22:02:00 saltó a 119531048. El 047 no estuvo en el aire. En la emisión siguiente, de 22:12:17 a 22:14:34, nueve rotaciones seguidas, del 119531089 al 119531098, ese salto no se repitió.

### iOS, a 3,5 m

Una visita de 29,4 s, de 22:13:58 a 22:14:28. El primer paquete llegó a los 90 ms. Hubo 389 observaciones, RSSI mediano −67 dBm, entre −64 y −90. El iPhone vio 119531095 y, en los dos límites que cayeron adentro, 096 a las 22:14:00,391 y 097 a las 22:14:15,396. El 098 salió cuando la recepción ya estaba cerrada. `missedWindows` quedó en 0. Los seis paquetes clasificados `previous` son el código viejo durante los primeros 0,2 s de cada cambio. La separación mediana entre paquetes fue 52 ms. Dos huecos de 1,5 s y 1,7 s no hicieron perder el código.

Esa visita es la única de la serie de 3,5 m que quedó exportada. Los reinicios anteriores de recepción no están en el JSON. El emisor de esa prueba es el Note 14. El Pad ya había emitido el mismo formato hacia el Note 14 a 3–3,5 m.

## Conclusión

La arquitectura es válida para un prototipo con esta configuración. El mismo BLE propio sirve para el agente Android y para el agente iOS, y la regla de negocio es una: el código anunciado tiene que ser el de la ventana UTC actual.

Eso no es todavía una prueba de producción. Falta el backend, el QR, la base municipal y una muestra más amplia de teléfonos y de locales. La evidencia de hardware es un Pad Pro, un Note 14 y un iPhone 11, a unos 3–3,5 m, con las dos pantallas encendidas.

## Lectura frente al sistema

La clasificación de esta demo compara el código recibido con la ventana UTC del propio receptor. El identificador es el índice de esa ventana, calculado en los dos teléfonos, sin secreto. Sirve para medir la radio.

En el sistema de asistencia la app del agente no calcula el código. QR y BLE llevan valores distintos, derivados del secreto del kiosco, y la API es quien los valida. El paso siguiente de este repositorio no llama a esa API: simula el flujo en el teléfono con un secreto mock. El alcance está en [`ESTADO.md`](ESTADO.md).
