#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
printf '\n== Synex Host / Instalación de dependencias ==\n'
pkg update -y
pkg install -y python openjdk-21
java -version
python --version
printf '\nDependencias listas. Ejecuta ./start_backend.sh desde la carpeta SynexHost.\n'
