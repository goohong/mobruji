# Handover Document: Discord Architecture Overhaul & Remaining Tasks

## 1. 지금까지 완료된 작업 (Phase 1 & 2)

기존의 단일 채널/`send-keys` 기반의 불안정한 Discord 봇 구조를 **"Forum-Driven, Task-Queue Architecture"** 로 완전히 개편했습니다. 

*   **ADR 작성 완료**: `docs/decisions/0017-discord-forum-driven-ops.md` 및 `docs/features/discord-forum-ops-v2.md`
*   **Task Queue 도입 (`bot.py`)**: `DedupLedger`를 `OpLedger`로 확장하여 SQLite 기반의 비동기 작업 큐(`tasks` 테이블)를 구축했습니다. 이제 메시지 수신 시 바로 터미널에 쏘지 않고 큐에 안전하게 저장합니다.
*   **Discord Thread 자동 생성**: `on_message` 핸들러를 수정하여, 사용자가 명령을 내리면 `bot.py`가 자동으로 새로운 쓰레드(Issue)를 생성하고 대화를 격리합니다.
*   **Worker 프로세스 추가**: 큐를 폴링하여 실제 `claude` CLI를 실행할 `worker.py`의 뼈대를 만들었습니다.
*   **안정성 검증**: 이 모든 변경을 반영하여 231개의 단위 테스트를 모두 통과(Pass)시켰습니다.

## 2. Claude가 이어서 해야 할 작업 (남은 과제)

### Task A: Discord 채널 정비 및 적용 (Phase 3)
현재 `bot.py`는 `LOBBY_CHANNEL_ID`, `FORUM_CHANNEL_ID`, `STATUS_CHANNEL_ID` 세 가지 환경 변수를 읽도록 준비되어 있지만, 실제 Discord 서버에는 이 채널들이 존재하지 않습니다.
1.  제가 `tools/discord-daemon/setup_discord_server.py` 스크립트를 작성해 두었습니다. 이 스크립트를 실행하여 봇의 권한으로 **Lobby(일반 텍스트), Ops(포럼), Status(대시보드)** 채널을 생성하세요.
2.  출력된 채널 ID들을 `.env` 파일에 업데이트하세요.
3.  `bot.py`의 로비/포럼 라우팅 로직이 의도대로 동작하는지 E2E로 확인하고, 필요하다면 `worker.py`를 systemd에 등록하여 전체 사이클을 켜주세요.

### Task B: "Claude Usage" 메시지 추적 및 제거
사용자가 디스코드에서 "Claude usage daily 17030% 도달..." 이라는 부정확한 토큰 사용량 알림이 온다고 보고했습니다.
1.  제가 `bot.py`, `discord-reply.sh`, GitHub Workflows 등을 전부 `grep`으로 뒤져보았으나, 해당 문자열(`Claude usage daily`, `17030`, `787.99M`)을 **레포지토리 내부 코드에서 찾지 못했습니다.**
2.  이 알림이 외부 크론잡, 별도의 AWS/NCP 람다, 혹은 `claude` CLI 자체의 플러그인에서 발생하는 것일 수 있습니다. (혹은 다른 브랜치/stash에 코드가 있을 수 있습니다).
3.  어디서 이 웹훅/메시지가 쏘아지는지 추적하여 완전히 비활성화(제거)해주세요.

## 3. 핵심 전달 사항
*   현재 코드는 모두 정상적으로 스테이징되어 있거나 커밋(`refactor(infra): messenger-first architecture...`)된 상태입니다.
*   "한 구멍 막으면 다른 구멍 터지는" 주먹구구식 보수를 끝내기 위해 아키텍처를 갈아엎은 상태이니, 기존의 `send-keys` 방식으로 롤백하지 말고 큐(Queue)와 쓰레드(Thread) 기반의 새 아키텍처를 완성해 주세요.