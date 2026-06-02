---
feature: dev browser QA env — chrome headless + dev server + CDP wrapper
slug: dev-browser-qa-env
status: draft
owner: @goohong
scope: infra
related_issues: [1310]
related_prs: [1350, 1413]
last_reviewed: 2026-05-31
---

# dev-browser-qa env — rev / sub-agent 가 브라우저로 직접 QA 가능한 영구 환경

## 1) 개요 (What / Why)

사용자 directive 2026-05-31 "rev 가 브라우저로 직접 테스트하는 환경 구축하자" 의 영구 wrapper.

배경:
- rev round 42 / 43 에서 다크모드 사고 QA 시 chrome 환경 부재로 정적 분석 fallback 만 가능 → root cause 미확정.
- 본 nmae 가 직전 직접 진행: google-chrome 148 install + `next dev --webpack` + CDP `--remote-debugging-port=9222` path 확립 + 다크모드 사고 #2 root cause (`:where()` cascade specificity 0) 확정 + fix (PR #1413).
- 본 path 를 wrapper 로 박제 → rev / fe / be sub-agent 1줄 호출로 동일 환경 진입.

## 2) 사용자 / sub-agent 시나리오

- 시나리오 1 (rev sub-agent QA): rev round N 의 단계 2 e2e — `tools/dev-browser-qa.sh start` → `tools/dev-browser-qa.sh test-darkmode /recommend` → 결과 evidence 박제.
- 시나리오 2 (fe sub-agent 직접 검증): fix patch 후 본인 환경에서 동일 wrapper 호출 → before/after 비교 → PR 본문에 evidence 첨부.
- 시나리오 3 (본 nmae 가 직접 QA): 사용자 directive 즉시 응답 — 1줄 호출 + 결과 보고.

## 3) 요구사항

### 기능 요구사항

- [x] `tools/dev-browser-qa.sh` 신설 — 5 subcommand (`start` / `stop` / `status` / `test-darkmode` / `test-darkmode-all`).
- [x] `tools/dev_browser_qa.py` 신설 — CDP WebSocket client (python stdlib only, websocket-client 의존 회피).
- [x] chrome 자동 시작: `--headless=new --no-sandbox --disable-gpu --remote-debugging-port=9222 --remote-allow-origins='*'`.
- [x] dev server 자동 시작: `next dev --webpack -p 4322` (워크트리 자동 탐지 — be/fe/rev/plan/bridge 의 web/ + node_modules 첫 매치).
- [x] `test-darkmode <route>` — light/dark mode 비교 + computed style read + screenshot 박제 + verdict.
- [x] graceful fallback — dev server 또는 chrome 시작 timeout 시 log tail + exit 2.

### 비기능 요구사항

- **의존**: google-chrome (apt 또는 .deb install) + python3 (stdlib) + node_modules (워크트리 안 또는 `/data/node_modules` symlink).
- **port 충돌 회피**: 환경변수 `DEV_PORT` / `CDP_PORT` 로 override.
- **idempotent start**: `is_dev_running` / `is_chrome_running` check 후 skip (이미 켜진 상태에서 재호출 OK).
- **secret 안전**: chrome `--no-sandbox` (root 아닌 환경 필요) + `--user-data-dir=/tmp/dev-browser-qa-profile` (별 profile 격리).

## 4) 범위 / 비범위

### 포함

- chrome headless + CDP (Page / Runtime / DOM) 1차 wrapper.
- dev server (next.js webpack mode) 시작/종료 idempotent.
- 다크모드 light/dark 비교 evidence 박제 (computed style + screenshot byte diff).

### 제외 (Out of Scope)

- **Playwright 영구 도입** — visual-regression-ci.md spec (PR 3) 별 사이클. 본 spec 은 더 가벼운 CDP 기반.
- **multi-tab / WebDriver / Selenium** — 단일 tab + CDP 만.
- **production deploy URL e2e** — local dev server 만. production 은 별 wrapper.
- **CI 자동 활성화** — 본 wrapper 는 local QA / sub-agent 용. CI 통합은 visual-regression-ci.md.

## 5) 설계

### 5-1) wrapper API

```bash
# 시작 (idempotent)
tools/dev-browser-qa.sh start [<dev-port=4322>] [<cdp-port=9222>]

# 종료
tools/dev-browser-qa.sh stop

# 상태 확인
tools/dev-browser-qa.sh status

# 다크모드 검증
tools/dev-browser-qa.sh test-darkmode /recommend
tools/dev-browser-qa.sh test-darkmode /                  # 홈
tools/dev-browser-qa.sh test-darkmode /songs/123

# 9 페이지 일괄 검증 (matrix 출력)
tools/dev-browser-qa.sh test-darkmode-all
```

### 5-1-1) `test-darkmode-all` 대상 9 routes

