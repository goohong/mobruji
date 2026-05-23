---
feature: Discord 데몬 호스팅 (무료 24/7 옵션)
slug: discord-daemon-hosting
status: draft
owner: @goohong
scope: infra
related_issues: [201]
related_prs: [202]
last_reviewed: 2026-05-23
---

# Discord 데몬 호스팅 (무료 24/7 옵션)

## 1) 개요 (What / Why)
- `discord-realtime-bidirectional.md` 의 **옵션 4 (자체 호스팅 데몬)** 후속. 실제로 어디에 띄울지 결정한다.
- 1차 후보였던 **Oracle Cloud Free Tier** 는 신용카드 인증이 통과되지 않아 가입 불가 (사용자 카드 적합 카드 없음). **카드 없이/현 자원으로 가능한 대체 옵션**을 정리한다.
- 대상 액터: 운영자 (사용자 1인). 목표: iPhone/외부에서 Discord 한 줄로 mobruji 세션·작업 트리거 가능하게 한다.

## 2) 사용자 시나리오
- 외출 중 iPhone Discord에 `do PR 라벨 점검` 입력 → 데몬이 Codespaces 또는 로컬 Claude Code 세션 띄워 작업.
- 데스크탑/노트북이 잠시 꺼진 동안에도 슬래시 명령 `/mobruji status` 는 응답해야 한다 (옵션 C).
- 운영자가 PR 알림 받았을 때 `fix lint` 라고 답글만 보내면 봇이 해당 PR 브랜치로 작업 디스패치.

## 3) 요구사항
### 기능 요구사항
- [ ] 무료(또는 사용자가 이미 결제 중인 자원)로 24/7 또는 24/7에 가까운 가용성 확보
- [ ] Discord 봇 토큰을 안전하게 보관 (.env, OS 키체인, Workers secret 중 택1)
- [ ] ALLOWED_USER_IDS 화이트리스트로 발신자 제한
- [ ] mobruji repo 로 `repository_dispatch` 또는 로컬 프로세스 트리거 가능
- [ ] 옵션 채택 후 maestro 세션이 그대로 따라 칠 수 있는 셋업 절차 (커맨드 단위) 제공

### 비기능 요구사항
- 비용: 0원 우선, 차선은 GitHub 구독에 이미 포함된 자원
- 보안: 봇 토큰/PAT 평문 커밋 금지, repo secret 또는 로컬 .env만 사용
- 관측성: 데몬 시작/종료/명령 수신 stdout 로그 (민감정보 마스킹)

## 4) 범위 / 비범위
### 포함
- 무료/저비용 호스팅 옵션 비교표
- 각 옵션의 셋업 절차 개요 (실제 스크립트는 maestro PR이 작성)
- 채택 권장안 + maestro이 이어받을 후속 PR 명세

### 제외 (Out of Scope)
- Oracle 가입 재시도 (카드 인증 실패로 차단됨)
- 실제 봇 코드 구현 (`discord-realtime-bidirectional.md` 및 후속 PR 범위)
- 다인 운영을 위한 권한 관리 (운영자 1인 가정)

## 5) 설계
### 5-1) 도메인 모델
- 도메인 엔티티 변경 없음. 인프라 계층.
- 관련 컨텍스트: `infra` scope. `docs/ai-harness/06-domain-model.md` 영향 없음.

### 5-2) 옵션 비교

| 옵션 | 비용 | 신용카드 | Gateway(평문 메시지) | 24/7 | 셋업 난이도 |
|---|---|---|---|---|---|
| A. 사용자 Mac (LaunchAgent) | 0 | 무관 | O | △ (켜진 동안) | 낮음 |
| B. Termux on Android | 0 | 무관 | O | O (배터리 예외) | 중 |
| C. Cloudflare Workers (슬래시만) | 0 | 무관 | X (슬래시/Interactions 한정) | O | 중 |
| D. GCP Free e2-micro | 0 (영구 무료) | 필요 | O | O | 중 |
| E. GitHub Codespaces 60h/월 | 0 | GitHub 결제수단만 | O | X (60h 한계) | 낮음 |
| F. Replit Hacker | $7/월 | 필요 | O | O | 낮음 |
| G. NCP VM (사용자 보유 크레딧) | 0 (크레딧 소진까지) | 불필요 (사용자 기보유) | O | O | 중 (systemd) |

