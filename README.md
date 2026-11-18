# Synex Host — MVP Android 0.2

**Proyecto fuente para Android + Termux.** Permite administrar un servidor **real** de Minecraft Java Edition (Paper) desde el propio teléfono, sin root. Está diseñado para Android y un runtime Java instalado en Termux. **No incluye una APK compilada** ni el `paper.jar` de Minecraft.

## Configuración creada automáticamente

| Parámetro | Valor |
| --- | --- |
| Nombre | Mi BoxPvP |
| Software | Paper |
| Minecraft | 1.20.1 |
| RAM máxima de Java (`-Xmx`) | 2048 MB |
| RAM inicial de Java (`-Xms`) | 512 MB |
| Puerto inicial | 25565 |
| Jugadores máximos | 10 |
| `online-mode` | `true` |

El motor crea los archivos iniciales en `~/.synex-host/servers/mi-boxpvp/`. Se mantiene allí incluso después de cerrar el panel Android. **Paper no se descarga ni se ejecuta hasta que el usuario pulse los botones correspondientes.** La EULA tampoco se acepta automáticamente.

## Arquitectura

```text
Android / Jetpack Compose (Kotlin)
  ├─ Inicio: crear, instalar, encender, apagar
  ├─ Consola: logs + comandos
  ├─ Archivos: explorar, editar texto, subir JAR/ZIP
  └─ Ajustes: conexión y token
            │
            ▼ HTTP solo 127.0.0.1:8765, Bearer token
Termux / Python 3 (backend/app.py)
  ├─ REST API con autenticación
  ├─ Gestor de servidores/puertos
  ├─ Descarga PaperMC Fill API v3 + SHA-256
  ├─ Gestión de procesos Java y entrada estándar
  └─ Directorios privados ~/.synex-host/servers/
            │
            ▼
Termux OpenJDK 21 → Paper 1.20.1 → mundo/plugins
```

El motor usa únicamente la biblioteca estándar de Python: no hay FastAPI, Flask, base de datos remota o servicios de terceros para administrar el panel. Sí requiere Internet para descargar Paper y las dependencias de Minecraft.

## Instalar Termux y ejecutar el motor (en el teléfono)

