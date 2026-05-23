---
feature: Discord-driven maestro (tmux interactive + Discord bridge)
slug: discord-driven-mobruji
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [338, 349, 827, 830, 939]
last_reviewed: 2026-05-24
---

# Discord-driven maestro (tmux interactive + Discord bridge)

## 1) 개요 (What / Why)
- maestro(Claude Code interactive) 세션을 macOS LaunchAgent + tmux로 **24/7 가동**하고, Discord 채널(#모부르지)을 **stdin/stdout 브리지**로 사용한다.
- 현재 워크플로우(사용자 ↔ maestro ↔ sub-agent(be/fe/rev/plan) 오케스트레이션 + `plugin_discord` MCP 호출)는 그대로 유지. 단지 사용자 입력 경로가 **터미널 키보드 → Discord 메시지**로 바뀐다.
- 사용자 결정(2026-05-22): "지금 하는 형태대로 discord에 옮겨보자" → A안(tmux interactive + Discord bot bridge) 채택. B안(SDK headless 전환), C안(컨테이너화)은 phase 3+로 보류.
- 대상 액터: 운영자(사용자 1인). 목표: 사용자가 노트북 앞에 없어도(외출/이동) Discord로 maestro과 양방향 — 진행 중 작업과 비동기, 다음 호흡에서 자연 interrupt 처리.

### 관련 spec / 코드 (crossref)
- `docs/features/discord-daemon-hosting.md` — 옵션 A(macOS LaunchAgent) 채택 spec. 본 spec은 그 위에 tmux bridge 레이어를 추가한다.
- `docs/features/discord-realtime-bidirectional.md` — 양방향 소통 인프라 옵션 비교. 본 spec은 옵션 4(자체 호스팅 데몬) + 옵션 5(maestro 매 호흡 fetch)의 통합 후속.
- `tools/discord-daemon/bot.py` — 현재 discord.py 기반 데몬. Discord → GitHub `repository_dispatch` 호출만 수행. 본 spec에서 tmux send-keys + stdout capture 분기 추가.
- `tools/discord-daemon/com.mobruji.discord-daemon.plist` — 기존 LaunchAgent template (bot.py만 가동). 본 spec에서 tmux 세션 부트스트랩까지 책임지도록 확장 또는 별도 plist 분리.
- `tools/discord-daemon/setup-launchagent.sh` — 기존 셋업 스크립트. tmux 세션 자동 생성 + claude 실행 단계 추가 필요.
- 메모리: `[feedback-discord-polling]`, `[project-discord-channel]`, `[feedback-autonomous-loop]`.

## 2) 사용자 시나리오
- **S1. 외출 중 한 줄 지시**: 사용자가 iPhone Discord에서 "PR #237 라벨 점검해줘" 입력 → 1초 내 maestro tmux에 send-keys → maestro이 현재 호흡 끝나면 자연 interrupt로 새 입력 처리 → 결과를 maestro이 `plugin_discord__reply`로 Discord에 회신.
- **S2. 진행 중 작업 도중 코스 변경**: maestro이 sub-agent A 결과 정리 중인데 사용자가 "잠깐, 그건 보류하고 B부터" 입력 → tmux 입력 큐에 적재 → maestro이 다음 호흡(도구 호출 사이)에서 자연 처리. 진행 중 작업 강제 중단 없음.
- **S3. 노트북 재부팅 후 자동 복구**: macOS 재부팅 → LaunchAgent가 tmux 세션 + claude 자동 부팅 → Discord 한 줄로 "복구 OK?" 확인.
- **S4. maestro 응답 분리**: maestro이 작업 로그(narration) 출력 중에도 사용자에게 "최종 답"만 골라 Discord에 보내야 함 → maestro이 `===REPLY===` 마커로 감싸거나, 더 간단하게 **maestro은 narration만 stdout에, 사용자 회신은 `plugin_discord__reply` 명시 호출**로 분리(권장).

## 3) 요구사항
### 기능 요구사항
- [ ] F1. Discord 채널(#모부르지) 메시지 수신 → maestro tmux session "mobruji"에 stdin 주입. latency P95 < 2s.
- [ ] F2. maestro stdout(claude TUI 응답/narration)을 캡처. 단, **사용자 회신은 maestro이 `plugin_discord__reply` MCP 호출**로 명시적으로 전송(권장 경로). stdout capture는 보조/디버깅 용도.
- [ ] F3. maestro 진행 중 작업과 사용자 메시지 비동기 처리. interrupt는 claude TUI 자체 동작(다음 호흡에서 새 입력 인식)에 위임. bot은 send-keys + Enter만.
- [ ] F4. send-keys로 보낼 페이로드는 **줄 단위 텍스트 + Enter**. multiline은 `tmux send-keys -l` 또는 paste buffer 활용.
- [ ] F5. sub-agent 통지(maestro → sub-agent 결과 stdin 입력)는 기존 패턴(Claude Code 자체 통지) 그대로. bot은 사용자 → maestro 한 방향만 책임.
- [ ] F6. Discord → tmux 입력 시 **dedup**: 동일 `message_id` 중복 처리 금지(SQLite 또는 파일 ledger). 봇 재시작 후 1시간 이내 중복 차단.
- [ ] F7. Discord 메시지가 `/system:` prefix면 maestro에 슬래시 명령(예: `/compact`, `/clear`)으로 인식되도록 escape. 일반 메시지는 그대로 전달.
- [ ] F8. tmux pane 없이 봇이 부팅됐을 때 자동 생성(`tmux new-session -d -s mobruji 'claude'`).

### 비기능 요구사항
- **24/7 가동**: macOS sleep 방지(`sudo pmset -a disablesleep 1`). LaunchAgent `KeepAlive=true` + `ThrottleInterval=30`. tmux 세션 죽음 자동 재시작(bot이 send-keys 전 세션 존재 여부 체크).
- **부팅 자동 복구**: 재부팅 시 LaunchAgent가 tmux 세션 + claude 자동 부팅. 사용자 개입 0.
- **비용**: 추가 인프라 비용 0 (사용자 Mac). claude 토큰 사용량은 현행 유지.
- **보안**: Discord bot token, Anthropic API key, GitHub PAT는 `.env` (chmod 600). `ALLOWED_USER_IDS` 화이트리스트 유지. 임의 셸 명령 실행 금지(maestro 권한 정책은 기존 settings.json 그대로).
- **관측성**: bot stdout 로그(`~/Library/Logs/mobruji-discord-daemon.out.log`)에 send-keys 이벤트 + dedup hit 기록. tmux pane 별도 로그 파일 옵션(`tmux pipe-pane`).
- **응답 지연**: 사용자 Discord 전송 → maestro stdin 도달 P95 < 2s. maestro 처리 → Discord 회신은 maestro 도구 호출 + Claude 추론 시간에 따라 가변(상한 없음, 본 spec scope 아님).

## 4) 범위 / 비범위
### 포함 (Phase 1)
- `tools/discord-daemon/bot.py` 확장: Discord 메시지 수신 시 `repository_dispatch` 외에 **tmux send-keys 분기** 추가(env flag로 on/off).
- tmux session "mobruji" 자동 부팅 로직 (bot 또는 별도 wrapper 스크립트).
- `com.mobruji.discord-daemon.plist` 또는 신규 plist로 LaunchAgent 등록.
- 셋업 가이드 런북 (기존 `setup-launchagent.sh` 확장 또는 신규 `setup-tmux-bridge.sh`).
- dedup ledger (SQLite 또는 JSONL).

### 제외 (Out of Scope, Phase 2+)
- **컨텍스트 누적 자동 해결** — maestro transcript 무게가 한계 도달 시 사용자가 Discord에서 `/compact` 명시 전송. 자동 트리거는 Phase 2.
- **SDK headless maestro 전환(B안)** — tmux 의존 제거, Anthropic SDK 직접 구동. 컨텍스트 누적/세션 영속화 모두 자체 관리. Phase 3+.
- **컨테이너화(C안)** — Docker/k8s. 사용자 Mac 의존 제거. Phase 3+.
- **stdout → Discord 자동 회신** — 본 spec은 maestro이 명시적 `plugin_discord__reply` 호출하는 패턴 유지. stdout 캡처는 디버깅용. 자동 회신은 Phase 2 마커 룰 도입 시.
- **멀티 사용자/멀티 채널** — 1 maestro = 1 사용자 = 1 채널 가정. 멀티는 별도 spec.
- **maestro 응답 길이 split / attachment** — Discord 2000자 초과 시 처리. Phase 2.

## 5) 설계

### 5-1) 도메인 모델
- 도메인 엔티티 변경 없음. 인프라 계층 (scope: infra).
- `docs/ai-harness/06-domain-model.md` 영향 없음.

### 5-2) 컴포넌트
1. **discord-bot 확장** (`tools/discord-daemon/bot.py`)
   - 기존: Discord `on_message` → `dispatch_to_github` (repository_dispatch).
   - 추가: `on_message` → `tmux_send_keys` (env `TMUX_BRIDGE_ENABLED=1` 시).
   - 두 경로는 **OR 또는 둘 다**. 기본 채택안: **tmux bridge 단독**(repository_dispatch는 maestro idle 시 fallback 용도, Phase 2).
2. **tmux session "mobruji"**
   - LaunchAgent가 직접 띄우거나, bot이 부팅 시 `tmux new-session -d -s mobruji 'claude'` 보장.
   - 세션 죽음 감지: bot이 send-keys 전 `tmux has-session -t mobruji` 체크, 실패 시 재생성.
3. **stdout capture (보조)**
   - `tmux pipe-pane -t mobruji -o 'cat > ~/.mobruji/tmux-pane.log'` 으로 raw 출력 파일화.
   - Phase 1에선 디버깅 용도. maestro → Discord 회신은 maestro 자신이 `plugin_discord__reply` 호출.
   - Phase 2에서 ANSI 필터 + 마커 룰(`===REPLY===` ~ `===END===`) 도입 검토.
4. **macOS LaunchAgent**
   - 기존 `com.mobruji.discord-daemon.plist` 확장(`ProgramArguments`에 tmux session 부트스트랩 스크립트 추가) **또는** 신규 plist (`com.mobruji.claude-tmux.plist`) 분리.
   - 분리안 권장: 책임 분리(bot 죽어도 maestro 살아있음, maestro 죽어도 bot은 메시지 수신 가능 = Discord에 에러 회신 가능).
5. **dedup ledger**
   - `~/.mobruji/discord-bridge.sqlite` 또는 `inbox.jsonl` 재활용.
   - `message_id` PK + `processed_at` TIMESTAMP. 24h TTL 후 GC.

### 5-3) 데이터 흐름 / 시퀀스

```
[사용자 iPhone Discord]
       │
       │ 메시지 전송
       ▼
[Discord Gateway WebSocket]
       │
       │ push
       ▼
[bot.py on_message]
       │ 1. allowed_user_ids 검증
       │ 2. dedup ledger 체크 (message_id 신규?)
       │ 3. tmux has-session -t mobruji ? (없으면 생성)
       │ 4. tmux send-keys -t mobruji "<text>" Enter
       │ 5. ledger insert (message_id, now)
       ▼
[tmux session "mobruji" — claude TUI 실행 중]
       │
       │ stdin 입력 수신 (현재 호흡 끝나면 처리)
       ▼
[maestro Claude — 처리]
       │
       │ 응답 생성 → plugin_discord__reply 호출
       ▼
[Discord 채널 #모부르지]
       │
       ▼
[사용자 iPhone — 회신 수신]
```

### 5-4) 환경변수 추가 (`.env`)
```
TMUX_BRIDGE_ENABLED=1
TMUX_SESSION_NAME=mobruji
TMUX_TARGET_PANE=mobruji:0.0
CLAUDE_BIN=/usr/local/bin/claude   # 또는 ~/.claude/local/claude
DEDUP_LEDGER_PATH=~/.mobruji/discord-bridge.sqlite
```
기존 `DISCORD_BOT_TOKEN`, `ALLOWED_USER_IDS`, `MOBRUJI_CHANNEL_ID`, `GITHUB_PAT`, `GITHUB_REPO`는 유지(GitHub 경로 fallback 보존).

### 5-5) DB 마이그레이션
- 해당 없음 (앱 DB 영향 없음). dedup ledger는 별 SQLite 파일(`~/.mobruji/discord-bridge.sqlite`).

### 5-6) 프론트엔드 화면
- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] **PR A (본 PR)**: spec 작성 (#TBD)
- [x] **PR B**: `bot.py` 확장 — `TMUX_BRIDGE_ENABLED` flag + `tmux_send_keys` 함수 + dedup ledger. 단위 테스트(tmux mock).
- [x] **PR C**: tmux pane stdout capture (`tmux pipe-pane`) 로깅 + 로그 로테이션. Phase 2 마커 룰 준비.
- [ ] **PR D**: LaunchAgent plist 확장(또는 신규 `com.mobruji.claude-tmux.plist`) + `setup-tmux-bridge.sh` 런북. 재부팅 자동 복구 검증 절차 포함.
- [ ] **PR E (선택, Phase 2)**: 응답 마커 룰 + ANSI 필터링 → bot이 stdout 캡처 → Discord 자동 회신. maestro 행동 변경(`CLAUDE.md`에 마커 룰 명시) 동반.
- [ ] **PR F (선택, Phase 2)**: maestro transcript 무게 모니터 → 임계치 초과 시 자동 `/compact` send-keys.

## 7) Phase 분할
- **Phase 1 (이번 spec, PR B~D)**: 기본 bridge — Discord → tmux send-keys, maestro은 명시적 `plugin_discord__reply`로 회신. 현행 워크플로우 무변경, 입력 경로만 추가.
- **Phase 2 (PR E~F)**: 자동 회신 (stdout 마커) + 자동 `/compact`. maestro 행동 룰 일부 변경.
- **Phase 3+**: SDK headless 전환(B안), 컨테이너화(C안). tmux/Mac 의존 제거. 큰 작업, 별 spec.

## 8) 보안
- `.env` chmod 600 + .gitignore (기존 정책 유지).
- `ALLOWED_USER_IDS` 화이트리스트로 Discord 발신자 검증 (기존).
- send-keys 페이로드 sanitize: shell escape 불필요(tmux send-keys는 shell 통하지 않음), 단 **maestro 슬래시 명령 prefix(`/`)는 사용자가 명시할 때만 허용**. 일반 메시지는 그대로 전달(maestro Claude가 해석).
- 임의 셸 명령 실행 금지(maestro 권한은 기존 settings.json 정책 그대로).
- 로그: 메시지 내용은 truncate 후 기록(기존 `truncate_for_log` 재사용).

## 9) 트레이드오프 / 알려진 한계
- **컨텍스트 누적 그대로** — maestro transcript 무게는 본 spec scope 밖. Phase 2에서 자동 `/compact` 검토.
- **stdout 노이즈** — claude TUI는 ANSI 색상/스피너/진행 indicator 출력. Phase 1에선 stdout 캡처를 디버깅용으로만 사용해 회피. 자동 회신은 Phase 2 마커 룰로 해결.
- **동시 대화 1:1** — 한 maestro = 한 사용자 = 한 채널. 멀티는 별 spec.
- **maestro 죽음 감지 지연** — LaunchAgent `KeepAlive`는 bot 프로세스만 모니터. tmux pane 안 claude가 죽으면 bot은 send-keys 성공해도 응답 없음. 해결: bot이 send-keys 후 일정 시간 응답 없으면 Discord에 "maestro 응답 없음" 회신 + tmux pane 상태 체크(Phase 2).
- **interactive 키 입력(Ctrl+C, /exit)** — 사용자가 Discord에 `/system:ctrl-c` 같은 sentinel 보내면 bot이 `tmux send-keys C-c`로 변환. Phase 1에선 `/exit` 등 명시적 sentinel만 지원, 일반 텍스트는 그대로.
- **send-keys 타이밍** — maestro이 현재 호흡 중일 때 send-keys하면 키가 prompt에 들어가지 않고 sub-agent 출력 사이에 끼어들 수 있음. claude TUI의 input buffer 동작에 의존(현재 관찰상 다음 prompt 대기 시 자연 인식). 문제 발생 시 Phase 2에서 sentinel 기반 큐잉 검토.

## 10) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | stdout 마커 룰을 maestro 행동 룰로 강제할지 (Phase 2) | (a) `CLAUDE.md`에 명시 / (b) 시스템 프롬프트 인젝션 / (c) Phase 2까지 결정 보류 | @goohong / Phase 2 진입 시 |
| Q2 | Discord 회신을 thread reply vs 새 메시지 | (a) thread reply (대화 컨텍스트 유지) / (b) 새 메시지 (알림 강제) / (c) maestro 재량 | @goohong / PR B 전 |
| Q3 | maestro 응답 길이 > 2000자 처리 | (a) split / (b) attachment / (c) maestro이 요약만 보내고 상세는 gist | @goohong / Phase 2 |
| Q4 | LaunchAgent plist 분리 여부 | (a) 기존 plist 확장 (bot + tmux 한 묶음) / (b) 신규 plist 분리 (책임 분리) | @goohong / PR D 전 |
| Q5 | maestro `/exit` 처리 — KeepAlive와 충돌 | (a) `/exit` 차단 / (b) `/exit` 후 LaunchAgent 자동 재시작 허용 / (c) Discord 명시 sentinel만 종료 트리거 | @goohong / PR B 전 |
| Q6 | dedup ledger 백엔드 | (a) SQLite / (b) JSONL append (기존 inbox 재사용) | @goohong / PR B 전 |

## 11) 결정 로그
- 2026-05-22: 초안 작성 (status=draft). 사용자 결정 "지금 하는 형태대로 discord에 옮겨보자" 반영. A안(tmux interactive + Discord bridge) 채택. B안(SDK headless), C안(컨테이너) Phase 3+ 보류. Phase 1 범위 = bot.py tmux bridge + LaunchAgent + 런북. maestro stdout 자동 회신은 Phase 2로 분리(maestro이 명시적 `plugin_discord__reply` 호출 유지).
