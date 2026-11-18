import http.client
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from backend.app import make_server
from backend.core import Manager, safe_member, paper_versions


class CoreTests(unittest.TestCase):
    def test_default_server_and_persistence(self):
        with tempfile.TemporaryDirectory() as d:
            m = Manager(Path(d))
            self.assertEqual(m.get('mi-boxpvp').spec['version'], '1.20.1')
            self.assertEqual(m.get('mi-boxpvp').spec['ramMb'], 2048)
            self.assertFalse(m.get('mi-boxpvp').state()['eulaAccepted'])
            self.assertTrue((Path(d) / 'servers/mi-boxpvp/server.properties').exists())
            m2 = Manager(Path(d))
            self.assertEqual(len(m2.servers), 1)
            self.assertEqual(m.token, m2.token)

    def test_file_sandbox(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for path in ('../secret', '/etc/passwd', 'folder/../../secret', 'a\\b'):
                with self.assertRaises(ValueError):
                    safe_member(root, path)
            self.assertEqual(safe_member(root, 'plugins/config.yml'), root / 'plugins/config.yml')
            server = Manager(root).get('mi-boxpvp')
            with self.assertRaises(ValueError):
                server.upload('plugins/payload.sh', b'hi')
            with self.assertRaises(ValueError):
                server.write_text('paper.jar', 'oops')

    def test_eula_explicit_and_commands(self):
        with tempfile.TemporaryDirectory() as d:
            server = Manager(Path(d)).get('mi-boxpvp')
            self.assertFalse(server.state_eula())
            server.accept_eula()
            self.assertTrue(server.state_eula())
            with self.assertRaises(ValueError):
                server.command('say hi\nstop')


    def test_paper_download_and_checksum(self):
        import hashlib
        jar = b"P" * 120_000
        build = [{"id": 100, "channel": "STABLE", "downloads": {"server:default": {
            "url": "https://fill-data.papermc.io/v1/objects/example/paper.jar",
            "checksums": {"sha256": hashlib.sha256(jar).hexdigest()}
        }}}]
        with tempfile.TemporaryDirectory() as d:
            server = Manager(Path(d)).get('mi-boxpvp')
            fake_results = [io.BytesIO(json.dumps(build).encode('utf-8')), io.BytesIO(jar)]
            with patch('backend.core.urllib.request.urlopen', side_effect=fake_results):
                server._download_worker()
            self.assertEqual((server.folder / 'paper.jar').read_bytes(), jar)
            self.assertTrue(server.state()['installed'])
            self.assertIsNone(server.install_error)

    def test_java_process_console_and_safe_stop(self):
        import time
        with tempfile.TemporaryDirectory() as d:
            server = Manager(Path(d)).get('mi-boxpvp')
            (server.folder / 'paper.jar').write_bytes(b'test')
            server.accept_eula()
            fake = Path(d) / 'java'
            fake.write_text('#!/usr/bin/env python3\nimport sys\nprint("Done (1.0s)!",flush=True)\nfor line in sys.stdin:\n print("console:"+line.strip(),flush=True)\n if line.strip()=="stop":break\n')
            fake.chmod(0o755)
            with patch('backend.core.shutil.which', return_value=str(fake)):
                server.start()
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline and not any('Done (1.0s)' in line['text'] for line in server.logs(0)['lines']):
                    time.sleep(0.01)
                server.command('say hola')
                server.stop()
                server.process.wait(timeout=5)
                time.sleep(0.05)
            log = '\n'.join(e['text'] for e in server.logs(0)['lines'])
            self.assertIn('console:say hola', log)
            self.assertIn('console:stop', log)
            self.assertFalse(server.state()['running'])

    def test_more_ram_and_new_version_formats(self):
        with tempfile.TemporaryDirectory() as d:
            manager = Manager(Path(d))
            chosen = manager.create('High RAM', '26.3', 16384)
            self.assertEqual(chosen.spec['ramMb'], 16384)
            self.assertEqual(chosen.spec['version'], '26.3')
            with self.assertRaises(ValueError):
                manager.create('Too much', '1.21.11', 16640)
            with self.assertRaises(ValueError):
                manager.create('Bad version', '1.21.11-rc1', 2048)

    def test_version_catalog_filters_prereleases(self):
        import backend.core as core
        catalog = {'versions': {'26.3': ['26.3', '26.3-rc1'],
                                '1.21': ['1.21.11', '1.21.11-pre1', '1.20.1']}}
        with patch.object(core, '_version_cache', (0, [])):
            with patch('backend.core.urllib.request.urlopen', return_value=io.BytesIO(json.dumps(catalog).encode())):
                self.assertEqual(paper_versions(), ['26.3', '1.21.11', '1.20.1'])

    def test_multiple_server_ports_are_distinct(self):
        with tempfile.TemporaryDirectory() as d:
            m = Manager(Path(d))
            additional = m.create('Otro Server', '1.20.1', 1024)
            self.assertEqual(m.get('mi-boxpvp').spec['port'], 25565)
            self.assertEqual(additional.spec['port'], 25566)


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.m = Manager(Path(self.temp.name))
        self.httpd = make_server(self.m, port=0)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method, path, obj=None, token=None, binary=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.httpd.server_port, timeout=4)
        headers = {}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + token
        body = binary if binary is not None else (json.dumps(obj).encode() if obj is not None else b'')
        headers['Content-Length'] = str(len(body))
        conn.request(method, path, body, headers)
        response = conn.getresponse()
        result = response.status, json.loads(response.read())
        conn.close()
        return result

    def test_authorization(self):
        self.assertEqual(self.request('GET', '/api/servers')[0], 401)
        status, value = self.request('GET', '/api/servers', token=self.m.token)
        self.assertEqual(status, 200)
        self.assertEqual(value['servers'][0]['name'], 'Mi BoxPvP')

    def test_version_api_requires_token(self):
        import backend.core as core
        self.assertEqual(self.request('GET', '/api/versions')[0], 401)
        with patch.object(core, 'paper_versions', return_value=['1.20.1']):
            # app.py binds the function on import, so patch the handler namespace.
            with patch('backend.app.paper_versions', return_value=['1.20.1', '26.3']):
                status, data = self.request('GET', '/api/versions', token=self.m.token)
        self.assertEqual(status, 200)
        self.assertEqual(data['versions'], ['1.20.1', '26.3'])
        self.assertEqual(data['maxRamMb'], 16384)

    def test_create_console_files_and_eula(self):
        tok = self.m.token
        self.assertEqual(self.request('POST', '/api/servers', {'name': 'Pruebas', 'version': '1.20.1', 'ramMb': 1024}, tok)[0], 201)
        self.assertEqual(self.request('POST', '/api/servers/pruebas/eula', {'accepted': False}, tok)[0], 400)
        self.assertEqual(self.request('POST', '/api/servers/pruebas/eula', {'accepted': True}, tok)[0], 200)
        self.assertEqual(self.request('POST', '/api/servers/pruebas/start', {}, tok)[0], 400)
        self.assertEqual(self.request('GET', '/api/servers/pruebas/files?path=..%2F', token=tok)[0], 400)
        self.assertEqual(self.request('GET', '/api/servers/pruebas/files', token=tok)[0], 200)
        self.assertEqual(self.request('PUT', '/api/servers/pruebas/files/content?path=server.properties', {'content': 'max-players=5\n'}, tok)[0], 200)
        self.assertIn('max-players=5', self.m.get('pruebas').read_text('server.properties'))
        self.assertEqual(self.request('POST', '/api/servers/pruebas/files/upload?path=plugins%2Fmyplugin.jar', token=tok, binary=b'jar bytes')[0], 201)


if __name__ == '__main__':
    unittest.main()
