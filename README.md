# Municipal BLE Lab

Laboratorio nativo para medir, en dispositivos físicos, si un identificador BLE que cambia cada 15 segundos se puede emitir desde Android y recibir en Android e iOS con la estabilidad que exigiría una fichada.

No es una aplicación de asistencia. No hay usuarios, backend, QR ni criptografía.

## Alcance

- Monorepo con Android (emisor y receptor) e iOS (solo receptor).
- Dos protocolos independientes: iBeacon y un anuncio BLE propio con *service data*.
- Identificador de 32 bits derivado de la ventana UTC, para poder verificarlo sin un canal compartido.
- Métricas locales con reloj monotónico. Las marcas de pared de dos equipos no se tratan como latencia.
- Exportación CSV y JSON de aciertos y de fallos.

El detalle normativo está en [`protocol/SPEC.md`](protocol/SPEC.md). El procedimiento de campo está en [`results/PLAN_DE_PRUEBAS.md`](results/PLAN_DE_PRUEBAS.md).

## Estructura

```text
android/     Kotlin. Módulo :core (sin Android) y módulo :app
ios/         Swift y SwiftUI. Solo receptor
protocol/    Especificación y vectores de prueba compartidos
results/     Plan de pruebas y plantillas. Las exportaciones reales no se versionan
```

Identificador de aplicación: `dev.municipal.blelab`.

## Estado

La especificación, las plantillas y el código se agregan en commits separados. Este archivo se actualiza al cerrar la entrega para distinguir:

- **Implementado:** hay código.
- **Compilado:** el proyecto compiló en esta máquina.
- **Compatible según documentación:** la API existe y el layout cabe en el anuncio legacy.
- **Verificado en hardware:** hay una exportación real de dispositivos físicos.

Hasta ese cierre, nada de lo anterior está verificado en hardware. iOS no se puede compilar en Windows.

## Fuera de alcance

TOTP, cifrado, QR, base municipal, backend, segundo plano real, *extended advertising*, emisor iOS y publicación en tiendas.
