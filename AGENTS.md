# Municipal BLE Lab

Laboratorio nativo de la app de asistencia del municipio de Catamarca. Android emite y recibe. iOS solo recibe. No es la app municipal ni la API.

Cada hecho vive en un solo documento. No lo copies a otro.

| Qué | Dónde |
| --- | --- |
| Diseño del sistema | `assets/Especificacion_Sistema_Asistencia_Municipal.md` |
| Bytes y vectores | `protocol/SPEC.md` y `protocol/fixtures/` |
| Qué está medido | `results/INFORME.md` |
| Etapa actual, espera del receptor y cómo leer el 99 % | `results/ESTADO.md` |
| Cómo compilar y qué hace la app de hoy | `README.md` |

Si el diseño y el ensayo discrepan, el ensayo obedece la sección C de `protocol/SPEC.md`. Esa sección no aprueba un contrato con el servidor.

## Alcance

Este trabajo es solo la app móvil. No decidas ni pruebes la API, el enrolamiento, la cola de 72 horas, la fichada asistida real ni el GPS. El GPS no entra en la fichada.

Hay dos modos de radio. No reemplaces uno con el otro.

- El modo ya medido anuncia el índice de la ventana UTC. Sigue disponible para repetir esas pruebas.
- El modo kiosco de la simulación deriva el `uint32` del secreto mock. Secreto `lab-secreto-mock`, kiosco `lab-kiosco-01`, período 15 segundos. El vector es `protocol/fixtures/simulation.json`.

La simulación comparte ese secreto entre kiosco y receptor para poder comprobar el par sin servidor. En el producto la app del agente no tiene el secreto. No conviertas la excepción del ensayo en la regla del producto.

Fuera de este corte: entrada y salida, legajo, firma y cola offline. El aviso de asistencia asistida es texto. No abre el flujo del encargado.

## Al implementar la simulación

- Kiosco solo en Android. Lector de QR en Android y en iOS. Misma generación en Kotlin y en Swift, contra el fixture, antes de abrir la cámara.
- `T` entra como entero de 8 bytes, big-endian. El TOTP es HMAC-SHA1 de 6 dígitos con período 15, no 30. El BLE son los primeros 4 bytes del HMAC, en el anuncio propio de 9 bytes. Se reinicia el anuncio en cada ventana, junto con el QR.
- Un par es válido si el token coincide con el `step` del QR, el BLE es el de ese `step` o el de uno contiguo, y el `step` está a lo sumo a una ventana del reloj del teléfono.
- La espera de 15 segundos empieza cuando la persona inicia la fichada, con la cámara y el BLE a la vez. Corta al primer par válido. Si vence, pide otro intento. Al segundo fallo sugiere la asistencia asistida y deja el reintento disponible.
- Antes de una prueba en hardware, el reloj del kiosco y el del receptor tienen que estar a menos de unos 15 segundos.

## Cómo leer las mediciones

18/20 y 19/20 son series de intentos de laboratorio. Cada intento espera como máximo 5 segundos y después hay 2 segundos de pausa. No son la proporción de teléfonos ni de fichadas. Una fichada necesita una lectura vigente del QR y una del BLE. P95 y P99 de la pantalla son latencias. El detalle está en `results/ESTADO.md`.

No reescribas los números de `results/INFORME.md`. Si una lectura nueva cambia el sentido de una cifra, aclárala en `results/ESTADO.md`.

## Compilar

En esta máquina el `java` del PATH es JDK 25 y no debe correr Gradle. Usa el JDK 21 del JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location android
.\gradlew.bat :core:test :app:assembleDebug
```

No hay Mac. El `.xcodeproj` es el camino en un Mac. xtool no lo compila. La instalación ya hecha en el iPhone 11 usó el paquete SwiftPM con xtool, en Ubuntu 24.04 bajo WSL2. No digas que iOS no se compiló.

No hay Flutter, React Native ni bibliotecas BLE de terceros. `:core` no referencia Android. La pantalla está en español y los identificadores del código en inglés.

Identificador `dev.municipal.blelab`, versión `0.1.0`. No versiones `android/**/build/`, el APK, `ios/.build/` ni `ios/xtool/*.ipa`.

## Commits

Cada cambio que cierra se commitea solo, en cuanto queda verde. Un commit es una sola cosa: el test con el código que lo hace pasar, la pantalla que lo usa, o el documento que lo anota.

No dejes el árbol con cambios mezclados de otra tarea. No dejes basura de herramienta ni carpetas de build. El mensaje dice qué cambió, en el estilo `tipo(ámbito): frase`.

## Al cerrar un cambio

Un cambio de bytes se hace en `protocol/SPEC.md`, en los fixtures y en las dos implementaciones. Un cambio de etapa se anota en `results/ESTADO.md` y, si cambia el diseño, en la especificación. No dejes la decisión solo en el chat.
