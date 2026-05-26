"""Unit tests for directive_detect (issue #1071)."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from directive_detect import (
    CLASS_CONVERSATION,
    CLASS_DIRECTIVE,
    CLASS_DIRECTIVE_AMBIGUOUS,
    CLASS_DIRECTIVE_MIXED,
    CLASS_QUERY,
    DIRECTIVE_CLASSES,
    append_detect_entry,
    classify,
    detect_mismatch,
    format_mismatch_push,
    make_detect_entry,
    summarize,
)


class TestClassify:
    """directive / query / conversation 분류 — 실 queue 메시지 기반 가드."""

    @pytest.mark.parametrize(
        "text",
        [
            "discord-reply.sh 새 mode 추가해줘",
            "이 부분 수정해줘",
            "사용자 페르소나 정의 진행해",
            "forum 채널 신설해",
            "메모리 박제",
            "manual status push 폐기",
            "라벨 자동 부착 강화",
            "단순화 진행",
        ],
    )
    def test_directive_명확(self, text: str) -> None:
        assert classify(text) == CLASS_DIRECTIVE

    @pytest.mark.parametrize(
        "text",
        [
            "지금 뭐하고 있니",
            "왜 leak 났어?",
            "어디까지 진행됐어?",
            "맞아?",
        ],
    )
    def test_query_명확(self, text: str) -> None:
        assert classify(text) == CLASS_QUERY

    @pytest.mark.parametrize("text", ["네", "응", "ㅇㅋ", "good"])
    def test_conversation_짧은(self, text: str) -> None:
        assert classify(text) == CLASS_CONVERSATION

    def test_directive_mixed_혼합(self) -> None:
        # 명령 + 의문 동시 — directive 우선 (frustration 가드)
        assert classify("왜 안 됐어? 수정해줘") in {
            CLASS_DIRECTIVE_MIXED,
            CLASS_DIRECTIVE,
        }

    def test_directive_ambiguous_boundary(self) -> None:
        """경계 메시지 — directive 분류 (false-positive safe)."""
        result = classify("사이클 누수 재발 - 근본 방법 제안 요구")
        # 키워드 "요구" 가 directive 패턴에 매칭
        assert result in DIRECTIVE_CLASSES

    def test_empty_or_none(self) -> None:
        assert classify("") == CLASS_CONVERSATION
        assert classify(None) == CLASS_CONVERSATION  # type: ignore[arg-type]
        assert classify("   ") == CLASS_CONVERSATION


class TestClassifyFalsePositiveGuards:
    """#1124: false-positive 약 90% 차단 — meta / 짧은 ack / 자연어 의문어 보강."""

    @pytest.mark.parametrize(
        "text",
        [
            # bot 본인 auto-ack 가 inbox leak
            "앞으로 🤖 helper bot 수신 — helper 가 nmae 상태 확인 중. 곧 답변드립니다",
            "🤖 helper bot 수신 — helper 가 nmae 상태 확인 중",
            "helper bot auto-ack 활성",
            # 시스템 알림 (helper-empty-message-classify 등)
            "메시지가 전달되지 않은 것 같습니다. 다시 보내 주십시오.",
            "내용이 없는 메시지를 받았습니다",
            # watchdog / digest leak
            "⚠️ watchdog alert — cycle-status idle",
            "🚀 sub-agent launch: be — 진행",
            "✅ 완료: P1 #1083 머지",
        ],
    )
    def test_meta_message_classified_conversation(self, text: str) -> None:
        """bot 본인 ack / 시스템 알림 → conversation (directive 등록 차단)."""
        assert classify(text) == CLASS_CONVERSATION

    @pytest.mark.parametrize(
        "text",
        [
            # 명시적 동의 표지어 + 실행 동사 — "응 해줘" 등
            "응 해줘",
            "응 해주세요",
            "오케이 진행",
            "ㅇㅋ 진행",
            "그래 해줘",
            "좋아 진행",
            # 순수 동의 표지어 (실행 동사 없이)
            "컨펌",
            "오케이",
            "ㅇㅋ",
        ],
    )
    def test_short_ack_classified_conversation(self, text: str) -> None:
        """user→helper 진행 승인 (신규 directive 아님) → conversation.

        주의: "진행" / "진행해" 같은 bare verb 는 실제 신규 directive 도 가능하므로
        ack 패턴에서 제외 (모호 시 directive 유지).
        """
        assert classify(text) == CLASS_CONVERSATION

    def test_bare_verb_still_directive(self) -> None:
        """bare verb '진행해' / '진행' — 신규 directive 가능성 보존 (#1124 backward-compat)."""
        # 기존 테스트 + jsonl 사례에서 '진행해' 는 directive 분류 보존
        assert classify("진행해") == CLASS_DIRECTIVE

    @pytest.mark.parametrize(
        "text",
        [
            "nmae는 뭐하니",
            "지금 nmae는 뭐해",
            "사이클은 어디까지",
            "다음 배포는 언제",
            "비용은 얼마",
            "내말 들리니",
            "잘 들려",
            "이거 보이니",
            "이해했어?",
        ],
    )
    def test_natural_query_without_question_mark(self, text: str) -> None:
        """의문부호 없는 자연어 의문문 — query 분류 (#1124)."""
        assert classify(text) == CLASS_QUERY

    def test_meta_takes_priority_over_directive(self) -> None:
        """meta + directive 키워드 혼합 — meta 가 우선 (bot ack 안 등록)."""
        # "helper bot 수신" + "진행" — bot 본인 ack 이므로 conversation
        assert classify("🤖 helper bot 수신 — 진행 중") == CLASS_CONVERSATION

    def test_short_ack_within_8_char_boundary(self) -> None:
        """8자 boundary — '응 해줘' (4자 stripped) conversation."""
        assert classify("응 해줘") == CLASS_CONVERSATION
        # 9자 이상이면 short-ack 가드 미적용 → 기존 directive 패턴 매칭 가능
        assert classify("응 그건 해줘") in {CLASS_DIRECTIVE, CLASS_DIRECTIVE_AMBIGUOUS}

    def test_real_directive_still_works(self) -> None:
        """false-positive 차단 후에도 실제 directive 는 정상 분류."""
        assert classify("이 부분 수정해줘") == CLASS_DIRECTIVE
        assert classify("forum 채널 신설해") == CLASS_DIRECTIVE


