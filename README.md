# Synex Host v0.6 — Android autónomo (corrección de instalación)

**No requiere Termux, root, Python, tokens ni una computadora al instalar la APK.**

La aplicación Kotlin administra localmente los servidores en su almacenamiento privado. Incluye OpenJDK ARM64 17/21 dentro de la APK y extrae automáticamente la versión requerida al instalar Paper. No contiene los archivos de Mojang ni el servidor Paper: se descargan de los servicios oficiales bajo demanda, con comprobación SHA-256. El usuario acepta explícitamente la EULA antes de iniciar Minecraft.

## Cómo utilizar

1. Instala la APK en **Android ARM64** (mínimo Android 8.0). Se necesita memoria y espacio de almacenamiento suficiente.
2. Abre Synex Host. No requiere configurar nada externo.
3. Selecciona el servidor preconfigurado **Mi BoxPvP** (Paper 1.20.1, RAM 2 GB) y pulsa **Instalar Paper**, o usa **+** para crear un servidor con nombre, versión y memoria. Los nuevos servidores comienzan a instalarse automáticamente.
4. La app prepara Java 17/21 desde los assets incluidos y descarga la build STABLE de Paper que corresponda.
5. Acepta la EULA de Minecraft cuando aparezca la opción y pulsa **Iniciar servidor**.
6. Accede a consola y archivos desde las pestañas. Los servidores utilizan los puertos LAN 25565, 25566, etc.

## Arquitectura

- `android-app/.../LocalEngine.kt`: gestión Kotlin de instancias, consola, instalación y directorios.
- `android-app/.../EmbeddedRuntime.kt`: instalación de OpenJDK ARM64 empaquetado y puesta en marcha mediante el ejecutable procedente de `nativeLibraryDir`, evitando lanzar binarios desde directorios modificables por Android 10+.
- `android-app/.../HostService.kt`: servicio foreground con notificación y bloqueo parcial de CPU.
- `.github/workflows/android-debug.yml`: descarga distribuciones OpenJDK ARM64 verificadas por SHA-256 y las empaqueta dentro de la APK.

**Limitaciones actuales:** la APK puede compilarse con CI, pero todavía requiere ensayos reales del arranque del JVM/Paper en dispositivos ARM64. En particular, no está demostrado que las dependencias nativas del JRE y los permisos de ejecución funcionen en todos los fabricantes. La app solo implementa Paper para Minecraft compatible con Java 17/21; para versiones más antiguas o que requieran Java 25 hacen falta otros runtimes. El servidor puede terminar si Android mata el proceso; el servicio foreground ayuda, pero no lo garantiza indefinidamente.

**Nota:** los servidores anteriores creados dentro de Termux no se migran automáticamente: Android restringe el acceso entre aplicaciones. No desinstales versiones anteriores sin exportar mundos que quieras conservar.

## Código y licencias

