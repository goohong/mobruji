"""Integration tests for directive_register_watch_loop (issue #1071).

watchdog loop 동작:
- mismatch 감지 시 channel.send 호출
- debounce 윈도우 안 재 push 안 함
- 파일 부재 graceful
- on_message hook 통합 (분류 + jsonl append)
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import bot  # noqa: E402
from directive_detect import (  # noqa: E402
    DIRECTIVE_CLASSES,
    append_detect_entry,
    make_detect_entry,
)


def _make_detect_jsonl(path: Path, now: datetime, count: int, base_min_ago: int = 30) -> None:
    """count 건 directive entry 작성 (모두 윈도우 안)."""
    for i in range(count):
        ts = (now - timedelta(minutes=base_min_ago + i)).isoformat().replace("+00:00", "Z")
        append_detect_entry(
            path,
            make_detect_entry(
                message_id=str(1_000_000_000_000_000_000 + i),
                ts_iso=ts,
                text=f"기능 {i} 추가해줘",
            ),
        )


def _make_empty_board(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("", encoding="utf-8")


class TestDirectiveRegisterWatchLoop:
    @pytest.mark.asyncio
    async def test_mismatch_pushes_to_channel(self, tmp_path: Path) -> None:
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        now = datetime.now(timezone.utc)
        _make_detect_jsonl(detect, now, count=10)
        _make_empty_board(board)

        # mock channel
        channel = MagicMock()
        channel.send = AsyncMock()
        client = MagicMock()
        client.get_channel = MagicMock(return_value=channel)

        # one iter — patch sleep 으로 두 번째 iter 진입 막음
        loop_iter_count = {"n": 0}

        async def fake_sleep(_seconds: float) -> None:
            loop_iter_count["n"] += 1
            if loop_iter_count["n"] >= 2:
                raise asyncio.CancelledError()

        with patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await bot.directive_register_watch_loop(
                    client,
                    notify_channel_id=999,
                    detect_path=detect,
                    board_path=board,
                    poll_interval=1,
                    window_minutes=60,
                    grace_count=5,
                    initial_delay=0,
                )

        # mismatch detected → push 1회 발생
        channel.send.assert_called_once()
        message = channel.send.call_args[0][0]
        assert "directive forum 등록 누락" in message
        assert "10" in message  # detect count
        assert "0" in message  # board count

    @pytest.mark.asyncio
    async def test_no_mismatch_no_push(self, tmp_path: Path) -> None:
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        now = datetime.now(timezone.utc)
        _make_detect_jsonl(detect, now, count=2)  # 2 < 0+5 grace
        _make_empty_board(board)

        channel = MagicMock()
        channel.send = AsyncMock()
        client = MagicMock()
        client.get_channel = MagicMock(return_value=channel)

        iter_count = {"n": 0}

        async def fake_sleep(_s: float) -> None:
            iter_count["n"] += 1
            if iter_count["n"] >= 2:
                raise asyncio.CancelledError()

        with patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await bot.directive_register_watch_loop(
                    client,
                    notify_channel_id=999,
                    detect_path=detect,
                    board_path=board,
                    poll_interval=1,
                    window_minutes=60,
                    grace_count=5,
                    initial_delay=0,
                )

        channel.send.assert_not_called()

    @pytest.mark.asyncio
    async def test_debounce_suppresses_duplicate_push(self, tmp_path: Path) -> None:
        """첫 mismatch push 후 debounce 윈도우 안 두 번째 iter mismatch — push 안 함."""
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        now = datetime.now(timezone.utc)
        _make_detect_jsonl(detect, now, count=10)
        _make_empty_board(board)

        channel = MagicMock()
        channel.send = AsyncMock()
        client = MagicMock()
        client.get_channel = MagicMock(return_value=channel)

        iter_count = {"n": 0}

        async def fake_sleep(_s: float) -> None:
            iter_count["n"] += 1
            if iter_count["n"] >= 3:  # 3번 iter 후 종료 (2회 mismatch check)
                raise asyncio.CancelledError()

        with patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await bot.directive_register_watch_loop(
                    client,
                    notify_channel_id=999,
                    detect_path=detect,
                    board_path=board,
                    poll_interval=1,
                    window_minutes=60,
                    grace_count=5,
                    debounce_seconds=3600,
                    initial_delay=0,
                )

        # 첫 iter 만 push (debounce 가 두 번째 막음)
        assert channel.send.call_count == 1

    @pytest.mark.asyncio
    async def test_disabled_returns_immediately(self) -> None:
        """poll_interval <= 0 또는 notify_channel_id <= 0 시 즉시 return."""
        client = MagicMock()
        await bot.directive_register_watch_loop(
            client,
            notify_channel_id=0,
            poll_interval=600,
            initial_delay=0,
        )
        # 즉시 return — 예외 없음

        await bot.directive_register_watch_loop(
            client,
            notify_channel_id=999,
            poll_interval=0,
            initial_delay=0,
        )

    @pytest.mark.asyncio
    async def test_missing_files_graceful(self, tmp_path: Path) -> None:
        """파일 부재 → detect=0 / board=0 → mismatch=False → push 없음."""
        channel = MagicMock()
        channel.send = AsyncMock()
        client = MagicMock()
        client.get_channel = MagicMock(return_value=channel)

        iter_count = {"n": 0}

        async def fake_sleep(_s: float) -> None:
            iter_count["n"] += 1
            if iter_count["n"] >= 2:
                raise asyncio.CancelledError()

        with patch.object(bot.asyncio, "sleep", side_effect=fake_sleep):
            with pytest.raises(asyncio.CancelledError):
                await bot.directive_register_watch_loop(
                    client,
                    notify_channel_id=999,
                    detect_path=tmp_path / "missing-detect.jsonl",
                    board_path=tmp_path / "missing-board.jsonl",
                    poll_interval=1,
                    initial_delay=0,
                )

        channel.send.assert_not_called()