class TestSummarize:
    def test_truncation(self) -> None:
        text = "a" * 80
        assert len(summarize(text, max_len=60)) == 60
        assert summarize(text, max_len=60).endswith("…")

    def test_whitespace_collapse(self) -> None:
        assert summarize("hello   world\n\n") == "hello world"

    def test_no_internal_label_leak(self) -> None:
        """[A1] 같은 internal label 이 사용자 paraphrase 에서 그대로 보존됨 (사용자가
        '내부용 라벨 제거' 후 요구한 paraphrase 룰 — summarize 가 라벨을 추가하지 않음).
        """
        # summarize 는 변형 안 함 — internal label 처리는 caller (helper) 책임.
        assert "[A1]" not in summarize("backfill 8건")


class TestMakeDetectEntry:
    def test_entry_fields(self) -> None:
        entry = make_detect_entry(
            message_id="123456789012345678",
            ts_iso="2026-05-24T07:10:00Z",
            text="directive 등록 누락 fix 해줘",
            channel_id="111",
        )
        assert entry["class"] == CLASS_DIRECTIVE
        assert entry["message_id"] == "123456789012345678"
        assert entry["channel_id"] == "111"
        assert "summary" in entry
        assert entry["ts"] == "2026-05-24T07:10:00Z"


class TestAppendDetectEntry:
    def test_append_creates_file(self, tmp_path: Path) -> None:
        path = tmp_path / "directive-detect.jsonl"
        entry = make_detect_entry(
            message_id="1", ts_iso="2026-05-24T07:00:00Z", text="진행해"
        )
        append_detect_entry(path, entry)
        assert path.exists()
        lines = path.read_text(encoding="utf-8").splitlines()
        assert len(lines) == 1
        parsed = json.loads(lines[0])
        assert parsed["class"] == CLASS_DIRECTIVE

    def test_append_multiple(self, tmp_path: Path) -> None:
        path = tmp_path / "directive-detect.jsonl"
        for i, txt in enumerate(["수정해줘", "왜 안돼?", "그래"]):
            append_detect_entry(
                path,
                make_detect_entry(
                    message_id=str(i),
                    ts_iso=f"2026-05-24T07:{i:02d}:00Z",
                    text=txt,
                ),
            )
        lines = path.read_text(encoding="utf-8").splitlines()
        assert len(lines) == 3
        classes = [json.loads(l)["class"] for l in lines]
        assert CLASS_DIRECTIVE in classes
        assert CLASS_QUERY in classes