- Runtime JRE 17/21 ARM64 distribuido a partir de [AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases) (OpenJDK, GPLv2 con Classpath Exception); para fuentes y avisos legales consulta el proyecto y sus archivos de licencias. Los paquetes se descargan automáticamente durante CI, con hashes SHA-256 fijos en el workflow.
- PaperMC se descarga de [fill.papermc.io](https://fill.papermc.io), no está incluido en el repositorio ni en la APK.
- La EULA de Minecraft: https://aka.ms/MinecraftEULA.

## Desarrollo

Para una APK: ejecuta el workflow **Android standalone APK** en GitHub Actions y descarga el artifact `SynexHost-standalone-debug-apk`. El código Python original permanece bajo `backend/` solo como referencia/pruebas de compatibilidad; **la APK ya no lo utiliza**.

## Correcciones v0.6
- Se reparó la extracción de enlaces simbólicos relativos que OpenJDK utiliza para sus archivos legales. Ya no se rechaza todo enlace con `..`; se valida la ruta resultante contra el directorio de extracción para bloquear escapes.
- Indicador de etapa de instalación, porcentaje de descarga de Paper cuando el servidor publica Content-Length y opción de reintento/ver consola ante errores.
- Validación CI de la estructura de OpenJDK 17/21 y enlaces legítimos, antes de compilar.
- Descargas Paper restringidas a HTTPS y el host oficial, sin redirecciones automáticas.
- **La compilación no demuestra por sí sola que Java/Paper arranquen en un teléfono real.** Se necesita probarlo en Android ARM64.

## Correcciones de v0.6
Java se ejecuta desde `runtimes/jreXX/bin/java`; ya no se lanza una copia fuera del directorio JRE. Se restauran los permisos de ejecución al preparar un runtime, incluso después de actualizar una instalación anterior. Se comprueba `java -version` en el propio teléfono antes de descargar Paper.

Esta versión sideload utiliza `targetSdk=28` como compromiso de compatibilidad con la ejecución de código en el almacenamiento privado de Android. No es adecuada para publicar en Play Store y reduce las protecciones de Android modernas. El runtime y el servidor todavía necesitan una prueba física ARM64.

## v0.6 — Informe de diagnóstico para errores persistentes

Cuando falle una instalación, en la tarjeta de error pulsa «Copiar diagnóstico para soporte». Copia la versión instalada, API de Android, ABI, archivos presentes de JRE, permisos del ejecutable, tamaño de Paper, fase exacta del fallo y los últimos 65 mensajes de consola, incluyendo un fragmento de la excepción. El informe solo se prepara **localmente** y no se envía a ningún servicio; puedes revisar lo copiado antes de compartirlo (los registros de servidores y plugins pueden contener información sensible).

La compilación en CI comprueba que la APK contiene Java 17/21, pero **no reproduce las restricciones de tu teléfono físico**. Esta versión diagnostica el problema y no garantiza por sí misma que Paper pueda arrancar en todos los dispositivos.

## Synex Host v0.7 — Android 13 / Paper y administración
- **Compatibilidad del motor**: se añade `android:allowNativeHeapPointerTagging="false"` para el aborto con el mensaje `Pointer tag ... was truncated` en Android 11+; según Android AOSP es una vía de compatibilidad que reduce una protección de seguridad frente a errores de memoria. No se considera una solución de seguridad a largo plazo.
- **IP LAN visible y copiable**: la pantalla principal muestra la IPv4 de Wi-Fi/Ethernet y el puerto efectivo de `server.properties`. Es una dirección privada; no es una IP pública ni expone el servidor a Internet sin configurar la red.
- **Servicio al minimizar**: continúa como *foreground service* con notificación, bloqueo parcial de CPU y bloqueo de Wi-Fi mientras el servidor esté vivo. Minimizar la interfaz o retirarla de recientes no debería cerrarlo por sí solo. Forzar detención, reiniciar el dispositivo o límites de batería/memoria pueden acabar con el servidor; no se garantiza 24/7.
- **Archivos**: crear archivos de texto y carpetas, renombrar y eliminar con confirmación solo cuando esté detenido, además de editar, explorar y subir los tipos compatibles existentes.
- **Backups**: exportación a ZIP con el selector de documentos de Android, solo con el servidor apagado para evitar mundos inconsistentes. Incluye archivos de servidor y mundo; máximo de 2 GB descomprimidos.
- **RAM**: editar los límites de Java de cada servidor apagado entre 512 MB y 16 GB, en pasos de 256 MB.
- **Consola**: accesos directos a `list`, `save-all`, `tps`, `whitelist list` y `time set day`; logs rotativos persistidos y función para copiar la salida.
- **Preparación**: indicador separado de proceso Java en ejecución y servidor listo (se detecta la línea `Done (...)`).
- **Sin confirmación de arranque en teléfono real**: la compilación y los tests Linux no reproducen el comportamiento de ARM64 bajo Android 13.
