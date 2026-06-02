"""mobruji-agent — Claude Agent SDK orchestration entry point.

systemd ExecStart 로 호출됨 (mobruji-agent.service).

architecture spec:
- docs/architecture/2026-05-29-legacy-cli-tmux-snapshot.md
- docs/architecture/2026-05-29-legacy-ncp-inventory.md
- tools/agent/README.md

Phase 1.4 — agent loop actual 구현 (event polling + tool dispatch).
SDK query() 통합은 Phase 2 (NCP 배포 직전).
"""

from __future__ import annotations

import asyncio
import logging
import sys


logger = logging.getLogger("mobruji-agent")


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s :: %(message)s",
    )
    logger.info("mobruji-agent 시작 (Phase 1.4)")
    import agent
    return asyncio.run(agent.run())


if __name__ == "__main__":
    sys.exit(main())