`/`, `/voice-range`, `/recommend`, `/songs`, `/history`, `/likes`, `/bookmarks`, `/offline`, `/maintenance`

각 route 별로 `test-darkmode` 동일 흐름 (light/dark + computed style + screenshot). 종료 시 matrix:

```
=== matrix (pass=8 / fail=1 / total=9) ===
✅ / — verdict: ✅ OK
✅ /voice-range — verdict: ✅ OK
❌ /maintenance — verdict: ❌ 사고 — dark 적용 X
```

### 5-2) test-darkmode 흐름

1. CDP connect (existing tab — about:blank 또는 localhost:port)
2. localStorage clear → reload → light mode evidence capture
   - `html.dark` classList 확인 (false 기대)
   - `getComputedStyle(body).backgroundColor` read
   - `getComputedStyle(documentElement).getPropertyValue('--background')` read
   - `Page.captureScreenshot` → light.png
3. localStorage `mobruji-theme` = `dark` → reload → dark mode evidence
   - 동일 evidence 4종 (dark 기대)
4. verdict:
   - `dark.dark_class == True` AND `light.body_bg != dark.body_bg` AND `byte_diff(png) > 200` → ✅ OK
   - 그 외 → ❌ 다크 적용 X (사고)

### 5-3) 환경변수

| 변수 | default | 설명 |
|---|---|---|
| `DEV_PORT` | `4322` | dev server port |
| `CDP_PORT` | `9222` | chrome CDP port |
| `WEB_DIR` | (자동 탐지) | next.js dev server 가 띄울 web 디렉토리 |
| `CHROME_BIN` | `google-chrome` | chrome binary path |
| `QA_OUT_DIR` | `/tmp/dev-browser-qa` | 로그 / 스크린샷 출력 |
| `CHROME_PROFILE_DIR` | `/tmp/dev-browser-qa-profile` | chrome user-data-dir |

## 6) 보호 영역 변경 여부

- `tools/dev-browser-qa.sh` 신규 (보호 영역 X)
- `tools/dev_browser_qa.py` 신규 (보호 영역 X)
- 본 spec docs 신규 (보호 영역 X)

후속 PR 예고:
- `docs/ai-harness/actors/sub-agent.md` §2-rev 에 cross-ref 1 줄 추가.
- `tools/rev-queue/round-summary.sh` 또는 rev sub-agent prompt template 에 본 wrapper 호출 표준화.

## 7) 검증

### 본 spec 머지 시점 (수동 검증)

```bash
# 1. start
tools/dev-browser-qa.sh start
# 기대: dev server / chrome 둘 다 alive

# 2. status
tools/dev-browser-qa.sh status
# 기대: 둘 다 ✅

# 3. test-darkmode /
tools/dev-browser-qa.sh test-darkmode /
# 기대 (PR #1413 머지 후):
#   light: dark_class=False body_bg='rgb(255, 255, 255)' --background='#ffffff'
#   dark:  dark_class=True  body_bg='rgb(10, 10, 10)'   --background='#0a0a0a'
#   verdict: ✅ OK

# 4. stop
tools/dev-browser-qa.sh stop
```

### sub-agent 자동 통합 (후속 PR)

- rev sub-agent prompt template: 단계 2 e2e 의무 시 본 wrapper 호출.
- fe sub-agent: web fix patch PR 본문에 `tools/dev-browser-qa.sh test-darkmode` 결과 첨부 의무.

## 8) 결정 로그 (사용자 confirm 2026-05-31)

- **dev server port = 4322** — 사용자 confirm. production deploy (3000/8080 가정) 와 충돌 회피.
- **chrome 미설치 시 안내 메시지만 + exit** — 사용자 confirm. `sudo apt install` 자동 실행 X (system 변경 회피).
- **multi-route v1 포함** — 사용자 confirm. `test-darkmode-all` 9 페이지 일괄 wrapper 안 박제.
- **chrome `--no-sandbox`** — Ubuntu 24.04 NCP VM 검증 완료 (root 아닌 mobruji 사용자 환경 필수).
- **CDP WebSocket client = python stdlib only** — pip / npm install 회피, 즉시 사용.
- **Playwright 회피** — PR #1200 / #1208 npm symlink 사고 영구 영역 = visual-regression-ci.md 별 spec.

## 9) 변경 로그

- 2026-05-31: 본 nmae 가 직접 진행한 chrome 148 + CDP path 를 wrapper 로 박제 — sub-agent 학습 의존 ↓, 다음 사이클부터 1줄 호출 가능.
- 2026-05-31: Playwright 회피 — PR #1200 / #1208 의 npm symlink 사고 통합 fix 가 필요한 영구 도입은 visual-regression-ci.md 별 spec.
- 2026-05-31: CDP WebSocket client = python stdlib only (websocket-client 의존 회피 — pip install 없이 즉시 사용).
