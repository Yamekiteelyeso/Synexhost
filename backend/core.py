"""Server management logic. Uses only Python's standard library."""
from __future__ import annotations

from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock, Thread
from typing import Any
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request

BASE = Path(os.environ.get('SYNEX_HOME', str(Path.home() / '.synex-host'))).expanduser().resolve()
USER_AGENT = 'SynexHost/0.2 (https://github.com/Yamekiteelyeso/Synexhost)'
PAPER_PROJECT_API = 'https://fill.papermc.io/v3/projects/paper'
API = PAPER_PROJECT_API + '/versions/{version}/builds'
NAME_RE = re.compile(r'^[A-Za-z0-9 ._()\-áéíóúÁÉÍÓÚñÑ]{1,48}$')
VER_RE = re.compile(r'^\d{1,2}\.\d{1,2}(?:\.\d{1,2})?$')
MAX_RAM_MB = 16384
RAM_STEP_MB = 256
CACHE_SECONDS = 1800
_version_cache: tuple[float, list[str]] = (0, [])
_version_lock = Lock()

def paper_versions() -> list[str]:
    """Versions advertised by PaperMC; exclude snapshots/pre-releases.

    A listed version is not a promise that a STABLE build exists.
    """
    global _version_cache
    with _version_lock:
        if _version_cache[1] and time.monotonic() - _version_cache[0] < CACHE_SECONDS:
            return list(_version_cache[1])
        request = urllib.request.Request(PAPER_PROJECT_API, headers={'User-Agent': USER_AGENT})
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.load(response)
        groups = data.get('versions')
        if not isinstance(groups, dict):
            raise RuntimeError('Catálogo inválido de PaperMC')
        seen: set[str] = set()
        ordered: list[str] = []
        for entries in groups.values():
            if not isinstance(entries, list):
                continue
            for version in entries:
                if isinstance(version, str) and VER_RE.fullmatch(version) and version not in seen:
                    ordered.append(version)
                    seen.add(version)
        if not ordered:
            raise RuntimeError('No se encontraron versiones de Paper disponibles')
        _version_cache = (time.monotonic(), ordered)
        return list(ordered)