> 결론: **카드 인증 없이 평문 Gateway까지 가능한 건 A, B, E (E는 60h 한계).** C는 카드 없이 24/7 가능하지만 슬래시 명령만 됨. **2026-05-22 추가**: 사용자가 NCP 크레딧 보유 → **G (NCP VM + systemd) 1차 채택** 으로 전환. A (macOS LaunchAgent) 는 사용자 데스크탑 켜진 시간대 fallback 으로 유지. 상세 운영은 `docs/features/maestro-auto-wake.md` + `docs/features/ncp-maestro-resilience.md`.

### 5-3) 각 옵션 셋업 절차 (개요)

#### A. macOS LaunchAgent (권장 #1, 데스크탑 켜진 시간대 한정)
1. `python3 -m venv ~/.mobruji/discord-daemon && source ~/.mobruji/discord-daemon/bin/activate`
2. `pip install discord.py python-dotenv requests`
3. `~/.mobruji/discord-daemon/.env` 에 `DISCORD_BOT_TOKEN`, `ALLOWED_USER_IDS`, `GH_PAT` 저장 (chmod 600)
4. `~/Library/LaunchAgents/com.mobruji.discord-daemon.plist` 작성 — `RunAtLoad=true`, `KeepAlive=true`, stdout/stderr 로그를 `~/Library/Logs/mobruji-discord-daemon.log` 로
5. `launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.mobruji.discord-daemon.plist`
6. 컴퓨터 sleep 방지: `caffeinate -dimsu` 백그라운드 또는 `sudo pmset -a disablesleep 1` (디스플레이만 끔)
7. maestro이 이어받을 산출물: `tools/discord-daemon/setup-launchagent.sh`, `tools/discord-daemon/com.mobruji.discord-daemon.plist.template`, `tools/discord-daemon/bot.py`

#### B. Termux on Android (권장 #2, 사용자가 안드로이드 보유 시)
1. F-Droid 에서 Termux 설치 (Play 스토어 버전은 outdated)
2. `pkg update && pkg install python git`
3. `pip install discord.py python-dotenv requests`
4. Android 설정 → 배터리 최적화에서 Termux 예외
5. `termux-wake-lock` 으로 sleep 방지
6. `nohup python ~/mobruji/bot.py > ~/mobruji/bot.log 2>&1 &`
7. maestro 산출물: `tools/discord-daemon/termux-setup.md`

#### C. Cloudflare Workers (권장 #3, 슬래시 명령 한정, 노트북 끄는 시간대 대비)
1. `npm create cloudflare@latest mobruji-discord-bot -- --type=hello-world`
2. `wrangler secret put DISCORD_PUBLIC_KEY` / `DISCORD_BOT_TOKEN` / `GH_PAT`
3. `worker.js` 에서 `POST /` 시그니처 검증 (Ed25519) + Interactions 응답
4. Discord Developer Portal → Application → General Information → **Interactions Endpoint URL** 에 Worker URL 등록
5. 슬래시 명령 등록 스크립트로 `/mobruji status`, `/do <task>`, `/fix <task>` 정의
6. maestro 산출물: `tools/discord-bot-worker/wrangler.toml`, `tools/discord-bot-worker/src/worker.js`, `tools/discord-bot-worker/scripts/register-commands.ts`

#### D~F (대안, 본 spec 채택 안 함)
- D는 카드 인증 동일 문제, E는 60h/월 제약, F는 유료. 향후 카드 확보 또는 정책 변경 시 재검토.

#### G. NCP VM + systemd (1차 채택, 2026-05-22~)
1. NCP 콘솔에서 micro VM 1대 (Ubuntu 22.04) 생성. 사용자 보유 크레딧 사용.
2. 사용자 ssh 키 등록 후 `ssh ubuntu@<vm-ip>`.
3. `apt install python3-venv git tmux` → `git clone https://github.com/goohong/mobruji.git`.
4. `tools/discord-daemon/setup-gcp-systemd.sh` 실행 (이름이 GCP 잔재지만 NCP 동일 적용 — Linux + systemd 환경 공통).
5. `/etc/systemd/system/mobruji-discord-daemon.service` + `mobruji-discord-bridge.service` 등록 → `systemctl enable --now`.
6. `journalctl -u mobruji-discord-daemon -f` 로 로그 모니터.
7. 운영 런북: `docs/features/ncp-maestro-resilience.md`. wake 자동화: `docs/features/maestro-auto-wake.md`.

### 5-4) 데이터 흐름 / 시퀀스
- Gateway 옵션 (A/B):
  - 사용자 → Discord 채널 메시지 → 데몬 `on_message` → 화이트리스트 검증 → GitHub `repository_dispatch` 호출 → mobruji workflow 트리거 → Discord 채널에 결과 회신
