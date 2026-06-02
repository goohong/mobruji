"""post-merge injected ledger 단위 테스트 (#1443) — PR 당 1회성 보장."""
from __future__ import annotations
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import bot  # noqa: E402


def test_ledger_roundtrip(tmp_path, monkeypatch):
    led = tmp_path / "led.jsonl"
    monkeypatch.setattr(bot, "REV_POST_MERGE_AUDIT_INJECTED_LEDGER", led)
    assert bot.load_post_merge_injected_ledger() == set()  # 부재 graceful
    bot.append_post_merge_injected_ledger([1437, 1439])
    bot.append_post_merge_injected_ledger([1441])
    assert bot.load_post_merge_injected_ledger() == {1437, 1439, 1441}


def test_narrative_message_not_logform():
    msg = bot.format_rev_post_merge_discord([1441])
    assert "머지됐으므로" in msg and "진행하겠습니다" in msg
    assert "audit trigger" not in msg  # 옛 로그 형태 아님
