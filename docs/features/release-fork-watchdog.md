---
feature: release fork watchdog (scheduled GHA + 임계치 Discord push)
slug: release-fork-watchdog
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [1162]
last_reviewed: 2026-05-27
---

# release fork watchdog (scheduled GHA + 임계치 Discord push)

## 1) 개요 (What / Why)
- `main` ↔ `develop` 사이 누적 fork (한쪽에만 있는 commit 수) 를 매일 자동 측정하고, 임계치 초과 시 Discord 로 가시화하는 scheduled GitHub Actions workflow.
- 사고 박제 (release #1122, 2026-05-26): `develop-only 240 commit / main-only 162 commit / merge-base 빈 응답` 까지 누구도 모르는 채 누적 → release PR 머지 직전에야 발견. 다음 release 사이클에서 같은 silent fork 재발을 차단하기 위해 daily monitor 가 필요.
- 운영 규약 `docs/ai-harness/02-agent-workflow.md §8-7-3` (PR #1162 신설 — "develop-only / main-only commit 누적 monitor" 임계치) 의 *operational automation* 짝. 룰 명문화만으로는 인적 polling 책임이 분산되어 누락 risk 존속 → 외부 cron 으로 강제.
- 대상 액터: nmae (사이클 사이 release cadence 판단), 사용자 (P0 escalation 수신), helper (사용자 응답 시 fork 상태 cross-ref).

## 2) 사용자 시나리오
- **케이스 A — 동기 상태**: 일일 09:00 KST trigger 시 `main-only=0 / develop-only<50` → workflow 성공 + 어떤 push 도 발생하지 않음 (silent OK, 노이즈 0).
- **케이스 B — develop 누적 warning**: develop-only commit 이 50 건 도달 → `#모부르지-알림` (DIGEST 채널) 에 warning push: "release cadence 도달 — develop-only=50, 주 1회 release 권고". nmae 다음 사이클에서 release PR 트리거 판단.
- **케이스 C — main-only 1+ (back-merge 누락)**: main-only=1 발견 → warning push + (선택) GitHub Issue 자동 생성. release PR 머지 후 24h sync 의무 (`§8-7-2`) 위반 → back-merge PR 즉시 launch.
- **케이스 D — P0 fork**: main-only=10 이상 또는 develop-only=240 같은 사고 박제 수치 도달 → `#모부르지` 사용자 채널 직접 push (escalation). 사용자 결정 (cherry-pick / force reset / 무시) 까지 사이클 일시정지 권고.
- **케이스 E — 수동 trigger**: 사용자 / nmae 가 `gh workflow run release-fork-watchdog.yml` 으로 즉시 점검 (release PR 트리거 직전 sanity check).

## 3) 요구사항
### 기능 요구사항
- [ ] `.github/workflows/release-fork-watchdog.yml` 신설
  - cron: `0 0 * * *` (UTC 00:00 = KST 09:00) — daily 1회 default
  - `workflow_dispatch` (manual trigger) 지원, optional input `threshold_override` (P0 임계치 임시 override)
  - permissions: `contents: read` (write 불요)
- [ ] `tools/release-sync/check-fork.sh` 신설 (bash, GHA + 로컬 둘 다 실행 가능)
  - 3 metric 측정 (`§8-7-2` 3 git 명령 박제):
    - `MAIN_ONLY = $(git rev-list --count origin/develop..origin/main)`
    - `DEVELOP_ONLY = $(git rev-list --count origin/main..origin/develop)`
    - `MERGE_BASE = $(git merge-base origin/main origin/develop)`
  - 환경 변수 임계치:
    - `WARN_MAIN_ONLY` (default `1`)
    - `WARN_DEVELOP_ONLY` (default `50`)
    - `P0_MAIN_ONLY` (default `10`)
    - `P0_DEVELOP_ONLY` (default `240` — release #1122 사고 수치)
  - exit code: 0 = OK, 1 = warning, 2 = P0 (workflow level 에서 다음 단계 분기)
  - stdout: 한 줄 JSON `{"main_only": N, "develop_only": M, "merge_base": "sha", "level": "ok|warn|p0", "reasons": [...]}` — workflow step 이 jq 로 파싱
- [ ] 임계치 위반 시 Discord push 분기
  - **warning** (`MAIN_ONLY>=1` OR `DEVELOP_ONLY>=50`): `DIGEST_CHANNEL_ID` (= `NOTIFY_CHANNEL_ID`, #모부르지-알림 또는 #모부르지-digest) 에 push
  - **P0** (`MAIN_ONLY>=10` OR `DEVELOP_ONLY>=240`): `MOBRUJI_CHANNEL_ID` (#모부르지) 직접 push + warning push 동반 (사용자 가시 + cron 기록)
  - push 본문 템플릿: `level | main_only=N | develop_only=M | merge_base=<sha-7> | reasons: ...` + `§8-7-3` link
- [ ] Discord webhook secret 부재 시 graceful skip (workflow 성공 유지, `discord-periodic-summary.yml` 패턴 동일)
- [ ] `merge_base` 가 빈 응답일 때 즉시 P0 분류 (release #1122 사고 패턴 박제)
- [ ] (선택) issue 자동 생성: P0 도달 시 `gh issue create --title "release-fork P0 — main_only=N" --label "scope:infra,type:fix,session:plan"` — 사용자 결정 wait 채널. v1 에서는 *선택* (env `OPEN_ISSUE_ON_P0=0` default off, false positive 조사 후 활성)

### 비기능 요구사항
- 알림 spam 방지: 같은 metric 24h 안 동일 level 반복 시 silent (debounce). 임계치 cross 시점 만 push.
  - 구현: 직전 run 결과를 `actions/cache` 또는 ephemeral artifact 로 저장 → 비교. 부재 시 첫 push.
- Discord rate limit 준수: 1 회 호출 / level — multi-line 단일 push.
- graceful degradation:
  - webhook secret 부재 → skip + log "DISCORD_WEBHOOK_URL 없음".
  - `git fetch` 실패 → workflow fail (P0 와 구분, infra 이슈 별도 처리).
  - `check-fork.sh` 부재 / 권한 fail → workflow fail.
- 관측성: GHA run summary 에 metric 표 + level + reasons 출력 (action log 만으로 root cause 추적 가능).
- 보안: workflow `contents: read` 만, secret 노출 echo 금지. webhook URL masking 자동.

## 4) 범위 / 비범위
### 포함
- GitHub Actions scheduled workflow 1 개 신설
- `tools/release-sync/check-fork.sh` 1 개 신설 (count + 임계치 + JSON 출력)
- Discord push 2 단계 분기 (warning / P0)
- workflow_dispatch manual trigger + threshold override input
- daily cron (UTC 00:00 / KST 09:00) — 변경 시 본 spec §10 결정 로그 추가

### 제외 (Out of Scope)
- **자동 back-merge / cherry-pick 실행 X** — 본 watchdog 은 monitor 전용. fork 해소는 ADR-0023 sub-옵션 (A1/A2/A3) 절차 + nmae 사이클 판단 + 사용자 결정에 위임.
- **branch protection 설정 변경 X** — `02-agent-workflow §8-9-1` 별도 인프라 PR.
- **real-time event 기반 (PR 머지 hook) X** — daily cron 으로 충분 (release cadence 주 1회 default 와 정합).
- **`docs/ai-harness/02-agent-workflow.md §8-7-3` 본문 추가 변경 X** — PR #1162 머지본 본문 임계치 (1 / 50 / 10) 를 SoT 로 사용. 향후 조정 시 본 spec + workflow yml + check-fork.sh 의 default 동시 갱신.
- **multi-repo / submodule 지원 X** — `mobruji/mobruji` 단일 repo 가정.
- **GitHub Issue 자동 close X** — P0 issue 는 사용자 / nmae 가 수동 close (조치 완료 확인 책임 분리).

## 5) 설계
### 5-1) 도메인 모델
- 도메인 변화 없음. `infra` scope 의 운영 automation.

### 5-2) API 엔드포인트
- 해당 없음.

### 5-3) 외부 연동
- **Discord webhook** — `DISCORD_WEBHOOK_URL` (digest 채널 webhook). 운영 secret 으로 GHA repository secret 등록 의무. 부재 시 graceful skip.
- **MOBRUJI_CHANNEL_ID push** — bot.py 운영 REST API 가 아닌 webhook URL 별도 (`DISCORD_MOBRUJI_WEBHOOK_URL`) 필요. 부재 시 P0 도 digest webhook 으로 fallback (degradation 명시).
- **GitHub API (gh CLI)** — `gh issue create` 호출 시 `GH_TOKEN: ${{ github.token }}`.

### 5-4) 데이터 흐름 / 시퀀스
```
GHA scheduled trigger (cron daily 09:00 KST)
  ↓
checkout (fetch-depth: 0 — 전체 히스토리 필요)
  ↓
git fetch origin main develop
  ↓
tools/release-sync/check-fork.sh
  ├─ git rev-list --count origin/develop..origin/main   → MAIN_ONLY
  ├─ git rev-list --count origin/main..origin/develop   → DEVELOP_ONLY
  ├─ git merge-base origin/main origin/develop          → MERGE_BASE (빈 응답 → P0)
  ├─ env 임계치와 비교 → level (ok|warn|p0)
  └─ stdout JSON 출력 + exit code
  ↓
restore previous run artifact (actions/cache)
  ↓
diff vs previous → 동일 level 24h 안 → skip push (debounce)
  ↓
level=ok  → silent 종료 (artifact 갱신만)
level=warn → DIGEST_CHANNEL_ID push
level=p0   → MOBRUJI_CHANNEL_ID push + DIGEST_CHANNEL_ID push (둘 다) + (선택) gh issue create
  ↓
artifact save (다음 run 비교용)
  ↓
GHA summary 출력 (metric 표 + level + reasons)
```

### 5-5) DB 마이그레이션
- 해당 없음.

### 5-6) 프론트엔드 화면
- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)
- [x] PR 1 (본 PR / plan): `docs/features/release-fork-watchdog.md` spec
- [ ] PR 2 (be / infra impl): `.github/workflows/release-fork-watchdog.yml` + `tools/release-sync/check-fork.sh`
  - 라벨: `type:feat scope:infra ai-generated ai:claude session:backend needs-human-review` (`.github/workflows/**` 보호 영역)
  - 검증: workflow_dispatch 로 즉시 trigger → 현재 fork 상태 측정 + Discord 임계치 동작 확인
- [ ] PR 3 (선택, 향후): false positive 조정 + issue 자동 생성 활성화 (`OPEN_ISSUE_ON_P0=1`)

## 7) 테스트 전략
- **unit (check-fork.sh)**:
  - 임시 git repo + tag 시뮬레이션 (테스트용 `tools/release-sync/test/check-fork.bats` 또는 shell test) — main-only/develop-only 의도된 commit 수 만들고 임계치 cross 검증.
  - 임계치 env override 동작 검증.
  - merge-base 빈 응답 시 P0 분류 검증.
- **workflow integration**:
  - `act` 또는 GHA branch test 로 workflow_dispatch trigger → step 별 exit code 확인.
  - webhook secret 부재 시 graceful skip 확인 (workflow exit 0).
- **Discord push smoke**:
  - 테스트 webhook URL 로 warning / P0 본문 가시 확인.
- **debounce**:
  - 두 번 연속 trigger 시 두 번째는 silent — artifact diff 동작 확인.

## 8) 오픈 질문
> 이번 spec 안에서 자율 결정 (사용자 wait state 금지). 필요 시 §10 결정 로그로 이동.

| # | 질문 | 자율 결정 | 근거 |
|---|---|---|---|
| Q1 | 임계치 변경 시 어디서 관리? | **GHA secret + env override** (`WARN_MAIN_ONLY` 등) | 운영 유연성 — hardcoded 변경 시 PR 사이클 필요. secret 변경은 즉시 반영. |
| Q2 | 알림 channel 선택 | **warning=DIGEST / P0=MOBRUJI escalate** | `nmae status channel` 룰 ([[feedback-nmae-status-channel]]) — 일상 시그널은 DIGEST, 사용자 결정 필요 항목만 MOBRUJI. |
| Q3 | cron 주기 | **daily 09:00 KST** | release cadence 주 1회 default 와 동기 — 사이클 시작 직전 sanity. 6h 단위는 노이즈 과다. |
| Q4 | issue 자동 생성 활성화 | **v1 off (env toggle 둠)** | false positive 조정 후 PR 3 에서 활성화. silent on/off 가능 구조 유지. |
| Q5 | debounce 구현 방식 | **actions/cache 직전 run 결과 비교** | external state 없이 GHA native. cache miss 시 conservative push (안전 측). |
| Q6 | P0 임계치 develop-only 값 | **240 (release #1122 사고 수치)** | 박제 수치 그대로 사용 — 다음 사이클에서 조정 결정 시 §10 갱신. |

## 9) 결정 로그
> 연대기 순.

- 2026-05-27: 초안 작성 (status=draft). PR #1162 §8-7-3 신설 직후 후속 spec. 임계치 default = (warning) MAIN_ONLY≥1, DEVELOP_ONLY≥50 / (P0) MAIN_ONLY≥10, DEVELOP_ONLY≥240. cron = daily UTC 00:00 (KST 09:00). 채널 = DIGEST (warning) + MOBRUJI (P0 escalate). issue 자동 생성 = v1 off (env toggle 보유).

## 10) 의존성 / 위험 / 롤백
### 의존성
- PR #1162 (open) `docs/ai-harness/02-agent-workflow §8-7-3` 머지 — 임계치 SoT.
- ADR-0023 (`docs/decisions/0023-workflow-main-sync.md`) accepted — sub-옵션 (A1/A2/A3) 절차 명시.
- Discord webhook secret (`DISCORD_WEBHOOK_URL`, 운영 .env / GHA repository secret 동시 존재).
- (P0 escalate 시) `DISCORD_MOBRUJI_WEBHOOK_URL` — 없으면 P0 도 digest fallback.
- bot.py / `tools/discord-daemon/` 동작 무관 (webhook 직접 호출).

### 위험 / mitigation
- **false positive (release PR 머지 직후 24h 안 1 건은 정상)**: §8-7-2 24h sync window 와 cron 시간 동기화. `MAIN_ONLY=1` warning 발생 시 nmae 가 back-merge PR 진행 중인지 cross-ref (직전 24h release PR 머지 commit 확인 step 추가 가능 — v1 에는 단순 임계치).
- **임계치 너무 보수적 → noise**: 24h debounce 가 1 차 방어. 운영 1주 후 §10 갱신.
- **Discord rate limit**: 1 회 push / level. multi-line 단일 message.
- **webhook secret leak**: GHA secret + masking. `set -x` 금지.

### 롤백
- workflow yml 삭제 / disable (`on: schedule` 주석화). 영향 = silent monitor 중단 → 인적 polling 복귀.
- check-fork.sh 는 로컬 / 다른 cron 으로도 재사용 가능 — 별도 cleanup 불요.

## 11) 후속 액션 (impl PR 권고)
1. **be (다음 사이클)**: PR 2 — workflow yml + check-fork.sh 구현
   - 라벨: `type:feat scope:infra ai-generated ai:claude session:backend needs-human-review`
   - base=develop
   - 검증: `gh workflow run release-fork-watchdog.yml` 즉시 trigger → 현재 main/develop fork 측정 결과 GHA summary 확인
2. **nmae**: PR 2 머지 후 cron 첫 trigger 결과 모니터링 — false positive 시 §10 갱신
3. **rev**: PR 2 단계 1 audit 시 (a) workflow yml syntax (b) check-fork.sh edge case (merge-base 빈 응답 / 분리된 history) (c) Discord push 본문 정중체 검증
4. **plan**: 운영 1주 후 본 spec §10 갱신 (실제 false positive 빈도 / 임계치 조정 필요 여부)

## 12) 관련 문서 cross-ref
- `docs/ai-harness/02-agent-workflow.md §8-7` — release cadence + main↔develop sync 의무 (PR #1162)
- `docs/decisions/0023-workflow-main-sync.md` — main sync ADR (accepted)
- `docs/features/adr-0023-suboption-analysis.md` — sub-옵션 A1/A2/A3 비교 (PR #1159)
- `docs/features/release-cadence-v0.4.0.md` — release cadence 운영 룰
- `docs/features/nmae-cycle-watchdog.md` — bot.py watchdog 패턴 (graceful skip / Discord push 본문 형식 참고)
- `docs/ai-harness/14-discord-ops.md` — webhook secret 셋업