- Interactions 옵션 (C):
  - 사용자 → 슬래시 명령 → Discord → Worker URL → Ed25519 검증 → `repository_dispatch` → Discord에 즉시 ack + followup webhook 으로 후속 결과

### 5-5) DB 마이그레이션
- 해당 없음.

### 5-6) 프론트엔드 화면
- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1 (본 spec): 옵션 비교 및 권장안 문서화 (#202)
- [ ] PR 2: 채택 옵션 셋업 스크립트 — 1차 채택은 **A (macOS LaunchAgent)**, 사용자 데스크탑 ON 시간대 우선 커버
  - `tools/discord-daemon/setup-launchagent.sh`
  - `tools/discord-daemon/com.mobruji.discord-daemon.plist.template`
  - `tools/discord-daemon/bot.py` (discord.py 기반, repository_dispatch 호출)
- [ ] PR 3: 보강 — **C (Cloudflare Workers 슬래시 명령)** 로 데스크탑 OFF 시간대 status/health 응답 커버
  - `tools/discord-bot-worker/`
- [ ] PR 4 (선택): Termux 절차 문서화 (사용자가 안드로이드 단말 결정 시)
- [x] PR 5 (2026-05-22~): 옵션 G (NCP VM + systemd) 셋업 — `setup-gcp-systemd.sh`, `mobruji-discord-daemon.service`, `mobruji-discord-bridge.service`. NCP 크레딧으로 24/7 확보. 운영 런북: `docs/features/ncp-maestro-resilience.md`.

## 7) 권장 단계
1. **2026-05-22 이후 (현행)**: 옵션 G (NCP VM + systemd) 1차. 사용자 NCP 크레딧으로 24/7 확보. PR 5 머지 완료. 옵션 A 는 사용자 데스크탑 켜진 시간대 보조.
2. **보강**: 옵션 C (Cloudflare Workers) 로 슬래시 명령 추가 가능 — 현재 G 가 24/7 커버하므로 우선순위 낮음.
3. **백업**: 사용자가 안드로이드 단말 도입 시 옵션 B 추가.
4. **장기**: NCP 크레딧 소진 시 GCP/Oracle 재검토 → `infra` ADR 갱신.

## 8) 보안
- 봇 토큰: A/B 는 `.env` (chmod 600) + .gitignore, C 는 `wrangler secret`
- GitHub PAT: `repo`, `workflow` 스코프 최소. `repository_dispatch` 전용 토큰 분리 권장
- 발신자 검증: `ALLOWED_USER_IDS` 환경변수에 Discord user id 목록 (정수 비교)
- 명령 화이트리스트: `do`, `fix`, `status`, `branch` 등 정의된 prefix만 허용. 임의 셸 명령 실행 금지
- 로그: 토큰/PAT 마스킹, 사용자 입력은 truncate 후 기록

## 9) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 1차 채택 옵션 | (a) A 단독 / (b) A + C 병행 | @goohong / 2026-05-22 |
| Q2 | `repository_dispatch` 토큰을 별도 PAT 로 분리할지 | (a) 분리 / (b) 기존 PAT 재사용 | @goohong / PR 2 전 |
| Q3 | Mac sleep 정책 | (a) `pmset disablesleep` / (b) `caffeinate` 만 / (c) 절전 허용 + 깨어있을 때만 동작 | @goohong / PR 2 전 |

## 10) 결정 로그
- 2026-05-21: 초안 작성 (status=draft). Oracle Free Tier 가입 불가(카드 인증 실패) 확인 → 카드 없이 가능한 A/B/C/E 위주 비교. 1차 권장 = A (macOS LaunchAgent), 보강 = C (Cloudflare Workers Interactions).
- 2026-05-22 (plan): 사용자 NCP 크레딧 확보 → **옵션 G (NCP VM + systemd)** 신설 + 1차 채택. `setup-gcp-systemd.sh` (이름 GCP 잔재지만 Linux + systemd 환경 공통, NCP 동일 적용) + `mobruji-discord-daemon.service` + `mobruji-discord-bridge.service` 추가. 운영 런북: `docs/features/ncp-maestro-resilience.md`. 옵션 A 는 데스크탑 켜진 시간대 보조로 유지.
- 2026-05-23 (plan, spec drift cleanup pt2): §5-2 표에 옵션 G 행 추가, §5-3 에 옵션 G 셋업 절차 추가, §6 PR 5 (옵션 G shipped) 추가, §7 권장 단계 G 1차로 갱신. spec ↔ 실 코드 (`tools/discord-daemon/setup-gcp-systemd.sh`, `*.service`) 정합화.
