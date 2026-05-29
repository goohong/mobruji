"""mobruji-agent — Claude Agent SDK orchestration entry point.

systemd ExecStart 로 호출됨 (mobruji-agent.service).

Phase 1.1 — skeleton. agent loop 실제 구현은 Phase 1.4.

architecture spec:
- docs/architecture/2026-05-29-legacy-cli-tmux-snapshot.md (마이그레이션 직전 시스템)
- docs/architecture/2026-05-29-legacy-ncp-inventory.md (운영 본체 inventory)
- (작성 중) docs/architecture/2026-05-29-claude-sdk-agent-design.md (새 시스템 설계)

결정 누적 (2026-05-29):
- IPC = event log + state table (SQLite)
- State machine = 계층 hierarchical (GlobalState dataclass)
- Subagent handoff = SDK subagent primitive
- Tools = 12개 최소 (옵션 A)
- Scope = 정식 마이그레이션 (Phase 단계)
- 위치 = tools/agent/
- 내부 구조 = flat + tools_*.py 분리
- systemd = 단순 simple + Restart=always (Watchdog 없음, 필요 시 추가)
"""

from __future__ import annotations

import logging
import sys

logger = logging.getLogger("mobruji-agent")


def main() -> int:
    """entry point. Phase 1.1 skeleton — actual loop 은 1.4 에서 구현."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s :: %(message)s",
    )
    logger.info("mobruji-agent skeleton 부팅 (Phase 1.1 — agent loop 미구현)")
    logger.info("Phase 1.2 ~ 1.4 후 actual agent 작동.")
    # Phase 1.1 = skeleton 만. Phase 1.4 에서 actual loop 구현.
    return 0


if __name__ == "__main__":
    sys.exit(main())
