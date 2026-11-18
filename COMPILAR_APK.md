# Compilar Synex Host v0.1 a APK

La APK todavía no se compiló en este entorno. Este proyecto sí incluye el workflow `/.github/workflows/android-debug.yml` para que GitHub Actions la compile usando Android SDK y Gradle.

## Compilación en GitHub Actions (sin computadora)

1. En GitHub crea un repositorio nuevo, por ejemplo `SynexHost`. Puede ser privado o público.
2. **Sube el contenido de esta carpeta a la raíz del repositorio**. Es importante que `.github/workflows/android-debug.yml` y `android-app/` queden al mismo nivel, no dentro de otra carpeta `SynexHost/`.
3. Haz commit en la rama `main`. El workflow `Android debug APK` está configurado para ejecutarse en cada push a `main` y `master`.
4. Abre la pestaña **Actions**, entra en **Android debug APK** y espera a que el job **build** termine correctamente.
5. En la página de esa ejecución, en **Artifacts**, descarga `SynexHost-debug-apk`.
6. Extrae el ZIP del artifact y encontrarás `app-debug.apk`. En el teléfono, instala esa APK de depuración.

Si el workflow no arranca, revisa en **Settings > Actions > General** que las Actions del repositorio estén habilitadas, o lánzalo desde **Actions > Android debug APK > Run workflow**.

## Compilación en PC con Android Studio

1. Abre la carpeta `android-app/`.
2. Deja que Android Studio resuelva Android Gradle Plugin 8.9.2, Gradle 8.11.1, Kotlin 2.1.20 y SDK 35.
3. Compila con **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Resultado esperado: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

## Nota sobre el funcionamiento

La APK será un **panel para Termux**, no incluye Java/Termux dentro del APK. Instala Termux por separado, ejecuta `start_backend.sh` y copia el token mostrado en pantalla. Revisa `README.md` para el resto.
