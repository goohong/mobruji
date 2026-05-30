"""_format_tool_progress 분기 — #1356 기본 Claude tool case 추가."""

from __future__ import annotations


def test_post_discord_message_returns_none():
    from agent import _format_tool_progress

    assert _format_tool_progress("post_discord_message", {"body": "x"}) is None


def test_bash_includes_command_first_line():
    from agent import _format_tool_progress

    result = _format_tool_progress("Bash", {"command": "./gradlew test\nignored"})
    assert result == "💬 Bash: ./gradlew test"


def test_edit_uses_basename():
    from agent import _format_tool_progress

    result = _format_tool_progress("Edit", {"file_path": "/abs/path/to/agent.py"})
    assert result == "✏️ Edit: agent.py"


def test_write_uses_basename():
    from agent import _format_tool_progress

    assert _format_tool_progress("Write", {"file_path": "x.md"}) == "✏️ Write: x.md"


def test_noisy_read_returns_none():
    from agent import _format_tool_progress

    assert _format_tool_progress("Read", {"file_path": "x"}) is None
    assert _format_tool_progress("Glob", {"pattern": "*.py"}) is None
    assert _format_tool_progress("Grep", {"pattern": "x"}) is None


def test_webfetch_includes_url():
    from agent import _format_tool_progress

    result = _format_tool_progress("WebFetch", {"url": "https://example.com/x"})
    assert result == "🌐 WebFetch: https://example.com/x"


def test_websearch_includes_query():
    from agent import _format_tool_progress

    result = _format_tool_progress("WebSearch", {"query": "claude code"})
    assert result == "🌐 WebSearch: claude code"


def test_agent_includes_description():
    from agent import _format_tool_progress

    result = _format_tool_progress("Agent", {"description": "find files"})
    assert result == "🤖 Agent: find files"


def test_toolsearch_includes_query():
    from agent import _format_tool_progress

    result = _format_tool_progress("ToolSearch", {"query": "select:Read"})
    assert result == "🔍 ToolSearch: select:Read"


def test_unknown_builtin_fallback():
    from agent import _format_tool_progress

    assert _format_tool_progress("SomeNewTool", {}) == "🛠️ SomeNewTool"


def test_mcp_nmae_launch_subagent_unchanged():
    from agent import _format_tool_progress

    result = _format_tool_progress(
        "mcp__nmae__launch_subagent", {"cycle": "fe", "title": "fix"},
    )
    assert "launch_subagent" in result
    assert "cycle=fe" in result


def test_bash_command_truncates_to_100_chars():
    from agent import _format_tool_progress

    long_cmd = "x" * 200
    result = _format_tool_progress("Bash", {"command": long_cmd})
    assert result == "💬 Bash: " + "x" * 100
