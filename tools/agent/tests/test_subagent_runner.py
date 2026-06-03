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


def test_on_exec_success_be_no_pr_does_not_complete(monkeypatch):
    """#1455: be(구현 사이클)가 PR 없이 rc==0 종료 → 완료 전이 금지 + ⚠️ 미완 알림."""
    import subagent_runner as sr, tools_cycle as tc
    monkeypatch.setattr(sr, "_find_pr_number", lambda wt: None)
    comp, comments, notes = [], [], []
    monkeypatch.setattr(tc, "set_directive_forum_status",
                        lambda did, st, **k: comp.append((did, st)))
    monkeypatch.setattr(sr, "_safe_comment",
                        lambda tid, body: comments.append(body))
    monkeypatch.setattr(sr, "_notify_user_done",
                        lambda title, body, thread_id="", **k: notes.append(body))
    sr._on_exec_success("be", "d1", "제목", "T1", "/tmp/wt")
    assert comp == []  # 완료 전이 안 함
    assert notes == []  # 완료 알림 안 함
    assert comments and "미완" in comments[0]


def test_on_exec_success_fe_no_pr_does_not_complete(monkeypatch):
    """#1455: fe 도 구현 사이클 — PR 없이 종료는 미완."""
    import subagent_runner as sr, tools_cycle as tc
    monkeypatch.setattr(sr, "_find_pr_number", lambda wt: None)
    comp = []
    monkeypatch.setattr(tc, "set_directive_forum_status",
                        lambda did, st, **k: comp.append((did, st)))
    monkeypatch.setattr(sr, "_safe_comment", lambda tid, body: None)
    monkeypatch.setattr(sr, "_notify_user_done", lambda *a, **k: None)
    sr._on_exec_success("fe", "d2", "제목", "T2", "/tmp/wt")
    assert comp == []


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


def test_persist_pr_cycle_thread_stores_mapping(isolated_db):
    """(#7) be/fe/rev/plan + snowflake → agent_state pr_cycle_thread:<N> 저장."""
    import subagent_runner as sr, events as ev
    sr._persist_pr_cycle_thread("1593", "be", "1509466456230989926")
    assert ev.get_state("pr_cycle_thread:1593") == {
        "cycle": "be", "thread_id": "1509466456230989926"
    }


def test_persist_pr_cycle_thread_skips_invalid_cycle(isolated_db):
    """(#7) nmae/infra 등 비-cycle 은 저장 안 함."""
    import subagent_runner as sr, events as ev
    sr._persist_pr_cycle_thread("10", "nmae", "1509466456230989926")
    assert ev.get_state("pr_cycle_thread:10") is None


def test_persist_pr_cycle_thread_skips_non_snowflake(isolated_db):
    """(#7) thread_id 가 빈/짧은 값이면 저장 안 함(오태깅 방지)."""
    import subagent_runner as sr, events as ev
    sr._persist_pr_cycle_thread("11", "fe", "")
    sr._persist_pr_cycle_thread("12", "fe", "abc")
    assert ev.get_state("pr_cycle_thread:11") is None
    assert ev.get_state("pr_cycle_thread:12") is None


def test_on_exec_success_persists_mapping_from_thread_arg(isolated_db, monkeypatch):
    """(#7) PR 있으면 launch thread_id 인자로 pr_cycle_thread 매핑 영속 — 본문 주입과 무관.

    directive state 에 cycle_thread_id 가 없어도(LAUNCH_THREAD_ID 미상속 시나리오)
    thread_id 인자가 authoritative source 라 매핑이 저장된다(PR #1593 구멍 차단).
    """
    import subagent_runner as sr, tools_queue as tq, events as ev
    monkeypatch.setattr(sr, "_find_pr_number", lambda wt: "1593")
    monkeypatch.setattr(sr, "_ensure_pr_xrefs", lambda *a, **k: None)
    monkeypatch.setattr(tq, "enqueue_rev_for_pr_if_any", lambda c, wt: "1593")
    monkeypatch.setattr(sr, "_notify_user_done", lambda *a, **k: None)
    # directive state 비움 — thread_id 인자 단독으로 매핑돼야.
    sr._on_exec_success("be", "d1", "제목", "1509466456230989926", "/tmp/wt")
    assert ev.get_state("pr_cycle_thread:1593") == {
        "cycle": "be", "thread_id": "1509466456230989926"
    }


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


def test_kill_process_group_noop_for_invalid_pgid(monkeypatch):
    """#1481: pgid<=1 이면 killpg 호출 안 함 (잘못된 그룹/init 보호)."""
    import asyncio
    import subagent_runner as sr
    calls = []
    monkeypatch.setattr(sr.os, "killpg", lambda pg, sig: calls.append((pg, sig)))
    asyncio.run(sr._kill_process_group(0, "be", reason="t"))
    asyncio.run(sr._kill_process_group(1, "be", reason="t"))
    assert calls == []


