#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["moto[server]==5.2.3"]
# ///
"""Run the PostgreSQL suite plus S3 tests against an isolated local Moto server."""
import logging
import os
from pathlib import Path
import subprocess

from moto.server import ThreadedMotoServer

logging.getLogger("werkzeug").setLevel(logging.ERROR)
server = ThreadedMotoServer(ip_address="127.0.0.1", port=0, verbose=False)
server.start()
try:
    host, port = server.get_host_and_port()
    env = dict(os.environ, PDS_TEST_S3_ENDPOINT=f"http://{host}:{port}")
    root = Path(__file__).resolve().parents[1]
    raise SystemExit(subprocess.call(["bash", "scripts/test-postgres.sh"], cwd=root, env=env))
finally:
    server.stop()
