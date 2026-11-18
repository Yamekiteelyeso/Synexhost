# Synex Host v0.3 — Android autónomo (prototipo)

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
