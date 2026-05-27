---
id: 0017
title: Discord Forum 기반 이슈 중심 운영 및 Task Queue 아키텍처 전환
status: proposed
date: 2026-05-27
deciders: [@goohong, Gemini CLI]
---

# 0017. Discord Forum 기반 이슈 중심 운영 및 Task Queue 아키텍처 전환

## Context
현재의 Discord 기반 운영 체계는 단일 채널(#모부르지)에 모든 대화와 작업 알림이 섞여 있어 다음과 같은 한계가 발생하고 있습니다.
1. **컨텍스트 오염**: 여러 작업(예: BE 기능 개발, 인프라 점검)이 동시에 논의될 때 에이전트의 기억(Context)이 섞여 정확도가 떨어짐.
2. **제어권 충돌**: `tmux send-keys` 기반의 직접 입력 방식은 에이전트가 긴 출력을 내뿜는 중 사용자의 명령이 박힐 경우 명령이 무시되거나 출력이 꼬이는 현상이 발생함.
3. **가시성 부족**: 수많은 알림 메시지 사이에서 실제 진행 중인 작업의 단계를 파악하기 어려움.

## Decision
Discord의 **Forum** 기능을 활용하여 모든 작업을 **독립적인 쓰레드(Thread)** 단위로 격리하고, 통신 방식을 `send-keys`에서 **SQLite 기반 Task Queue**로 전환합니다.

1. **이슈 중심 격리 (Forum-Driven)**: 사용자의 새로운 요청은 전용 Forum 채널에서 새 쓰레드를 생성하여 처리하며, 각 쓰레드는 독립적인 작업 환경(tmux pane/worktree)을 할당받음.
2. **비동기 작업 큐 (Task-Queue)**: `bot.py`는 사용자 명령을 수신하면 SQLite DB에 작업을 등록(Enqueue)하고, 에이전트(Worker)는 순차적으로 이를 꺼내어 처리(Dequeue).
3. **에이전트 역할 분리**:
    - **Dispatcher (Bot)**: 이슈 분류, 쓰레드 관리, 작업 큐잉 담당.
    - **Lobby Helper**: 메인 채널 응대 및 작업 현황 대시보드 관리.
    - **Issue Worker (Nmae)**: 할당된 쓰레드의 작업을 전담하여 수행하고 결과 회신.

## Consequences
### 긍정적
- **무결성**: `send-keys` 타이밍 이슈가 사라져 작업 유실률 0% 달성.
- **정확도**: 작업 단위로 컨텍스트가 격리되어 LLM의 망각 및 실수 획기적 감소.
- **멀티태스킹**: 이론적으로 여러 작업을 서로 다른 쓰레드에서 병렬로 진행 가능.

### 부정적
- **리소스 증가**: 여러 쓰레드를 동시 처리할 경우 서버(NCP VM)의 CPU/Memory 부하 증가 (c2-g3a 사양 고려 필요).
- **복잡도**: 단순 브리지에서 관리형 시스템으로 전환됨에 따라 초기 구현 및 유지보수 비용 발생.

## Alternatives (considered)
- **SDK 기반 Headless 전환**: tmux를 완전히 걷어내고 Anthropic SDK로 직접 구동하는 방식. 하지만 기존의 익숙한 CLI 도구들과의 호환성 및 실시간 개입 가능성을 위해 tmux 유지 결정.

## References
- `docs/features/discord-driven-mobruji.md` (Phase 1 legacy)
- `docs/features/discord-forum-ops-v2.md` (예정)
