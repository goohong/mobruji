"""cycle-status digest 단위 테스트 (#804, embed 개편 #840).

`read_cycle_status` / `format_cycle_digest` 두 함수가
`~/.mobruji/cycle-status.json` 입력 4분기(정상 / 누락 필드 / 빈 dict / 깨진 JSON)
를 정확히 처리하는지 검증.

사용자 룰 (2026-05-23 #모부르지):
    기존 PR open/머지 24h digest 폐기.
    4 워크트리(be/fe/rev/plan) 각 한 줄씩.

#840 UX 개선:
    plain markdown 문자열 → discord.Embed.
    field name 에 워크트리별 emoji prefix, value 는 "진행: ...\\n최근: ..." 2줄.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock
from zoneinfo import ZoneInfo

PARENT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PARENT_DIR))

# discord 는 embed 검증을 위해 반드시 실제 모듈을 사용한다 (#840).
# `tests/__init__.py` 가 venv 내 실제 discord 를 우선 sys.modules 에 적재한다.
# 그래도 누락 환경 대비 — 진짜 import 시도 후 실패 시에만 MagicMock.
try:
    import discord as _real_discord  # noqa: F401
except ImportError:
    sys.modules["discord"] = mock.MagicMock()

# requests/dotenv 는 단순 stub 으로 충분.
for missing in ("requests", "dotenv"):
    if missing not in sys.modules:
        stub = mock.MagicMock()
        if missing == "dotenv":
            stub.load_dotenv = lambda *a, **kw: None
        sys.modules[missing] = stub

import bot  # noqa: E402


# ─────────────────────────────────────────────────────────────────────────────
# read_cycle_status
# ─────────────────────────────────────────────────────────────────────────────


class ReadCycleStatusTest(unittest.TestCase):
    """파일 입출력 + JSON 파싱 분기 검증."""

    def test_valid_json_returns_dict(self) -> None:
        sample = {
            "be": {"in_progress": "PR #804", "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as fp:
            json.dump(sample, fp)
            tmp_path = fp.name
        try:
            result = bot.read_cycle_status(tmp_path)
            self.assertEqual(result, sample)
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_missing_file_returns_none(self) -> None:
        # 존재하지 않는 path.
        result = bot.read_cycle_status("/tmp/__never_exists_cycle_status__.json")
        self.assertIsNone(result)

    def test_malformed_json_returns_none(self) -> None:
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as fp:
            fp.write("{not valid json,,,")
            tmp_path = fp.name
        try:
            result = bot.read_cycle_status(tmp_path)
            self.assertIsNone(result)
        finally:
            Path(tmp_path).unlink(missing_ok=True)


# ─────────────────────────────────────────────────────────────────────────────
# format_cycle_digest (embed 개편 #840)
# ─────────────────────────────────────────────────────────────────────────────


def _field_by_ws(embed, ws):
    """embed.fields 에서 `name` 끝이 ``ws`` 인 것을 반환. 못 찾으면 AssertionError."""
    for field in embed.fields:
        # name 형식: "{emoji} {ws}".
        if field.name.split()[-1] == ws:
            return field
    raise AssertionError(
        f"field for ws={ws!r} not found; fields={[f.name for f in embed.fields]}"
    )


class FormatCycleDigestTest(unittest.TestCase):
    """4 워크트리 embed field 조립 + signature 산출 검증."""

    def test_full_status_renders_all_four_fields(self) -> None:
        status = {
            "be": {
                "in_progress": "PR #804 digest cron 작성 중",
                "last_completed": {
                    "pr": "#803",
                    "title": "bot.py auto-ack 동적화",
                    "merged_at": "2026-05-23T12:40:00Z",
                },
            },
            "fe": {
                "in_progress": None,
                "last_completed": {
                    "pr": "#796",
                    "title": "voice-range auto handleRetry",
                    "merged_at": "2026-05-23T09:23:03Z",
                },
            },
            "rev": {
                "in_progress": None,
                "last_completed": {
                    "pr": None,
                    "title": "P0 404 진단",
                    "merged_at": "2026-05-23T11:00:00Z",
                },
            },
            "plan": {
                "in_progress": "spec 보강",
                "last_completed": {
                    "pr": "#801",
                    "title": "helper agent spec",
                    "merged_at": "2026-05-23T11:12:10Z",
                },
            },
        }
        embed, signature = bot.format_cycle_digest(status)

        # title + description (timestamp 포함).
        self.assertIn("Cycle Digest", embed.title)
        self.assertIn("🕒", embed.description)
        self.assertIn("KST", embed.description)

        # 4 워크트리 field 정확히.
        self.assertEqual(len(embed.fields), 4)

        # be: in_progress + 최근 (pr + title).
        be_field = _field_by_ws(embed, "be")
        self.assertIn("🛠", be_field.name)
        self.assertIn("진행: PR #804 digest cron 작성 중", be_field.value)
        self.assertIn("최근: #803 (bot.py auto-ack 동적화)", be_field.value)
        # field value 는 진행/최근 + 빈 줄 separator → 2개 newline (#1023 가독성 개선).
        self.assertEqual(be_field.value.count("\n"), 2)

        # fe: in_progress null → "idle".
        fe_field = _field_by_ws(embed, "fe")
        self.assertIn("🎨", fe_field.name)
        self.assertIn("진행: idle", fe_field.value)
        self.assertIn("최근: #796 (voice-range auto handleRetry)", fe_field.value)

        # rev: pr null 이어도 title 만으로 표시.
        rev_field = _field_by_ws(embed, "rev")
        self.assertIn("🔍", rev_field.name)
        self.assertIn("진행: idle", rev_field.value)
        self.assertIn("최근: P0 404 진단", rev_field.value)

        # plan: in_progress + pr+title.
        plan_field = _field_by_ws(embed, "plan")
        self.assertIn("📋", plan_field.name)
        self.assertIn("진행: spec 보강", plan_field.value)
        self.assertIn("최근: #801 (helper agent spec)", plan_field.value)

        # signature 는 시간 무관 — be/fe/rev/plan 4 파트 포함.
        self.assertIn("be=", signature)
        self.assertIn("fe=", signature)
        self.assertIn("rev=", signature)
        self.assertIn("plan=", signature)

    def test_none_status_returns_fallback_embed(self) -> None:
        embed, signature = bot.format_cycle_digest(None)
        self.assertIn("cycle-status.json 읽기 실패", embed.description)
        # timestamp 는 fallback path 에서도 항상 포함.
        self.assertIn("KST", embed.description)
        self.assertEqual(signature, "unavailable")
        # fallback 은 idle color.
        self.assertEqual(embed.color.value, bot.CYCLE_DIGEST_COLOR_IDLE)
        # fields 없음.
        self.assertEqual(len(embed.fields), 0)

    def test_missing_workspace_renders_as_idle(self) -> None:
        # be 만 존재. 나머지(fe/rev/plan) 는 누락 — 모두 "idle" / "없음" 으로 출력.
        status = {
            "be": {
                "in_progress": "작업 중",
                "last_completed": {"pr": "#100", "title": "Sample"},
            },
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertEqual(len(embed.fields), 4)
        for ws in ("fe", "rev", "plan"):
            field = _field_by_ws(embed, ws)
            self.assertIn("진행: idle", field.value)
            self.assertIn("최근: 없음", field.value)

    def test_empty_dict_renders_all_idle(self) -> None:
        embed, signature = bot.format_cycle_digest({})
        self.assertEqual(len(embed.fields), 4)
        for ws in bot.CYCLE_DIGEST_WORKSPACES:
            field = _field_by_ws(embed, ws)
            self.assertIn("진행: idle", field.value)
            self.assertIn("최근: 없음", field.value)
        # signature: 모든 워크트리 missing.
        self.assertEqual(signature.count("missing"), 4)
        # 전부 idle → gray color.
        self.assertEqual(embed.color.value, bot.CYCLE_DIGEST_COLOR_IDLE)

    def test_in_progress_non_string_non_dict_falls_back_to_idle(self) -> None:
        # in_progress 가 int / list 등 미지원 타입이면 idle 로 fallback.
        # 빈 dict 는 "진행 중(스키마 미상)" — 이론상 nmae 가 갱신할 일 없음(가드용).
        status = {
            "be": {"in_progress": 12345, "last_completed": None},
            "fe": {"in_progress": [], "last_completed": None},
            "rev": {"in_progress": {}, "last_completed": None},
            "plan": {"in_progress": "  ", "last_completed": None},  # 공백 trim
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertIn("진행: idle", _field_by_ws(embed, "be").value)   # int → idle
        self.assertIn("진행: idle", _field_by_ws(embed, "fe").value)   # [] → idle
        self.assertIn("진행 중(스키마 미상)", _field_by_ws(embed, "rev").value)  # 빈 dict
        self.assertIn("진행: idle", _field_by_ws(embed, "plan").value)  # '  ' → idle

    # ─────────────────────────────────────────────────────────────────────
    # in_progress dict 형식 (nmae 현행 schema, #821)
    # ─────────────────────────────────────────────────────────────────────

    def test_in_progress_dict_with_issue_and_pr_uses_issue(self) -> None:
        # issue 와 pr 둘 다 있으면 issue 가 1차 식별자 (nmae 관례).
        status = {
            "be": {
                "in_progress": {
                    "issue": "#809",
                    "pr": "#810",
                    "title": "context_auto_clear_loop 복구",
                    "started_at": "2026-05-23T13:00:00Z",
                },
                "last_completed": None,
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        be_value = _field_by_ws(embed, "be").value
        self.assertIn("#809", be_value)
        self.assertIn("context_auto_clear_loop 복구", be_value)
        # pr 단독 노출은 X (issue 우선) — in_progress 부분만 확인.
        in_progress_section = be_value.split("진행: ")[1].split("\n")[0]
        self.assertNotIn("#810", in_progress_section)

    def test_in_progress_dict_with_only_pr(self) -> None:
        status = {
            "be": {
                "in_progress": {"pr": "#810", "title": "bar"},
                "last_completed": None,
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertIn("진행: #810 bar", _field_by_ws(embed, "be").value)

    def test_in_progress_dict_with_target_uses_colon_format(self) -> None:
        # rev 워크트리: target + title.
        status = {
            "be": {"in_progress": None, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {
                "in_progress": {
                    "target": "docs/features/ spec 셀프 약속 audit",
                    "title": "rev audit — spec drift 점검",
                    "started_at": "2026-05-23T14:30:00Z",
                },
                "last_completed": None,
            },
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        rev_value = _field_by_ws(embed, "rev").value
        self.assertIn(
            "docs/features/ spec 셀프 약속 audit: rev audit", rev_value
        )

    def test_in_progress_dict_with_only_title(self) -> None:
        status = {
            "be": {
                "in_progress": {"title": "only-title"},
                "last_completed": None,
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        be_value = _field_by_ws(embed, "be").value
        self.assertIn("진행: only-title", be_value)
        # id prefix 없어야 함.
        in_progress_section = be_value.split("진행: ")[1].split("\n")[0]
        self.assertNotIn("#", in_progress_section)

    def test_in_progress_dict_none_renders_idle(self) -> None:
        # 명시적 None — 다른 테스트에서도 cover 되지만 #821 회귀 가드용.
        status = {
            "be": {"in_progress": None, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        for ws in bot.CYCLE_DIGEST_WORKSPACES:
            self.assertIn("진행: idle", _field_by_ws(embed, ws).value)

    def test_in_progress_dict_missing_title_uses_fallback(self) -> None:
        # title 누락 — issue 만 있어도 라벨 노출 + "제목 없음".
        status = {
            "be": {
                "in_progress": {"issue": "#999"},
                "last_completed": None,
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertIn("진행: #999 제목 없음", _field_by_ws(embed, "be").value)

    def test_last_completed_missing_title_uses_fallback(self) -> None:
        # title 누락 → "제목 없음".
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {"pr": "#999"},
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertIn("#999 (제목 없음)", _field_by_ws(embed, "be").value)

    def test_signature_changes_with_in_progress(self) -> None:
        # signature 는 in_progress 변경에 민감해야 delta push 가 동작.
        status_v1 = {ws: {"in_progress": None, "last_completed": None}
                     for ws in bot.CYCLE_DIGEST_WORKSPACES}
        status_v2 = dict(status_v1)
        status_v2["be"] = {"in_progress": "새 작업", "last_completed": None}

        _e1, sig1 = bot.format_cycle_digest(status_v1)
        _e2, sig2 = bot.format_cycle_digest(status_v2)
        self.assertNotEqual(sig1, sig2)

    def test_mention_in_title_is_sanitized(self) -> None:
        # title 에 @everyone 이 들어가도 Discord mention 으로 발화되면 안 됨.
        status = {
            "be": {
                "in_progress": None,
                "last_completed": {"pr": "#100", "title": "@everyone fire"},
            },
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        be_value = _field_by_ws(embed, "be").value
        # zero-width space 가 삽입되어 mention 으로 해석되지 않음.
        self.assertNotIn("@everyone fire", be_value)
        self.assertIn("everyone", be_value)  # 본문은 보존

    def test_long_in_progress_truncated(self) -> None:
        # 한 줄 너무 길면 잘리고 … 표시.
        long_text = "x" * 500
        status = {
            "be": {"in_progress": long_text, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        be_value = _field_by_ws(embed, "be").value
        in_progress_section = be_value.split("진행: ")[1].split("\n")[0]
        self.assertLessEqual(len(in_progress_section), bot.CYCLE_DIGEST_FIELD_LINE_LEN)
        self.assertTrue(in_progress_section.endswith("…"))

    # ─────────────────────────────────────────────────────────────────────
    # timestamp (#811)
    # ─────────────────────────────────────────────────────────────────────

    def test_timestamp_line_present_with_injected_now(self) -> None:
        # `now` 주입 시 정확히 그 시각이 KST 포맷으로 description 에 노출.
        fixed_now = datetime(2026, 5, 23, 14, 7, tzinfo=ZoneInfo("Asia/Seoul"))
        status = {
            "be": {"in_progress": None, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status, now=fixed_now)
        self.assertIn("🕒 2026-05-23 14:07 KST", embed.description)

    def test_timestamp_converts_utc_to_kst(self) -> None:
        # UTC 시각 주입 시 KST(+9h)로 변환되어 표시.
        fixed_utc = datetime(2026, 5, 23, 5, 7, tzinfo=ZoneInfo("UTC"))
        embed, _signature = bot.format_cycle_digest({}, now=fixed_utc)
        # UTC 05:07 → KST 14:07
        self.assertIn("🕒 2026-05-23 14:07 KST", embed.description)

    def test_timestamp_not_in_signature(self) -> None:
        # signature 는 시간 무관 — 같은 status 면 시각 달라도 동일 signature.
        status = {
            "be": {"in_progress": "동일", "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        now_a = datetime(2026, 5, 23, 14, 0, tzinfo=ZoneInfo("Asia/Seoul"))
        now_b = datetime(2026, 5, 23, 23, 59, tzinfo=ZoneInfo("Asia/Seoul"))
        _e1, sig1 = bot.format_cycle_digest(status, now=now_a)
        _e2, sig2 = bot.format_cycle_digest(status, now=now_b)
        self.assertEqual(sig1, sig2)

    def test_timestamp_present_in_fallback_path(self) -> None:
        # status=None (cycle-status.json 읽기 실패) 분기에도 timestamp 노출.
        fixed_now = datetime(2026, 5, 23, 9, 0, tzinfo=ZoneInfo("Asia/Seoul"))
        embed, signature = bot.format_cycle_digest(None, now=fixed_now)
        self.assertEqual(signature, "unavailable")
        self.assertIn("🕒 2026-05-23 09:00 KST", embed.description)

    # ─────────────────────────────────────────────────────────────────────
    # embed UX 개선 (#840)
    # ─────────────────────────────────────────────────────────────────────

    def test_embed_title_present(self) -> None:
        # title 은 항상 "Cycle Digest" 포함.
        embed, _signature = bot.format_cycle_digest({})
        self.assertIn("Cycle Digest", embed.title)

    def test_embed_exactly_four_fields_on_full_status(self) -> None:
        # status 정상이면 항상 4 개 field (워크트리당 1개).
        status = {ws: {"in_progress": None, "last_completed": None}
                  for ws in bot.CYCLE_DIGEST_WORKSPACES}
        embed, _signature = bot.format_cycle_digest(status)
        self.assertEqual(len(embed.fields), 4)

    def test_embed_field_names_have_emoji_prefix(self) -> None:
        # field name 에 워크트리별 emoji prefix.
        embed, _signature = bot.format_cycle_digest({})
        emoji_for_ws = bot.CYCLE_DIGEST_WORKSPACE_EMOJI
        for field in embed.fields:
            # name 형식: "{emoji} {ws}".
            ws = field.name.split()[-1]
            self.assertIn(ws, bot.CYCLE_DIGEST_WORKSPACES)
            self.assertTrue(
                field.name.startswith(emoji_for_ws[ws]),
                f"field name {field.name!r} 이 emoji 로 시작하지 않음",
            )

    def test_embed_field_values_have_labels(self) -> None:
        # 각 field value 는 "진행:" 와 "최근:" 라벨 둘 다 포함.
        embed, _signature = bot.format_cycle_digest({})
        for field in embed.fields:
            self.assertIn("진행:", field.value)
            self.assertIn("최근:", field.value)
            # 진행/최근 사이 빈 줄 separator (#1023 가독성 개선) → 2 newline.
            self.assertEqual(field.value.count("\n"), 2)

    def test_embed_fields_are_not_inline(self) -> None:
        # 한 줄에 하나씩 (스캔 친화적) — inline=False.
        embed, _signature = bot.format_cycle_digest({})
        for field in embed.fields:
            self.assertFalse(field.inline)

    def test_embed_color_active_when_any_in_progress(self) -> None:
        # 하나라도 in_progress 가 있으면 active(blue) color.
        status = {
            "be": {"in_progress": "작업", "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }
        embed, _signature = bot.format_cycle_digest(status)
        self.assertEqual(embed.color.value, bot.CYCLE_DIGEST_COLOR_ACTIVE)

    def test_embed_color_idle_when_all_idle(self) -> None:
        # 4 워크트리 모두 idle 이면 idle(gray) color.
        status = {ws: {"in_progress": None, "last_completed": None}
                  for ws in bot.CYCLE_DIGEST_WORKSPACES}
        embed, _signature = bot.format_cycle_digest(status)
        self.assertEqual(embed.color.value, bot.CYCLE_DIGEST_COLOR_IDLE)

    def test_embed_timestamp_set(self) -> None:
        # embed.timestamp 가 주입된 now (KST) 와 일치.
        fixed_now = datetime(2026, 5, 23, 14, 7, tzinfo=ZoneInfo("Asia/Seoul"))
        embed, _signature = bot.format_cycle_digest({}, now=fixed_now)
        # Discord embed timestamp 는 tz-aware datetime.
        self.assertIsNotNone(embed.timestamp)
        self.assertEqual(embed.timestamp, fixed_now)

    def test_embed_footer_with_interval(self) -> None:
        # interval_seconds 주어지면 footer 에 "interval=Ns".
        embed, _signature = bot.format_cycle_digest({}, interval_seconds=900)
        self.assertIsNotNone(embed.footer.text)
        self.assertIn("interval=900s", embed.footer.text)

    def test_embed_footer_absent_without_interval(self) -> None:
        # interval_seconds 미지정이면 footer 없음.
        embed, _signature = bot.format_cycle_digest({})
        # discord.Embed footer.text 는 미설정 시 None.
        self.assertIn(embed.footer.text, (None, ""))

    # ─────────────────────────────────────────────────────────────────────
    # 가독성 개선 #1023 (사용자 P0 — "진행과 최근 구분 안돼")
    # ─────────────────────────────────────────────────────────────────────

    def test_field_value_has_blank_line_separator(self) -> None:
        """진행/최근 사이 빈 줄 — 시선 분리 의무."""
        embed, _signature = bot.format_cycle_digest({})
        for field in embed.fields:
            # 빈 줄 = "\n\n" 시퀀스 또는 정확히 3줄 (진행 / 빈 / 최근).
            self.assertIn("\n\n", field.value, f"빈 줄 누락: {field.value!r}")

    def test_field_value_uses_in_progress_emoji(self) -> None:
        """진행 라벨 앞에 🔄 emoji — 첫 눈에 의미 인지."""
        embed, _signature = bot.format_cycle_digest({})
        for field in embed.fields:
            self.assertIn("🔄", field.value)
            self.assertIn("✅", field.value)

    def test_field_value_emoji_precedes_labels(self) -> None:
        """🔄 진행 / ✅ 최근 순서 — emoji+label 연속 배치."""
        embed, _signature = bot.format_cycle_digest({})
        for field in embed.fields:
            # 🔄 가 "진행:" 앞에, ✅ 가 "최근:" 앞에 위치.
            in_progress_idx = field.value.index("진행:")
            recent_idx = field.value.index("최근:")
            in_progress_emoji_idx = field.value.index("🔄")
            recent_emoji_idx = field.value.index("✅")
            self.assertLess(in_progress_emoji_idx, in_progress_idx)
            self.assertLess(recent_emoji_idx, recent_idx)
            # 진행 이 최근 보다 앞 (순서 보존).
            self.assertLess(in_progress_idx, recent_idx)


# ─────────────────────────────────────────────────────────────────────────────
# read_autodeploy_status / 자동 배포 차단 digest field (#1495)
# ─────────────────────────────────────────────────────────────────────────────


class ReadAutodeployStatusTest(unittest.TestCase):
    """autodeploy-status.json 파싱 + diverged 필터 검증."""

    def _write(self, payload) -> str:
        with tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8"
        ) as handle:
            json.dump(payload, handle)
            return handle.name

    def test_missing_file_returns_none(self) -> None:
        self.assertIsNone(bot.read_autodeploy_status("/nonexistent/autodeploy.json"))

    def test_malformed_json_returns_none(self) -> None:
        with tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8"
        ) as handle:
            handle.write("{not json")
            tmp_path = handle.name
        try:
            self.assertIsNone(bot.read_autodeploy_status(tmp_path))
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_non_dict_returns_none(self) -> None:
        tmp_path = self._write(["not", "a", "dict"])
        try:
            self.assertIsNone(bot.read_autodeploy_status(tmp_path))
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_empty_dict_returns_empty(self) -> None:
        tmp_path = self._write({})
        try:
            self.assertEqual(bot.read_autodeploy_status(tmp_path), {})
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_diverged_entries_only(self) -> None:
        tmp_path = self._write(
            {
                "mobruji-discord-bridge": {
                    "state": "diverged",
                    "local": "abc12345",
                    "remote": "def67890",
                    "ts": "2026-06-03T12:00:00+09:00",
                },
                "mobruji-agent": {"state": "ok"},
            }
        )
        try:
            result = bot.read_autodeploy_status(tmp_path)
            self.assertEqual(list(result), ["mobruji-discord-bridge"])
        finally:
            Path(tmp_path).unlink(missing_ok=True)


class AutodeployDigestFieldTest(unittest.TestCase):
    """format_cycle_digest 가 자동 배포 차단을 field + signature 로 노출하는지."""

    def _status(self) -> dict:
        return {
            "be": {"in_progress": None, "last_completed": None},
            "fe": {"in_progress": None, "last_completed": None},
            "rev": {"in_progress": None, "last_completed": None},
            "plan": {"in_progress": None, "last_completed": None},
        }

    def test_no_autodeploy_status_adds_no_field(self) -> None:
        embed, signature = bot.format_cycle_digest(self._status())
        self.assertNotIn("자동 배포 차단", [f.name for f in embed.fields])
        self.assertNotIn("autodeploy=", signature)

    def test_empty_autodeploy_status_adds_no_field(self) -> None:
        embed, signature = bot.format_cycle_digest(self._status(), autodeploy_status={})
        self.assertFalse(any("자동 배포 차단" in f.name for f in embed.fields))
        self.assertNotIn("autodeploy=", signature)

    def test_diverged_renders_warning_field_and_signature(self) -> None:
        autodeploy = {
            "mobruji-discord-bridge": {
                "state": "diverged",
                "local": "abc12345",
                "remote": "def67890",
                "ts": "2026-06-03T12:00:00+09:00",
            }
        }
        embed, signature = bot.format_cycle_digest(
            self._status(), autodeploy_status=autodeploy
        )
        warn_field = next(f for f in embed.fields if "자동 배포 차단" in f.name)
        self.assertIn("mobruji-discord-bridge", warn_field.value)
        self.assertIn("abc12345", warn_field.value)
        self.assertIn("def67890", warn_field.value)
        self.assertIn("autodeploy=mobruji-discord-bridge", signature)

    def test_multiple_services_sorted_in_signature(self) -> None:
        autodeploy = {
            "mobruji-discord-bridge": {
                "state": "diverged",
                "local": "a1",
                "remote": "b2",
            },
            "mobruji-agent": {"state": "diverged", "local": "c3", "remote": "d4"},
        }
        _embed, signature = bot.format_cycle_digest(
            self._status(), autodeploy_status=autodeploy
        )
        self.assertIn("autodeploy=mobruji-agent,mobruji-discord-bridge", signature)

    def test_diverged_shows_in_fallback_path(self) -> None:
        autodeploy = {
            "mobruji-agent": {"state": "diverged", "local": "a1", "remote": "b2"}
        }
        embed, signature = bot.format_cycle_digest(None, autodeploy_status=autodeploy)
        self.assertTrue(any("자동 배포 차단" in f.name for f in embed.fields))
        self.assertIn("autodeploy=mobruji-agent", signature)


if __name__ == "__main__":
    unittest.main()