FILE_LIMIT = 1_048_576
UPLOAD_LIMIT = 64 * 1024 * 1024


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def valid_id(s: str) -> bool:
    return bool(re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', s)) and len(s) <= 64


def error(message: str) -> ValueError:
    return ValueError(message)


def safe_member(root: Path, relative: str) -> Path:
    if '\x00' in relative or '\\' in relative or relative.startswith('/'):
        raise error('Ruta inválida')
    candidate = (root / relative).resolve()
    if not candidate.is_relative_to(root.resolve()):
        raise error('No se permite acceder fuera del servidor')
    return candidate


class Server:
    def __init__(self, base: Path, spec: dict[str, Any]):
        self.base = base
        self.spec = spec
        self.id: str = spec['id']
        self.folder = base / self.id
        self.process: subprocess.Popen | None = None
        self.messages: deque[dict[str, Any]] = deque(maxlen=2500)
        self.seq = 0
        self.lock = Lock()
        self.installing = False
        self.install_error: str | None = None
        self.folder.mkdir(parents=True, exist_ok=True)

    def push(self, text: str):
        with self.lock:
            self.seq += 1
            self.messages.append({'seq': self.seq, 'text': text.rstrip('\r\n')[:4000]})

    def state(self) -> dict[str, Any]:
        with self.lock:
            running = self.process is not None and self.process.poll() is None
            installing = self.installing
            failure = self.install_error
        return {**self.spec, 'running': running, 'installing': installing,
                'installed': (self.folder / 'paper.jar').is_file(),
                'eulaAccepted': self.state_eula(),
                'installError': failure}

    def list_files(self, rel: str) -> list[dict[str, Any]]:
        directory = safe_member(self.folder, rel)
        if not directory.is_dir():
            raise FileNotFoundError('Carpeta inexistente')
        result = []
        for p in directory.iterdir():
            # Hide links to prevent surprising file-system navigation; .resolve is also checked elsewhere.
            if p.is_symlink():
                continue
            result.append({'name': p.name, 'path': p.relative_to(self.folder).as_posix(),
                           'directory': p.is_dir(), 'size': p.stat().st_size if p.is_file() else 0})
        return sorted(result, key=lambda e: (not e['directory'], e['name'].lower()))[:500]

    def read_text(self, rel: str) -> str:
        path = safe_member(self.folder, rel)
        if not path.is_file() or path.stat().st_size > FILE_LIMIT:
            raise error('Archivo inexistente o demasiado grande para el editor (máx. 1 MB)')
        try:
            return path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            raise error('El archivo no es texto UTF-8') from None

    def write_text(self, rel: str, content: str) -> None:
        path = safe_member(self.folder, rel)
        if len(content.encode('utf-8')) > FILE_LIMIT:
            raise error('El texto supera 1 MB')
        if not path.is_file() or path.is_symlink():
            raise error('Solo es posible editar archivos de texto existentes')
        # Avoid corrupting active server data; worlds / binary files are never editable.
        if path.suffix.lower() not in {'.txt', '.properties', '.yml', '.yaml', '.json', '.toml', '.cfg', '.conf', '.log', '.sh', '.md'}:
            raise error('Extensión no permitida en el editor')
        temp = path.with_name(path.name + '.synex-temp')
        temp.write_text(content, encoding='utf-8')
        temp.replace(path)

    def upload(self, rel: str, payload: bytes) -> None:
        path = safe_member(self.folder, rel)
        if not path.parent.is_dir() or path.exists() or path.name in ('', '.', '..'):
            raise error('La carpeta no existe o el archivo ya existe')
        if len(payload) > UPLOAD_LIMIT:
            raise error('Máximo 64 MB por archivo')
        if path.suffix.lower() not in {'.jar', '.zip', '.yml', '.yaml', '.properties', '.json', '.txt', '.toml', '.cfg'}:
            raise error('Formato no permitido')
        path.write_bytes(payload)

    def logs(self, since: int) -> dict[str, Any]:
        with self.lock:
            messages = [e for e in self.messages if e['seq'] > since]
            return {'lines': messages, 'next': self.seq}

    def _download_worker(self):
        target = self.folder / 'paper.jar'
        partial = self.folder / 'paper.jar.partial'
        try:
            version = self.spec['version']
            self.push(f'[Synex] Buscando Paper {version}...')
            req = urllib.request.Request(API.format(version=version), headers={'User-Agent': USER_AGENT})
            with urllib.request.urlopen(req, timeout=25) as response:
                builds = json.load(response)
            if not isinstance(builds, list):
                raise RuntimeError('PaperMC no devolvió una lista de builds')
            stables = [b for b in builds if b.get('channel') == 'STABLE' and b.get('downloads', {}).get('server:default', {}).get('url')]
            if not stables:
                raise RuntimeError('No hay una build estable de Paper para esta versión')
            build = max(stables, key=lambda b: int(b.get('id', 0)))
            info = build['downloads']['server:default']
            url = info['url']
            if not url.startswith('https://fill-data.papermc.io/'):
                raise RuntimeError('El enlace no pertenece a la CDN oficial de PaperMC')
            expected = (info.get('checksums') or {}).get('sha256')
            self.push(f"[Synex] Descargando Paper build #{build['id']}...")
            sha = hashlib.sha256()
            downloaded = 0
            req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
            with urllib.request.urlopen(req, timeout=90) as response, partial.open('wb') as f:
                while block := response.read(256 * 1024):
                    downloaded += len(block)
                    if downloaded > 150 * 1024 * 1024:
                        raise RuntimeError('Descarga demasiado grande')
                    f.write(block)
                    sha.update(block)
            if expected and sha.hexdigest().lower() != expected.lower():
                raise RuntimeError('La verificación SHA-256 falló')
            if downloaded < 100_000:
                raise RuntimeError('JAR descargado sospechosamente pequeño')
            partial.replace(target)
            self.push('[Synex] Paper instalado. Acepta la EULA para iniciarlo.')
        except Exception as exc:
            self.install_error = str(exc)
            self.push(f'[ERROR] Instalación: {exc}')
            partial.unlink(missing_ok=True)
        finally:
            with self.lock:
                self.installing = False

    def install(self):
        with self.lock:
            if self.installing:
                raise error('Ya hay una instalación en curso')
            if self.process and self.process.poll() is None:
                raise error('Detén el servidor antes de instalar')
            self.installing = True
            self.install_error = None
        Thread(target=self._download_worker, daemon=True).start()

    def accept_eula(self):
        (self.folder / 'eula.txt').write_text('# Aceptada explícitamente desde Synex Host\neula=true\n', encoding='utf-8')
        self.push('[Synex] EULA aceptada.')

    def start(self):
        with self.lock:
            if self.process and self.process.poll() is None:
                raise error('El servidor ya está encendido')
            if self.installing or not (self.folder / 'paper.jar').is_file():
                raise error('Debes instalar Paper primero')
            if not self.state_eula():
                raise error('Debes aceptar la EULA primero')
            java = shutil.which('java')
            if not java:
                raise error('Java no está instalado en Termux')
            ram = int(self.spec['ramMb'])
            args = [java, '-Xms512M', f'-Xmx{ram}M', '-jar', 'paper.jar', '--nogui']
            self.process = subprocess.Popen(args, cwd=self.folder, stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                            text=True, bufsize=1, start_new_session=True)
            proc = self.process
        self.push('[Synex] Iniciando proceso Java...')
        Thread(target=self._read_process, args=(proc,), daemon=True).start()

    def state_eula(self) -> bool:
        path = self.folder / 'eula.txt'
        if not path.exists():
            return False
        return any(line.strip() == 'eula=true' for line in path.read_text(errors='replace').splitlines())

    def _read_process(self, proc: subprocess.Popen):
        assert proc.stdout is not None
        try:
            for line in proc.stdout:
                self.push(line)
        finally:
            code = proc.wait()
            if proc.stdout is not None:
                proc.stdout.close()
            if proc.stdin is not None:
                proc.stdin.close()
            self.push(f'[Synex] Servidor detenido (código {code}).')

    def command(self, value: str):
        cmd = value.strip()
        if not cmd or len(cmd) > 512 or '\n' in cmd or '\r' in cmd:
            raise error('Comando inválido')
        with self.lock:
            proc = self.process
            if proc is None or proc.poll() is not None or proc.stdin is None:
                raise error('El servidor está apagado')
            try:
                proc.stdin.write(cmd.lstrip('/') + '\n')
                proc.stdin.flush()
            except (BrokenPipeError, OSError):
                raise error('No se pudo escribir en la consola') from None
        self.push('> ' + cmd)

    def stop(self):
        self.command('stop')  # Paper saves its world before exiting; don't hard-kill.


class Manager:
    def __init__(self, home: Path = BASE):
        self.home = Path(home).resolve()
        self.home.mkdir(parents=True, exist_ok=True)
        self.dir = self.home / 'servers'
        self.dir.mkdir(exist_ok=True)
        self.lock = Lock()
        self.servers: dict[str, Server] = {}
        for file in self.dir.glob('*/synex.json'):
            try:
                spec = json.loads(file.read_text(encoding='utf-8'))
                if valid_id(spec['id']) and file.parent.name == spec['id']:
                    self.servers[spec['id']] = Server(self.dir, spec)
            except (ValueError, KeyError, OSError):
                continue
        if not self.servers:
            self.create('Mi BoxPvP', '1.20.1', 2048)
        self.token_path = self.home / 'token'
        if not self.token_path.exists():
            self.token_path.write_text(secrets.token_urlsafe(32), encoding='ascii')
            self.token_path.chmod(0o600)
        self.token = self.token_path.read_text(encoding='ascii').strip()

    def create(self, name: str, version: str, ram: int) -> Server:
        name = str(name).strip()
        if not NAME_RE.fullmatch(name):
            raise error('Nombre inválido (máximo 48 caracteres)')
        if not VER_RE.fullmatch(version):
            raise error('Versión de Minecraft inválida')
        if not 512 <= ram <= MAX_RAM_MB or ram % RAM_STEP_MB != 0:
            raise error('RAM válida: 512 a 16384 MB (16 GB), múltiplos de 256')
        slug = re.sub(r'[^a-z0-9]+', '-', name.casefold()).strip('-') or 'server'
        slug = slug[:35].strip('-')
        with self.lock:
            sid = slug
            num = 2
            while sid in self.servers:
                sid = f'{slug}-{num}'
                num += 1
            used_ports = {int(item.spec.get('port', 25565)) for item in self.servers.values()}
            port = next((p for p in range(25565, 25666) if p not in used_ports), None)
            if port is None:
                raise error('No quedan puertos automáticos disponibles')
            spec = {'id': sid, 'name': name, 'software': 'Paper', 'version': version,
                    'ramMb': ram, 'port': port, 'createdAt': utc_now()}
            server = Server(self.dir, spec)
            self.servers[sid] = server
            (server.folder / 'synex.json').write_text(json.dumps(spec, indent=2, ensure_ascii=False), encoding='utf-8')
            (server.folder / 'server.properties').write_text(
                'motd=Synex Host - ' + name + '\n'
                f'server-port={port}\n'
                'online-mode=true\n'
                'max-players=10\n'
                'view-distance=6\n'
                'simulation-distance=4\n', encoding='utf-8')
            (server.folder / 'plugins').mkdir(exist_ok=True)
        server.push('[Synex] Servidor creado. Instala Paper para continuar.')
        return server

    def get(self, sid: str) -> Server:
        if not valid_id(sid) or sid not in self.servers:
            raise FileNotFoundError('Servidor inexistente')
        return self.servers[sid]
