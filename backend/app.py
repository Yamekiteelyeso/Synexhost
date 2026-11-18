"""Loopback-only REST API for the Synex Host Android app."""
from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
import json
import os
import re
import secrets

from .core import FILE_LIMIT, UPLOAD_LIMIT, Manager, MAX_RAM_MB, paper_versions


class ApiHandler(BaseHTTPRequestHandler):
    manager: Manager
    server_version = 'SynexHost/0.2'

    def log_message(self, format, *args):
        # Avoid printing authenticated URLs/headers or payloads.
        print('[HTTP] ' + format % args, flush=True)

    def send_data(self, code: int, data: dict | list):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        header = self.headers.get('Authorization', '')
        return secrets.compare_digest(header, 'Bearer ' + self.manager.token)

    def guarded(self, handler):
        if not self.authorized():
            self.send_data(401, {'error': 'Token inválido'})
            return
        try:
            handler()
        except (ValueError, TypeError) as exc:
            self.send_data(400, {'error': str(exc)})
        except FileNotFoundError as exc:
            self.send_data(404, {'error': str(exc)})
        except (OSError, RuntimeError) as exc:
            self.send_data(409, {'error': str(exc)})

    def payload(self, limit=FILE_LIMIT) -> bytes:
        size = int(self.headers.get('Content-Length', '-1'))
        if size < 0 or size > limit:
            raise ValueError(f'Tamaño inválido, máximo {limit} bytes')
        body = self.rfile.read(size)
        if len(body) != size:
            raise ValueError('Cuerpo incompleto')
        return body

    def obj(self) -> dict:
        try:
            data = json.loads(self.payload().decode('utf-8'))
        except (UnicodeError, json.JSONDecodeError):
            raise ValueError('JSON inválido') from None
        if not isinstance(data, dict):
            raise ValueError('Se esperaba un objeto JSON')
        return data

    def route(self):
        p = urlsplit(self.path)
        m = re.fullmatch(r'/api/servers/([a-z0-9-]+)(?:/(.*))?', p.path)
        params = parse_qs(p.query, keep_blank_values=True)
        return p.path, m, {key: vals[-1] for key, vals in params.items()}

    def do_GET(self):
        def task():
            path, match, q = self.route()
            if path == '/api/versions':
                self.send_data(200, {'software': 'Paper', 'versions': paper_versions(), 'maxRamMb': MAX_RAM_MB})
            elif path == '/api/servers':
                self.send_data(200, {'servers': [s.state() for s in self.manager.servers.values()]})
            elif match:
                sid, command = match.group(1), match.group(2)
                srv = self.manager.get(sid)
                if command is None:
                    self.send_data(200, srv.state())
                elif command == 'logs':
                    self.send_data(200, srv.logs(max(0, int(q.get('since', 0)))))
                elif command == 'files':
                    folder = q.get('path', '')
                    self.send_data(200, {'path': folder, 'entries': srv.list_files(folder)})
                elif command == 'files/content':
                    rel = q.get('path', '')
                    self.send_data(200, {'path': rel, 'content': srv.read_text(rel)})
                else:
                    raise FileNotFoundError('Ruta API desconocida')
            else:
                raise FileNotFoundError('Ruta API desconocida')
        if self.path == '/health':
            self.send_data(200, {'ok': True, 'service': 'synex-host'})
        else:
            self.guarded(task)

    def do_POST(self):
        def task():
            path, match, q = self.route()
            if path == '/api/servers':
                data = self.obj()
                srv = self.manager.create(data.get('name', ''), data.get('version', ''), int(data.get('ramMb', 2048)))
                self.send_data(201, srv.state())
                return
            if not match:
                raise FileNotFoundError('Ruta API desconocida')
            srv = self.manager.get(match.group(1))
            op = match.group(2)
            if op == 'install':
                srv.install()
                self.send_data(202, {'ok': True})
            elif op == 'eula':
                if self.obj().get('accepted') is not True:
                    raise ValueError('Es necesario aceptar la EULA explícitamente')
                srv.accept_eula()
                self.send_data(200, {'ok': True})
            elif op == 'start':
                srv.start()
                self.send_data(200, {'ok': True})
            elif op == 'stop':
                srv.stop()
                self.send_data(200, {'ok': True})
            elif op == 'command':
                srv.command(str(self.obj().get('command', '')))
                self.send_data(200, {'ok': True})
            elif op == 'files/upload':
                srv.upload(q.get('path', ''), self.payload(UPLOAD_LIMIT))
                self.send_data(201, {'ok': True})
            else:
                raise FileNotFoundError('Ruta API desconocida')
        self.guarded(task)

    def do_PUT(self):
        def task():
            _, match, q = self.route()
            if not match or match.group(2) != 'files/content':
                raise FileNotFoundError('Ruta API desconocida')
            self.manager.get(match.group(1)).write_text(q.get('path', ''), self.obj().get('content', ''))
            self.send_data(200, {'ok': True})
        self.guarded(task)


def make_server(manager: Manager, port: int = 8765) -> ThreadingHTTPServer:
    class Handler(ApiHandler):
        pass
    Handler.manager = manager
    httpd = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    httpd.daemon_threads = True
    return httpd


def main():
    manager = Manager()
    port = int(os.environ.get('SYNEX_PORT', '8765'))
    server = make_server(manager, port)
    print('\nSynex Host backend iniciado', flush=True)
    print('URL: http://127.0.0.1:' + str(server.server_port), flush=True)
    print('TOKEN: ' + manager.token, flush=True)
    print('Guarda el token en la pantalla de conexión de Synex Host.\n', flush=True)
    try:
        server.serve_forever(poll_interval=0.3)
    except KeyboardInterrupt:
        print('\nCerrando controlador (los procesos Java ya iniciados no se detienen automáticamente).')
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