class TestDetectMismatch:
    def _make_detect_jsonl(
        self, path: Path, entries: list[tuple[str, str]]
    ) -> None:
        """entries: [(ts_iso, text), ...]"""
        path.write_text(
            "\n".join(
                json.dumps(
                    make_detect_entry(
                        message_id=str(i),
                        ts_iso=ts,
                        text=text,
                    ),
                    ensure_ascii=False,
                )
                for i, (ts, text) in enumerate(entries)
            )
            + "\n",
            encoding="utf-8",
        )

    def _make_board_jsonl(self, path: Path, kst_ts_list: list[str]) -> None:
        """board entry — minimal (ts + summary)."""
        path.write_text(
            "\n".join(
                json.dumps(
                    {
                        "ts": ts,
                        "summary": f"board entry {i}",
                        "status": "진행 중",
                        "message_id": str(1000 + i),
                        "thread_id": str(1000 + i),
                    },
                    ensure_ascii=False,
                )
                for i, ts in enumerate(kst_ts_list)
            )
            + "\n",
            encoding="utf-8",
        )

    def test_mismatch_detected(self, tmp_path: Path) -> None:
        """1h 내 10 directive + 1 board → mismatch (10 > 1 + 5 grace)."""
        now = datetime(2026, 5, 24, 8, 0, tzinfo=timezone.utc)
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        self._make_detect_jsonl(
            detect,
            [
                ((now - timedelta(minutes=30)).isoformat().replace("+00:00", "Z"), f"기능 {i} 추가해줘")
                for i in range(10)
            ],
        )
        self._make_board_jsonl(
            board,
            [(now - timedelta(minutes=20)).astimezone(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M KST")],
        )
        result = detect_mismatch(
            detect_path=detect,
            board_path=board,
            now=now,
            window_minutes=60,
            grace_count=5,
        )
        assert result.detect_count == 10
        assert result.board_count == 1
        assert result.mismatch is True
        assert len(result.sample_summaries) == 3

    def test_no_mismatch_under_grace(self, tmp_path: Path) -> None:
        """detect 3 + board 0 → grace 5 안 → mismatch False."""
        now = datetime(2026, 5, 24, 8, 0, tzinfo=timezone.utc)
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        self._make_detect_jsonl(
            detect,
            [
                ((now - timedelta(minutes=10)).isoformat().replace("+00:00", "Z"), "수정해줘"),
                ((now - timedelta(minutes=15)).isoformat().replace("+00:00", "Z"), "추가해"),
                ((now - timedelta(minutes=20)).isoformat().replace("+00:00", "Z"), "진행해"),
            ],
        )
        # 빈 board
        board.write_text("", encoding="utf-8")
        result = detect_mismatch(
            detect_path=detect,
            board_path=board,
            now=now,
            window_minutes=60,
            grace_count=5,
        )
        assert result.detect_count == 3
        assert result.board_count == 0
        assert result.mismatch is False  # 3 <= 0+5

    def test_window_excludes_old_entries(self, tmp_path: Path) -> None:
        """1h 윈도우 밖 entry 는 카운트 안 됨."""
        now = datetime(2026, 5, 24, 8, 0, tzinfo=timezone.utc)
        detect = tmp_path / "directive-detect.jsonl"
        board = tmp_path / "directive-board.jsonl"
        self._make_detect_jsonl(
            detect,
            [
                # 2h 전 — 윈도우 밖
                ((now - timedelta(hours=2)).isoformat().replace("+00:00", "Z"), "기능 추가해줘"),
                # 30min 전 — 윈도우 안
                ((now - timedelta(minutes=30)).isoformat().replace("+00:00", "Z"), "수정해줘"),
            ],
        )
        board.write_text("", encoding="utf-8")
        result = detect_mismatch(
            detect_path=detect,
            board_path=board,
            now=now,
            window_minutes=60,
            grace_count=0,
        )
        assert result.detect_count == 1  # 1h 안만

    def test_missing_files_graceful(self, tmp_path: Path) -> None:
        result = detect_mismatch(
            detect_path=tmp_path / "missing-detect.jsonl",
            board_path=tmp_path / "missing-board.jsonl",
            now=datetime(2026, 5, 24, 8, 0, tzinfo=timezone.utc),
        )
        assert result.detect_count == 0
        assert result.board_count == 0
        assert result.mismatch is False


class TestFormatMismatchPush:
    def test_format_polite_tone(self) -> None:
        from directive_detect import MismatchSnapshot

        snap = MismatchSnapshot(
            window_minutes=60,
            detect_count=8,
            board_count=2,
            mismatch=True,
            sample_summaries=["기능 X 추가", "버그 Y 수정", "스펙 Z 갱신"],
        )
        msg = format_mismatch_push(snap)
        # 정중체 검증
        assert "합니다" in msg or "겠습니다" in msg
        # 영어 push/post/send 금지
        assert "push" not in msg.lower()
        assert "post" not in msg.lower()
        # 본질 정보 포함
        assert "8" in msg
        assert "2" in msg
        assert "기능 X 추가" in msg