def test_kill_process_group_term_then_kill(monkeypatch):
    """#1481: 살아있는 그룹 → SIGTERM 후 (유예) SIGKILL 순서로 자식까지 정리."""
    import asyncio
    import signal
    import subagent_runner as sr
    sigs = []
    monkeypatch.setattr(sr.os, "killpg", lambda pg, sig: sigs.append(sig))

    async def _nosleep(_):
        return None
    monkeypatch.setattr(sr.asyncio, "sleep", _nosleep)
    asyncio.run(sr._kill_process_group(12345, "rev", reason="t"))
    assert sigs == [signal.SIGTERM, signal.SIGKILL]


def test_kill_process_group_empty_graceful(monkeypatch):
    """#1481: 그룹에 남은 프로세스 없음(ProcessLookupError) → SIGKILL 안 함, 예외 전파 X."""
    import asyncio
    import signal
    import subagent_runner as sr
    sigs = []

    def _killpg(pg, sig):
        sigs.append(sig)
        raise ProcessLookupError
    monkeypatch.setattr(sr.os, "killpg", _killpg)
    asyncio.run(sr._kill_process_group(12345, "be", reason="t"))
    assert sigs == [signal.SIGTERM]  # SIGTERM 에서 비어 있음 확인 → 즉시 return


def test_ephemeral_worktree_add_calls_git_and_returns_path(monkeypatch):
    """#1531: infra ephemeral 워크트리 — git worktree add origin/develop 호출 + 고유 경로 반환."""
    import types
    import subagent_runner as sr
    calls = []

    def fake_run(argv, **k):
        calls.append(argv)
        return types.SimpleNamespace(returncode=0, stdout="", stderr="")
    monkeypatch.setattr(sr.subprocess, "run", fake_run)
    wt = sr._ephemeral_worktree_add("roadmap-1524-fix")
    assert "infra-roadmap-1524-fix" in str(wt)
    # prune + remove(가드) + add 가 호출됨
    assert any("add" in a and "origin/develop" in a for a in calls)


def test_ephemeral_worktree_add_raises_on_failure(monkeypatch):
    """#1531: worktree add 실패(rc!=0) → RuntimeError (호출부 finally 가 lock 해제)."""
    import types
    import subagent_runner as sr

    def fake_run(argv, **k):
        rc = 1 if "add" in argv else 0
        return types.SimpleNamespace(returncode=rc, stdout="", stderr="fatal: exists")
    monkeypatch.setattr(sr.subprocess, "run", fake_run)
    try:
        sr._ephemeral_worktree_add("d1")
        assert False, "RuntimeError 기대"
    except RuntimeError as exc:
        assert "worktree add 실패" in str(exc)


def test_ephemeral_worktree_remove_calls_remove_and_prune(monkeypatch):
    """#1531: teardown — remove --force + prune."""
    import types
    import subagent_runner as sr
    from pathlib import Path
    calls = []
    monkeypatch.setattr(sr.subprocess, "run",
                        lambda argv, **k: calls.append(argv) or types.SimpleNamespace(returncode=0, stdout="", stderr=""))
    sr._ephemeral_worktree_remove(Path("/home/mobruji/mobruji-infra-d1"))
    assert any("remove" in a for a in calls) and any("prune" in a for a in calls)


def test_exec_infra_teardowns_ephemeral_on_failure(isolated_db, monkeypatch):
    """#1531: run_subagent_execution(infra) — ephemeral 생성 후, 실패해도 finally teardown + lock 해제."""
    import asyncio
    import subagent_runner as sr
    import events as ev
    from pathlib import Path

    created = Path("/home/mobruji/mobruji-infra-d9")
    removed = []
    monkeypatch.setattr(sr, "_ephemeral_worktree_add", lambda did: created)
    monkeypatch.setattr(sr, "_ephemeral_worktree_remove", lambda wt: removed.append(wt))
    monkeypatch.setenv("CLAUDE_BIN", 'claude "unbalanced')  # argv ValueError → 실행 전 실패
    ev.set_state("in_flight_agents", ["infra"])
    ev.set_state("in_flight_started", {"infra": "2026-01-01T00:00:00+00:00"})

    asyncio.run(sr.run_subagent_execution("infra", "d9", "t", "k", ""))

    assert removed == [created]  # ephemeral teardown 됨
    assert "infra" not in (ev.get_state("in_flight_agents") or [])  # lock 해제