1. Instala Termux desde sus fuentes oficiales: [F-Droid](https://f-droid.org/en/packages/com.termux/) o [GitHub](https://github.com/termux/termux-app). **No mezcles instalaciones de firmas distintas.**
2. Extrae el ZIP del proyecto en el teléfono. La carpeta `SynexHost` deberá contener `backend/`, `android-app/`, `install_termux.sh` y `start_backend.sh`.
3. En Termux, ejecuta:

```sh
pkg update
pkg install -y python openjdk-21
termux-setup-storage
pkg install -y unzip
cd ~/storage/downloads
unzip -o SynexHost-v0.2-source.zip -d "$HOME"
cd ~/SynexHost
bash start_backend.sh
```

Si ya extrajiste el ZIP manualmente, entra directamente en su carpeta `SynexHost`. También puedes ejecutar `bash install_termux.sh` en vez de instalar Python y Java a mano.

Termux mostrará lo siguiente (con un token diferente en cada dispositivo):

```text
Synex Host backend iniciado
URL: http://127.0.0.1:8765
TOKEN: <token único del dispositivo>
```

Guarda ese token para la app. El backend **solo escucha en 127.0.0.1**, nunca publica la interfaz de administración en Internet o la red local. El servidor Minecraft (puerto 25565) sí podrá aceptar clientes si Android, la red y las opciones del servidor lo permiten.

### Compilar la aplicación nativa

En un PC con **Android Studio**, Java 17 para Gradle y el Android SDK:

1. Abre `SynexHost/android-app` como proyecto Gradle.
2. Deja que Android Studio sincronice el proyecto y descargue los plugins de Gradle.
3. Selecciona `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Instala el `app-debug.apk` que genere el proyecto. La ruta habitual de salida es `android-app/app/build/outputs/apk/debug/app-debug.apk`.
5. Abre Synex Host, introduce el token que aparece en Termux y pulsa **Conectar**.

El paquete contiene la configuración de Gradle (`settings.gradle.kts`, módulos y `gradle-wrapper.properties`) pero **no incluye el JAR oficial del Gradle Wrapper**, ya que no estaba disponible en este entorno. Si tu Android Studio exige un wrapper completo, ejecuta `gradle wrapper --gradle-version 8.11.1` desde `android-app` con una instalación local de Gradle, o utiliza la opción de Gradle instalado en Android Studio antes de sincronizar.

### Crear y ejecutar el primer servidor

1. En **Inicio**, verás `Mi BoxPvP · Paper 1.20.1 · 2 GB`.
2. Pulsa **Instalar Paper 1.20.1**. El backend consulta la API oficial de PaperMC y descarga la build *STABLE* más reciente para esa versión. Si no se encuentra una build estable, se muestra un error; **no se sustituye silenciosamente por otra versión**.
3. Consulta y acepta la [EULA de Minecraft](https://aka.ms/MinecraftEULA) desde la app.
4. Pulsa **Iniciar servidor** y abre **Consola** para ver la salida de Java.
5. Usa **Archivos** para editar `server.properties` o añadir plugins `.jar` dentro de `plugins/`. Para aplicar cambios que así lo requieran, detén y vuelve a iniciar el servidor.
6. Para apagarlo pulsa **Detener de forma segura**; envía `stop` a Paper para guardar el mundo.

El puerto por defecto es **25565**, y los siguientes servidores creados reciben automáticamente 25566, 25567, etc. Puedes ver la IP del teléfono en los ajustes de Wi-Fi de Android. En la misma red local, el cliente Minecraft Java puede intentar conectarse a `IP_DEL_TELEFONO:25565` (dependiendo de restricciones de la red y del sistema). **No se incluye un túnel público** para jugadores fuera de la red.

## Limitaciones reales de la versión 0.2

- No se ha construido ni probado una APK en un teléfono físico desde este entorno. El backend está cubierto por pruebas locales, pero **el arranque real de Paper en Android todavía requiere validación en un dispositivo ARM64 con Termux**.
- Requiere Termux: la app Android funciona como panel; **no ejecuta Java por sí sola**. No necesita root.
- Los procesos pueden suspenderse o cerrarse por políticas de energía de Android. Conviene eximir Termux de optimización de batería, mantener su sesión en primer plano mientras se prueba y, si está disponible, utilizar `termux-wake-lock`.
- Si el proceso Python termina de forma inesperada mientras Java sigue vivo, el backend reiniciado **no puede recuperar la consola de ese proceso antiguo**. Detén el servidor normalmente antes de cerrar Termux.
- Los 2 GB indican el **límite máximo de heap de Java**, no la RAM total consumida. El teléfono necesitará memoria adicional para Android, Termux, Java y el mundo de Minecraft.
- Por ahora solo está implementado el software Paper. La app consulta el catálogo oficial de versiones de PaperMC con un selector desplegable (y permite entrada manual si no hay red). Las versiones requieren builds estables disponibles y un Java compatible. La RAM máxima configurable es 16 GB (antes 6 GB), con pasos de 256 MB y advertencias según la memoria del teléfono; Purpur, Fabric, Forge, Vanilla, backups programados, métricas TPS y panel remoto se reservan para iteraciones futuras.
- Los plugins `.jar` son código ejecutable. Utiliza solo fuentes de confianza y verifica que sean compatibles con Paper 1.20.1.
- La administración está protegida mediante token y restringida al loopback. Ese token se guarda en las preferencias privadas del APK, **no está cifrado adicionalmente**.

## Novedades de v0.2

- Selector de muchas versiones de Minecraft, tomadas en tiempo real del catálogo oficial de PaperMC (`GET /api/versions`); excluye las versiones `pre` y `rc`.
- Se conserva 1.20.1 por defecto y se permite introducir una versión manualmente si no hay conexión.
- RAM configurable de 512 MB hasta 16 GB, con presets rápidos de 1, 2, 4, 6, 8, 12 y 16 GB.
- Indicador de RAM física del teléfono y alerta si se configura más RAM de la aconsejable.
- El servidor nunca se sustituye por otra versión sin autorización; la instalación rechaza versiones sin build `STABLE`.

## GitHub Actions

Se incluye `.github/workflows/android-debug.yml`. Si subes este proyecto a un repositorio, cada push a `main` o `master` (o ejecución manual) intentará compilar una APK de depuración y publicarla como artifact `SynexHost-debug-apk`. **No se ha ejecutado ni verificado ese workflow aquí.**

## Pruebas automatizadas del backend

```sh
cd SynexHost
python -m unittest discover -s backend/tests -v
```

Las pruebas cubren servidor por defecto, persistencia, rechazo de rutas inseguras, autenticación HTTP, aceptación EULA, creación de servidores, puertos distintos, descarga simulada con verificación SHA-256, envío de comandos y apagado ordenado con un proceso Java simulado.

## Archivos principales

```text
SynexHost/
├── README.md
├── .github/workflows/android-debug.yml
├── install_termux.sh
├── start_backend.sh
├── backend/
│   ├── __init__.py
│   ├── app.py                  # REST API local, HTTPServer, Bearer auth
│   ├── core.py                 # Servidores, PaperMC, procesos, archivos
│   └── tests/test_api.py      # Tests sin Android ni descarga real
└── android-app/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle/wrapper/gradle-wrapper.properties
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            └── java/com/synex/host/
                ├── MainActivity.kt   # Pantallas Compose
                └── ApiClient.kt      # Cliente REST local
```

**Fuentes técnicas:** [PaperMC Downloads Service](https://docs.papermc.io/misc/downloads-service/), [PaperMC Getting Started](https://docs.papermc.io/paper/getting-started/), [Termux oficial](https://github.com/termux/termux-app).
