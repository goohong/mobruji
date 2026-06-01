"""subagent_runner.py — role/task prompt + flag 순수 함수 테스트 (#1396).

실제 claude -p subprocess 는 통합(NCP) 검증 — 여기선 prompt/argv 빌드만.
"""

from __future__ import annotations


def test_exec_enabled_default_off(monkeypatch):
    import subagent_runner as sr
    monkeypatch.delenv("MOBRUJI_SUBAGENT_EXEC", raising=False)
    assert sr.exec_enabled() is False
    monkeypatch.setenv("MOBRUJI_SUBAGENT_EXEC", "1")
    assert sr.exec_enabled() is True
    monkeypatch.setenv("MOBRUJI_SUBAGENT_EXEC", "0")
    assert sr.exec_enabled() is False


def test_role_prompt_strips_frontmatter(tmp_path):
    import subagent_runner as sr
    d = tmp_path / ".claude" / "agents"
    d.mkdir(parents=True)
    (d / "rev.md").write_text(
        "---\nname: rev\nmodel: x\n---\n너는 rev sub-agent다. 코드 리뷰 + 품질 검증 전담.",
        encoding="utf-8",
    )
    body = sr.role_prompt("rev", tmp_path)
    assert body == "너는 rev sub-agent다. 코드 리뷰 + 품질 검증 전담."
    assert "name: rev" not in body


def test_role_prompt_fallback_when_missing(tmp_path):
    import subagent_runner as sr
    body = sr.role_prompt("be", tmp_path)
    assert "be 사이클 sub-agent" in body


def test_build_task_prompt_includes_forum_and_ids(tmp_path):
    import subagent_runner as sr
    p = sr.build_task_prompt("rev", "d1", "제목X", "작업내용Y", "T123")
    for needle in ("d1", "rev", "제목X", "작업내용Y", "T123", "forum-comment"):
        assert needle in p


def test_build_task_prompt_no_thread(tmp_path):
    import subagent_runner as sr
    p = sr.build_task_prompt("be", "d2", "t", "k", "")
    assert "thread id 미지정" in p
    assert "forum-comment" not in p


def test_claude_argv(monkeypatch):
    import subagent_runner as sr
    monkeypatch.setenv("CLAUDE_BIN", "claude --dangerously-skip-permissions")
    argv = sr._claude_argv("PROMPT", "ROLE")
    assert argv[0] == "claude"
    assert "--dangerously-skip-permissions" in argv
    assert "-p" in argv and "PROMPT" in argv
    assert "--append-system-prompt" in argv and "ROLE" in argv
    assert "--model" in argv


def test_worktree_path():
    import subagent_runner as sr
    assert str(sr.worktree_path("rev")).endswith("mobruji-rev")


def test_exec_releases_lock_on_argv_error(isolated_db, monkeypatch):
    """#1398 rev 🟡-1: argv 빌드(shlex.split) ValueError 여도 lock 해제 보장."""
    import asyncio
    import subagent_runner as sr
    import events as ev

    monkeypatch.setenv("CLAUDE_BIN", 'claude "unbalanced')  # shlex.split → ValueError
    ev.set_state("in_flight_agents", ["rev"])
    ev.set_state("in_flight_started", {"rev": "2026-01-01T00:00:00+00:00"})

    # thread_id="" → forum_comment 호출 안 함 (Discord 토큰 불필요). 예외 전파 없어야.
    asyncio.run(sr.run_subagent_execution("rev", "d1", "t", "k", ""))

    assert "rev" not in (ev.get_state("in_flight_agents") or [])
    assert "rev" not in (ev.get_state("in_flight_started") or {})


