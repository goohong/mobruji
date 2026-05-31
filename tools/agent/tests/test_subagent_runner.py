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
        "---\nname: rev\nmodel: x\n---\n너는 rev sub-agent다. 감사 전담.",
        encoding="utf-8",
    )
    body = sr.role_prompt("rev", tmp_path)
    assert body == "너는 rev sub-agent다. 감사 전담."
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
