"""agent.py loop integration tests — event polling + handler dispatch."""

from __future__ import annotations

import asyncio

import pytest


@pytest.mark.asyncio
async def test_agent_loop_processes_user_pause(isolated_db):
    import agent, events

    events.append_event("user_pause", {"reason": "test"})

    stop = asyncio.Event()
    task = asyncio.create_task(agent.agent_loop(stop))
    await asyncio.sleep(1.5)  # 1 polling cycle 보장
    stop.set()
    await task

    assert events.get_state("paused") is True
    assert events.read_unconsumed_events("agent") == []


@pytest.mark.asyncio
async def test_agent_loop_processes_user_resume(isolated_db):
    import agent, events

    events.set_state("paused", True)
    events.append_event("user_resume", {})

    stop = asyncio.Event()
    task = asyncio.create_task(agent.agent_loop(stop))
    await asyncio.sleep(1.5)
    stop.set()
    await task

    assert events.get_state("paused") is False


@pytest.mark.asyncio
async def test_agent_loop_skips_bot_events(isolated_db):
    """bot.py 책임 event (agent_reply / agent_forum_action) 는 mark X."""
    import agent, events

    eid = events.append_event("agent_reply", {"body": "x"})

    stop = asyncio.Event()
    task = asyncio.create_task(agent.agent_loop(stop))
    await asyncio.sleep(1.5)
    stop.set()
    await task

    # mark X — 여전히 unconsumed 보임
    unconsumed = events.read_unconsumed_events("bot")
    assert any(e["id"] == eid for e in unconsumed)


@pytest.mark.asyncio
async def test_agent_loop_handler_exception_isolated(isolated_db):
    """handler 실패가 loop 차단 X — drop + mark consumed."""
    import agent, events

    # pr_merged with bad directive_id → ValueError. drop 됨.
    events.append_event("pr_merged", {"directive_id": "bad", "pr_url": "x"})
    # 정상 event 도 함께 — 처리되는지 확인
    events.append_event("user_pause", {"reason": "after error"})

    stop = asyncio.Event()
    task = asyncio.create_task(agent.agent_loop(stop))
    await asyncio.sleep(1.5)
    stop.set()
    await task

    assert events.get_state("paused") is True
    assert events.read_unconsumed_events("agent") == []