def test_build_task_prompt_uses_asis_tobe_report_format(tmp_path):
    """#1413: 보고 양식 = AS-IS/TO-BE 마크다운 (코드펜스 금지 지시 포함)."""
    import subagent_runner as sr
    p = sr.build_task_prompt("rev", "d1", "제목", "작업", "T9")
    assert "AS-IS" in p and "TO-BE" in p
    assert "## " in p  # Discord 마크다운 제목
    assert "코드펜스" in p  # ``` 로 감싸지 말라는 지시
    # thread 없을 때도 양식 포함
    p2 = sr.build_task_prompt("be", "d2", "t", "k", "")
    assert "AS-IS" in p2


def test_find_pr_number(monkeypatch):
    import subagent_runner as sr, types, json as _json
    def fr(argv, **k):
        if "rev-parse" in argv:
            return types.SimpleNamespace(stdout="feat/x\n", returncode=0, stderr="")
        return types.SimpleNamespace(stdout=_json.dumps([{"number": 7}]), returncode=0, stderr="")
    monkeypatch.setattr(sr.subprocess, "run", fr)
    assert sr._find_pr_number("/tmp/wt") == "7"


def test_on_exec_success_no_pr_completes_and_notifies(monkeypatch):
    """#1417: PR 없으면 directive 완료 전이 + #모부르지 알림."""
    import subagent_runner as sr, tools_cycle as tc
    monkeypatch.setattr(sr, "_find_pr_number", lambda wt: None)
    comp, notes = [], []
    monkeypatch.setattr(tc, "set_directive_forum_status",
                        lambda did, st, **k: comp.append((did, st)))
    monkeypatch.setattr(sr, "_notify_user_done",
                        lambda title, body, thread_id="", **k: notes.append(body))
    sr._on_exec_success("rev", "d1", "제목", "T1", "/tmp/wt")
    assert comp == [("d1", "completed")]
    assert notes and "끝났" in notes[0]


def test_on_exec_success_with_pr_triggers_rev_and_notifies(monkeypatch):
    import subagent_runner as sr, tools_queue as tq
    monkeypatch.setattr(sr, "_find_pr_number", lambda wt: "9")
    monkeypatch.setattr(sr, "_ensure_pr_xrefs", lambda *a, **k: None)
    triggered, notes = [], []
    monkeypatch.setattr(tq, "enqueue_rev_for_pr_if_any",
                        lambda c, wt: triggered.append(c) or "9")
    monkeypatch.setattr(sr, "_notify_user_done",
                        lambda title, body, thread_id="", **k: notes.append(body))
    sr._on_exec_success("be", "d1", "제목", "T1", "/tmp/wt")
    assert triggered == ["be"]
    assert any("PR #9" in b for b in notes)


def test_ensure_pr_xrefs_no_markers_skips_gh(monkeypatch):
    """rev-pr-* 합성 id + cycle/thread 없음 → 추가할 marker 없어 gh 미호출."""
    import subagent_runner as sr
    called = []
    monkeypatch.setattr(sr.subprocess, "run", lambda *a, **k: called.append(a) or None)
    sr._ensure_pr_xrefs("9", "rev-pr-1422", "", "", "/tmp/wt")
    sr._ensure_pr_xrefs("9", "", "nmae", "x", "/tmp/wt")  # nmae 는 be/fe/rev/plan 아님
    assert called == []


def test_ensure_pr_xrefs_adds_both_markers(monkeypatch):
    """directive + cycle-forum 둘 다 본문에 보강 (기존 본문에 없을 때)."""
    import subagent_runner as sr
    calls = []

    def fake_run(args, **k):
        calls.append(args)
        class R:
            stdout = "## Summary\n기존 본문" if args[2] == "view" else ""
        return R()

    monkeypatch.setattr(sr.subprocess, "run", fake_run)
    sr._ensure_pr_xrefs("9", "1510610000000000002", "plan", "1510548826107154545", "/tmp/wt")
    edit = [a for a in calls if "edit" in a]
    assert edit, "gh pr edit 호출돼야"
    body = edit[0][edit[0].index("--body") + 1]
    assert "directive: 1510610000000000002" in body
    assert "cycle-forum: plan:1510548826107154545" in body
    assert "기존 본문" in body  # 기존 보존
