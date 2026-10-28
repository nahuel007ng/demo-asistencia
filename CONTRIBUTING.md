# Flujo de trabajo

Una tarea, una rama corta, un PR. El autor revisa los resultados y fusiona
manualmente su propio PR; no se exige otro revisor. No hay auto-merge ni
publicación o despliegue al fusionar.

1. Leer `AGENTS.md` y comprobar `git status --short --branch`. Conservar cambios
   ajenos y commits sin publicar en su rama; si hace falta, usar otro worktree.
2. Actualizar referencias y crear la rama desde la principal remota, sin arrastrar
   commits locales de `main`:

   ```powershell
   git fetch origin
   git switch -c codex/tarea-corta origin/main
   ```

3. Implementar y ejecutar las verificaciones de abajo. Revisar el diff y agregar
   archivos explícitos, sin secretos, credenciales ni archivos locales ignorados.
   Cada cambio cerrado se commitea por separado con `tipo(ámbito): frase`, usando
   la identidad Git existente, sin atribuciones del agente.
4. Publicar la rama y abrir un borrador con la plantilla:

   ```powershell
   git push -u origin codex/tarea-corta
   gh pr create --draft
   gh pr checks --watch
   ```

5. Resolver errores de CI y registrar fallos preexistentes sin ocultarlos ni
   desactivar checks. Mantener el borrador mientras falten verificaciones
   necesarias. Si cambia `origin/main`, incorporarlo con `git merge origin/main`
   y push normal; no hace falta rebase ni force-push.
6. Revisar código, reportes y pruebas manuales. Marcar listo con `gh pr ready` y
   hacer el merge manual desde GitHub cuando todo lo requerido esté verde.
   Los agentes no fusionan. Después, borrar la rama de la tarea si ya no sirve.

## Verificaciones

Desde `android/`, con el JDK indicado en el [README](README.md):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

`Android` en Actions ejecuta lo mismo con JDK 21, SDK 36 y Build Tools 35.0.0.
`:core` se compila como JVM sin SDK Android; sus tests verifican el contrato de
bytes, clasificación, métricas y simulación contra `protocol/fixtures/`.
Los tests de `:app` cubren el QR y el almacenamiento de eventos. Android Lint
analiza la app; no se agrega un formateador ni una baseline que esconda errores.

El wrapper tiene versión y checksum fijos. Los archivos `gradle.lockfile` fijan
las dependencias resueltas y se verifican en modo estricto. Para actualizar una
dependencia, cambiar su versión, ejecutar el mismo comando con `--write-locks`,
repetir sin esa opción y revisar/versionar los locks junto al cambio.
Para otra variante, generar y revisar también sus locks antes de usarla.
Las acciones están fijadas por SHA: actualizar sus referencias es un cambio
explícito que pasa por PR. La imagen del runner recibe actualizaciones del proveedor.

`iOS` usa macOS 15, Xcode 16.4 y el scheme compartido `MunicipalBleLab`:
valida los plist y el manifiesto SwiftPM, ejecuta XCTest en iPhone 16 / iOS 18.5
con los mismos fixtures del repositorio y corre `xcodebuild analyze`.
No hay paquetes Swift externos que resolver. La compilación de la app para
simulador forma parte de `xcodebuild test`; no requiere firma ni credenciales.
El empaquetado xtool, la firma y la instalación física siguen siendo manuales,
según el README. Si la imagen deja de ofrecer ese Xcode o simulador, actualizar
la selección explícitamente y comprobar los resultados antes del merge.

CI corre en cada PR a `main`, también en borrador, y tras un merge a `main`.
Se puede relanzar manualmente. No se filtran rutas: un cambio de fixtures o
configuración siempre ejecuta ambos checks. Solo se guardan reportes de Android
y resultados XCTest durante siete días; no se suben APK, IPA ni claves.

Compilar no demuestra funcionamiento de BLE ni cámara. Para cambios de radio,
permisos, ciclo de vida o fichada, registrar las pruebas físicas pertinentes del
[plan de pruebas](results/PLAN_DE_PRUEBAS.md) y del
[estado actual](results/ESTADO.md), además de los tests automatizados.
No hay E2E de cámara/radio automatizado. Respetar el alcance móvil y actualizar
SPEC, fixtures y ambas implementaciones cuando cambien bytes, como indica AGENTS.

## Protección de main y revisión opcional

La protección debe exigir PR y los checks `Android` e `iOS` exitosos con la rama
actualizada; cero aprobaciones obligatorias. Debe alcanzar a administradores y
bloquear force-push y borrado. Si el plan o los permisos no lo permiten, mantener
estas reglas como práctica operativa; no cambiar visibilidad ni contratar planes.

No se activa revisión automática de Codex. Si el repositorio tiene acceso a
[Codex Code Review](https://developers.openai.com/codex/integrations/github/),
se puede pedir una revisión puntual comentando `@codex review` en el PR.
Su disponibilidad depende de la conexión y del acceso de la cuenta; CI no depende
de ella. Como alternativa, revisar el diff localmente en Codex.
Las reglas compartidas ya están en `AGENTS.md`; no subir instrucciones privadas
ni configuración local para aportar contexto a una revisión remota.
