"""pytest fixtures — events DB 격리, tools import path."""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

import pytest


# tools/agent/ 를 sys.path 에 추가 (test 가 events.py 등 직접 import).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture
def isolated_db(monkeypatch):
    """test 별 isolated SQLite DB. fixture teardown 시 삭제."""
    fd, path = tempfile.mkstemp(suffix=".sqlite")
    os.close(fd)
    monkeypatch.setenv("MOBRUJI_AGENT_DB", path)

    import events  # late import — env 적용 후
    events.init_schema()

    yield path

    try:
        os.unlink(path)
    except OSError:
        pass
