"""Regression checks for the load tool; exercises a real local HTTP server."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCRIPT = Path(__file__).with_name("traffic_load.py")
TOKEN = "fixture-token-must-never-appear-in-evidence"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    statuses = {}
    paths = []
    guard = threading.Lock()

    def do_GET(self):
        with self.guard:
            self.paths.append(self.path)
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            status, payload = 401, {"code": 401}
        else:
            status, payload = self.statuses.get(self.path, (200, {"code": 200, "data": []}))
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


class LoadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        Handler.statuses = {}
        Handler.paths = []

    def run_load(self, output_dir, *extra):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--base-url", f"http://127.0.0.1:{self.server.server_port}",
             "--requests", "12", "--concurrency", "3", "--timeout", "1",
             "--output-dir", output_dir, *extra],
            env={**os.environ, "TOKEN": TOKEN}, text=True, capture_output=True, timeout=20,
        )

    def test_success_mix_produces_evidence_without_token(self):
        with tempfile.TemporaryDirectory() as output_dir:
            result = self.run_load(output_dir)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            files = list(Path(output_dir).glob("*.json"))
            self.assertEqual(len(files), 1)
            evidence = json.loads(files[0].read_text(encoding="utf-8"))
            self.assertEqual(evidence["summary"]["success"], 12)
            self.assertEqual(evidence["summary"]["total"], 12)
            self.assertEqual(len(set(Handler.paths)), 3)
            self.assertIn("p99_ms", evidence["summary"]["latency_all"])
            self.assertIn("python", evidence["load_generator"])
            self.assertEqual(evidence["parameters"]["concurrency"], 3)
            markdown = list(Path(output_dir).glob("*.md"))
            self.assertEqual(len(markdown), 1)
            exported = files[0].read_text(encoding="utf-8") + markdown[0].read_text(encoding="utf-8")
            self.assertNotIn(TOKEN, exported + result.stdout + result.stderr)

    def test_http_and_business_failures_are_not_successful_throughput(self):
        Handler.statuses = {
            "/api/traffic/realtime": (429, {"code": 429}),
            "/api/traffic/heatmap": (503, {"code": 503}),
            "/api/traffic/summary": (200, {"code": 500, "message": TOKEN}),
        }
        with tempfile.TemporaryDirectory() as output_dir:
            result = self.run_load(output_dir)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            path = next(Path(output_dir).glob("*.json"))
            evidence = json.loads(path.read_text(encoding="utf-8"))
            summary = evidence["summary"]
            self.assertEqual(summary["success"], 0)
            self.assertEqual(summary["success_rps"], 0)
            self.assertEqual(summary["rate_limited"], 4)
            self.assertEqual(summary["http_5xx"], 4)
            self.assertEqual(summary["business_error"], 4)
            self.assertEqual(summary["error_rate"], 1)
            self.assertNotIn(TOKEN, path.read_text(encoding="utf-8") + result.stdout + result.stderr)

    def test_remote_environment_requires_explicit_base_url_argument(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--requests", "1"],
            env={**os.environ, "BASE_URL": "http://example.invalid:8080", "TOKEN": TOKEN},
            text=True, capture_output=True, timeout=10,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("--base-url", result.stderr)
        self.assertNotIn(TOKEN, result.stderr)


if __name__ == "__main__":
    unittest.main()
