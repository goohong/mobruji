---
feature: rev 사이클 브라우저 E2E 검증 환경 (headless 브라우저 + 2 단계 워크플로우 통합)
slug: rev-browser-e2e-env
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-31
---

# rev 사이클 브라우저 E2E 검증 환경 (headless 브라우저 + 2 단계 워크플로우 통합)

## 1) 개요 (What / Why)

rev 사이클의 단계 1 / 단계 2 e2e (`rev-e2e-2-stages.md`) 는 현재 **RestAssured / curl 기반 BE 검증**이 중심이다.
`web/**` 변경 PR 의 검증도 "응답 status 200 / DOM 문자열 grep" 수준에 머물러 **실제 브라우저 렌더링·사용자 플로우**(라이트/다크 렌더, CTA 동작, 페이지 전이, 클라이언트 라우팅, JS 런타임 에러)가 사각지대로 남는다.

이 사각지대는 `web-e2e-playwright.md` 가 도입하는 **`web/e2e/` Playwright 테스트 스위트**(repo 안 커밋되어, 도입 예정인 `web-e2e.yml` CI 가 PR build 대상으로 실행 — web-e2e-playwright impl PR 3 산출물)로 부분 해소된다. 그러나 그 스위트는 **CI 의 ephemeral build** 만 대상으로 하며, rev 사이클이 자기 워크트리에서 **실제 deploy 환경(NCP dev live URL)** 을 직접 브라우저로 구동해 단계 2 를 수행할 실행 환경·절차는 정의되어 있지 않다.

본 spec 은 그 **실행 환경 레이어**를 박제한다 — rev 워크트리(macOS)에 headless 브라우저를 셋업하고, rev sub-agent 가 단계 1(local build) / 단계 2(NCP dev live) 에서 동일 Playwright 스위트 + 탐색적(ad-hoc) 브라우저 플로우를 (단계 2 는 live URL 대상으로) 구동하는 절차, rev-queue / rev-e2e-2-stages 워크플로우 통합, CI 와의 역할 분담을 정의한다.

> **단계 3(release 후 production live) 부재 근거**: production 환경이 존재하지 않으므로 단계 3 은 `rev-e2e-2-stages.md §1-1` 에서 사용자 결정으로 폐기됐다 (2026-05-30). 본 spec 도 2 단계 모델만 다룬다. 향후 production 환경 신설 시 부활 가능 — 그 시점에 본 spec 도 단계 3 live URL 절차를 별도 갱신한다.

대상 액터: **rev sub-agent**(주 사용자) / nmae(머지 결정) / fe sub-agent(시나리오 작성 — 본 spec 범위 밖, web-e2e-playwright 소관).

## 2) 사용자 시나리오

본 기능의 직접 사용자 = 개발 사이클 액터(rev / nmae). end-user 가시화는 없다.

1. **rev sub-agent — 단계 1 (PR 머지 전, e2e 가능 scope:web PR — `rev-e2e-2-stages.md §3-1` Pre-merge review)**:
   rev 가 PR 브랜치를 자기 워크트리(`mobruji-rev`)에 fetch → `cd web && npm ci && npm run build` → 로컬 dev server 기동 → `npx playwright test`(커밋된 `web/e2e/` 스위트) 를 headless chromium 으로 실행 → 통과 시 `reviewed:claude` 라벨.
   `web-e2e-playwright` 도입 후 CI(`web-e2e.yml`)가 같은 스위트를 build 대상으로 돌리므로, 그 시점부터 단계 1 의 rev 브라우저 실행은 **CI green 재확인 + 탐색적 보강**(스위트에 없는 신규 플로우 수동 구동)으로 정의한다.

2. **rev sub-agent — 단계 2 (develop 머지 후, NCP dev live 환경 — `rev-e2e-2-stages.md §3-2` Post-merge audit)**:
   develop 머지 + NCP dev deploy 완료 대기(~5분) 후, rev 가 `PLAYWRIGHT_BASE_URL=<dev live URL> npx playwright test --grep @smoke` 로 **live dev URL** 대상 스위트 재실행. CI 는 ephemeral build 만 보므로 **이 단계가 rev 의 고유 가치**(실제 deploy 회귀 탐지). 통과 → `rev-post-merge-pass` 라벨 / 실패 → `regression:dev` + revert 이슈.

3. **rev sub-agent — 탐색적 검증 (스위트 미커버 플로우)**:
   PR 이 `web/e2e/` 에 대응 시나리오가 아직 없는 페이지/플로우를 변경한 경우, rev 가 일회성 inline Playwright 스크립트(또는 `--ui` 불가한 headless 환경에서 trace 수집)로 플로우를 직접 구동해 결함을 탐지하고 PR 코멘트로 보고 + fe 후속 시나리오 추가를 nmae 에 권고.

## 3) 요구사항

### 기능 요구사항

- [ ] rev 워크트리(`mobruji-rev`, macOS)에 headless 브라우저 binary 설치 절차 정의 (`npx playwright install chromium` + 캐시 경로)
- [ ] rev 가 단계 1 / 단계 2 에서 **동일 Playwright 스위트를 BASE_URL 만 바꿔 재실행**하는 명령 규약 (`PLAYWRIGHT_BASE_URL` env override — 단계 1 = 로컬 / 단계 2 = dev live URL)
- [ ] live deploy URL 대상 실행 시 build/dev-server 기동 불요(이미 deploy 됨) — config 가 `baseURL` env 우선 / 부재 시 로컬 webServer 기동하도록 분기 (config 소유는 web-e2e-playwright impl PR 1 — 본 spec 은 **요구사항만 명시**)
- [ ] rev-queue / rev-e2e-2-stages 단계별 trigger 와 통합 — `web/playwright.config.ts` 존재 여부 = e2e 가능 판정 (이미 `rev-e2e-2-stages §3-1` 에 박제됨, 본 spec 은 단계 2 live URL 절차를 보강)
- [ ] 탐색적 검증 절차 — 스위트 미커버 플로우의 일회성 구동 + trace/screenshot 수집 + PR 코멘트 보고 규약
- [ ] macOS 환경 caveat 가드 — flock 부재(#1192) 와 동일 정신의 환경 의존성 fallback (browser binary 부재 / 네트워크 격리 시 graceful skip + 사유 코멘트)
- [ ] rev 환경 셋업 검증 명령 (`npx playwright --version` + chromium 설치 확인) — 매 사이클 first-action 또는 lazy 설치

### 비기능 요구사항

- **실행 시간**: 단계 1 = `rev-e2e-2-stages §4` 타임아웃 10분 안. live URL 단계 2 smoke(5-7건 chromium-only) = 3분 이하 목표 (`rev-e2e-2-stages §4` 단계 2 타임아웃 5분 안).
- **격리/멱등성**: rev 는 **파일 수정 절대 금지**(`pre-push` hook). 브라우저 실행은 read-only 검증만 — `playwright-report/` `test-results/` 산출물은 `.gitignore` 처리(web-e2e-playwright impl PR 1 소관)되어 commit 되지 않음.
- **재현성**: browser binary 는 `~/.cache/ms-playwright`(macOS: `~/Library/Caches/ms-playwright`) 캐시. 최초 설치 ~30초 허용.
- **관측성**: 단계별 실행 결과(통과/실패/실행 URL/elapsed) 는 PR 코멘트(`rev단계N: 🟢/🟡/🔴 ...` 패턴, `rev-qa-protocol.md §5-9` SoT) + `cycle-status.json` `rev.in_progress` 기록. live URL 단계 2 실패는 trace artifact 경로를 코멘트에 첨부.
- **환경 안전**: 단계 2 dev live 대상 실행은 dev 환경이 사용자 dogfooding 환경과 동일 endpoint 이므로, mutation 플로우(회원가입/데이터 생성) 는 **dev 전용 테스트 데이터로만** 수행하고 누적 오염을 피한다. (production 환경 부재로 production-only read-only 제약은 본 spec 범위 밖 — 단계 3 폐기.)

## 4) 범위 / 비범위 (중요)

### 포함

- rev 워크트리(macOS) headless 브라우저 환경 셋업 절차 + 캐시 + 검증 명령
- rev 단계 1 / 단계 2 에서 동일 스위트를 **BASE_URL override 로 실행**하는 규약 (단계 2 = dev live 환경 재실행)
- 탐색적(스위트 미커버) 브라우저 검증 절차 + 보고 규약
- rev-e2e-2-stages 단계 2 의 "live URL 대상 Playwright 재실행" 절차 보강 (§3-2 cross-ref)
- CI(`web-e2e.yml`, 도입 예정) ↔ rev 브라우저 실행의 **역할 분담** 명문화 (중복 제거)
- 도구 선정(Playwright vs Cypress) 트레이드오프 + 결정 근거

### 제외 (Out of Scope)

- **`web/e2e/` 테스트 스위트 내용·시나리오 작성** — `web-e2e-playwright.md` 소관(fe sub-agent). 본 spec 은 그 스위트를 rev 가 **어느 환경에서 어떻게 구동하는가**만 다룬다.
- **`web/playwright.config.ts` / `web/package.json` devDep / `npm run test:e2e` script** 작성 — web-e2e-playwright impl PR 1 소관. 본 spec 은 config 가 충족해야 할 요구사항(BASE_URL env 분기)만 명시.
- **CI workflow(`web-e2e.yml`) 작성** — web-e2e-playwright impl PR 3 소관 (현재 미구현 — 도입 예정). 본 spec 은 CI 와 rev 의 역할 경계만.
- **시각 회귀(visual regression) baseline 비교** — `visual-regression-ci.md` 소관.
- **BE RestAssured E2E** — 기존 `07-testing-guide.md` + `rev-e2e-2-stages.md` 소관. 본 spec 은 브라우저 레이어만 보강.
- **단계 3(release 후 production live) 검증** — production 환경 부재로 `rev-e2e-2-stages.md §1-1` 에서 폐기됨 (2026-05-30). production 환경 신설 시 별도 갱신.
- **단계 2 live URL smoke 의 cron/schedule 자동화** — 본 spec 은 rev sub-agent 수동/`bot.py` trigger(`rev-e2e-2-stages §8`). 추가 schedule 자동화는 별도 spec.
- **모바일 viewport / 멀티 브라우저(firefox/webkit) 매트릭스** — chromium-only 기본. web-e2e-playwright §4 와 동일.

## 5) 설계

### 5-1) 도메인 모델

해당 없음. 본 기능은 rev 사이클 인프라(검증 실행 환경)이며 product 도메인 엔티티 변경 없음 — `06-domain-model.md §4` 유비쿼터스 랭귀지 신규 등재 불요.
관련: `docs/ai-harness/07-testing-guide.md`(E2E 룰), `docs/features/rev-e2e-2-stages.md`(rev 단계), `docs/features/web-e2e-playwright.md`(스위트), `docs/features/rev-sla.md`(단계별 SLA).

### 5-2) API 엔드포인트

해당 없음. rev 가 기존 deploy 된 화면을 검증할 뿐 신규 API 도입 없음.

### 5-3) 외부 연동 — 도구 선정 (Playwright vs Cypress)

본 spec 의 핵심 결정. rev 가 구동할 브라우저 자동화 도구를 정한다.

| 항목 | Playwright | Cypress | rev 환경 적합성 |
|---|---|---|---|
| **web 스위트 일관성** | `web-e2e-playwright.md` 가 이미 `@playwright/test` 채택 → rev 가 **동일 스위트 그대로 재사용** | 별도 스위트 / 별도 문법 → rev 전용 중복 스위트 필요 | **Playwright 압도** — 단일 스위트를 단계 1 / 단계 2 가 공유 |
| **임의 live URL 대상 실행** | `baseURL` config / `PLAYWRIGHT_BASE_URL` env override 로 dev live URL 즉시 타격 | `baseUrl` config 지원하나 dev-server 기동 가정이 강함 | **Playwright** — env override 가 단계 2 핵심 |
| **headless / CI 친화** | 네이티브 headless, `--reporter=line`, trace/screenshot on-failure | headless 지원하나 Electron 런타임 무거움 | **Playwright** — rev 무인 환경 적합 |
| **macOS 설치** | `npx playwright install chromium` 단일 명령, 캐시 경로 명확 | binary 크기 큼, 캐시 관리 복잡 | **Playwright** |
| **inline/탐색적 스크립트** | `playwright` API 로 일회성 `.ts` 스크립트 작성 용이 | runner 종속성 강해 일회성 script 부적합 | **Playwright** — 탐색적 검증(시나리오 3) 핵심 |
| **멀티 브라우저** | chromium/firefox/webkit 네이티브 | chromium 계열 + firefox(실험) | 무승부 (chromium-only 기본) |
| **디버깅 UX** | trace viewer / `--ui` | Cypress GUI(대화형) 강점 | Cypress 강점이나 rev 는 무인 headless → 무의미 |

**결정 = Playwright.** 결정적 근거:
1. `web-e2e-playwright.md` 가 web/ 스위트로 이미 Playwright 채택 — rev 가 **별도 도구를 도입하면 스위트가 2벌**이 되어 유지보수·drift 비용 발생. 단일 스위트를 단계 1 / 단계 2 + CI 가 공유하는 것이 본질.
2. 단계 2 의 핵심 = **live deploy URL 대상 재실행**인데 Playwright 의 `baseURL` env override 가 이를 가장 깔끔하게 지원.
3. rev 는 무인(headless) 환경 — Cypress 의 대화형 GUI 강점이 무의미하고 Electron 런타임은 오히려 부담.

→ Cypress 는 명시적으로 제외. (별도 ADR 불요 — web-e2e-playwright §8 Q6 결정 "Vitest/RestAssured 와 동일 단순 도구 도입, 본 spec 결정으로 갈음" 정신 계승.)

### 5-4) 데이터 흐름 / 단계별 실행 시퀀스

```
[단계 1: PR 머지 전 — local build (Pre-merge review)]
  rev: git fetch origin pull/<PR>/head → checkout
  rev: cd web && npm ci && npm run build
  rev: npx playwright test --grep @smoke        # config webServer 가 로컬 기동
       └─ (web-e2e-playwright 도입 후 CI web-e2e.yml 가 같은 검증 → rev 는 green 재확인 + 탐색적 보강)
  통과 → reviewed:claude 라벨 + ✅ 코멘트

[단계 2: develop 머지 후 — NCP dev live (Post-merge audit)]
  대기: develop deploy 완료 (~5분)
  rev: PLAYWRIGHT_BASE_URL=<dev live URL> \
         npx playwright test --grep @smoke       # config 가 baseURL env 우선 → webServer skip
       └─ CI 가 못 보는 실제 deploy 회귀 탐지 (rev 고유 가치)
  통과 → rev-post-merge-pass / 실패 → regression:dev + revert 이슈

[탐색적: 스위트 미커버 플로우]
  rev: 일회성 inline .ts script 로 플로우 구동 → trace 수집
  결함 → PR 코멘트 보고 + fe 후속 시나리오 추가 nmae 권고
```

> production live(단계 3) 시퀀스는 의도적으로 부재 — production 환경이 없으므로 `rev-e2e-2-stages.md §1-1` 에서 폐기됐다. 환경 신설 시 본 §에 단계 3 live URL 블록을 추가한다.

`PLAYWRIGHT_BASE_URL` env 분기 요구 (config 소유 = web-e2e-playwright impl PR 1, 본 spec 은 요구만):

```ts
// web/playwright.config.ts 가 충족해야 할 요구 (예시 — 작성은 web-e2e-playwright 소관)
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:3000';
export default defineConfig({
  use: { baseURL },
  // baseURL 이 외부 env 로 주어지면 webServer 기동 skip (live deploy 대상)
  webServer: process.env.PLAYWRIGHT_BASE_URL ? undefined : {
    command: 'npm run start',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
  },
});
```

### 5-5) DB 마이그레이션

해당 없음.

### 5-6) 프론트엔드 화면

해당 없음. 화면 변경 없음 — rev 는 기존 화면을 검증만 한다. (`data-testid` 안정 selector 추가는 web-e2e-playwright impl PR 2 소관.)

### 5-7) CI ↔ rev 브라우저 실행 역할 분담 (중복 제거)

본 spec 의 가시성 핵심 — 두 채널이 **다른 환경**을 검증하므로 중복이 아니다:

| 채널 | 환경 | 시점 | 검증 대상 | 소관 spec |
|---|---|---|---|---|
| **CI `web-e2e.yml`** (도입 예정) | ephemeral build (GitHub runner) | PR open/synchronize | PR 코드가 build·smoke 통과하는가 (pre-merge gate) | web-e2e-playwright (impl PR 3) |
| **rev 단계 1** | 로컬 build (rev 워크트리) | rev 큐 발굴 시 | (CI 도입 후) CI green 재확인 + 탐색적 보강 | 본 spec |
| **rev 단계 2** | **NCP dev live** | develop 머지 후 | 실제 deploy 회귀 (CI 가 못 봄) | 본 spec |

→ CI 는 **build 게이트**, rev 단계 2 는 **live deploy 검증** — 본질적으로 다른 layer. rev 단계 1 은 (CI 도입 후) CI 와 환경이 겹치므로 "green 재확인 + 탐색적 보강" 으로 역할을 좁혀 중복 작업을 최소화한다. `web-e2e.yml` 미도입 동안에는 rev 단계 1 의 로컬 build playwright 실행이 유일한 브라우저 스위트 검증 채널이다.

### 5-8) rev-e2e-2-stages.md §3-2 보강 (cross-ref)

본 spec 도입 시 `rev-e2e-2-stages.md` 의 단계 2(Post-merge audit) 절차에 다음 한 줄을 명문화 보강한다 (impl PR 1 = docs PR):

- §3-2 (단계 2): "rev 가 단계 1 시나리오 동일 재실행 — 실제 dev 환경" →
  "**`web/playwright.config.ts` 존재 시** `PLAYWRIGHT_BASE_URL=<dev live URL> npx playwright test --grep @smoke` 로 NCP dev live 대상 재실행 (`rev-browser-e2e-env.md §5-4`). config 부재 시 기존 curl/RestAssured."

본 보강은 `web-e2e-playwright impl PR 1`(config + BASE_URL env 분기) 머지 후 활성 — 그 전에는 단계 2 가 기존 curl 검증으로 no-op fallback.

## 6) 작업 분할 (예상 PR 리스트)

> 본 spec 자체는 docs. 실제 구동 가능 시점은 web-e2e-playwright impl PR 1(config + BASE_URL env 분기) 머지에 의존.

- [x] **본 PR (type:docs scope:infra)**: `docs/features/rev-browser-e2e-env.md` spec 신설
- [ ] **impl PR 1 (type:docs scope:infra)**: `rev-e2e-2-stages.md §3-2` live URL 절차 보강 + `sub-agent.md §2-rev` 에 "단계 2 = BASE_URL override Playwright dev live 재실행" 1줄 + 환경 셋업 검증 명령 — **web-e2e-playwright impl PR 1 머지 후**
- [ ] **impl PR 2 (type:docs scope:infra)**: `web/playwright.config.ts` 의 `PLAYWRIGHT_BASE_URL` env 분기 + `@smoke` 태그 규약을 web-e2e-playwright spec §5 에 반영(요구 명세 cross-ref). config 코드 작성은 fe(web-e2e-playwright impl) — 본 PR 은 docs 요구 명세만
- [ ] **impl PR 3 (type:docs scope:infra, 선택)**: rev 환경 셋업 runbook 1쪽 (`docs/ai-harness/07-testing-guide.md` 또는 `tools/rev-queue/README.md` 에 "rev 브라우저 검증 환경 셋업" 절 추가) — browser install / 캐시 / macOS caveat / live URL 실행 명령 reference
- [ ] **검증 PR (type:test scope:infra, 선택)**: 단계 2 live URL 재실행이 실제 dev deploy 에서 동작하는지 rev sub-agent 1 사이클 sanity (메모리 [[feedback-verify-and-iterate]])

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (본 spec 은 docs only).
  - 단, impl 후속에서 `web/package.json`(devDep) / `web/playwright.config.ts` / `.github/workflows/web-e2e.yml` 변경은 **web-e2e-playwright.md 소관**이며 그 spec §6 에 정보성 보호 영역으로 이미 박제됨. 본 spec 의 docs PR 들은 보호 영역 미변경.

## 7) 테스트 전략

본 spec 자체는 docs 이므로 검증 = "후속 rev 사이클이 본 절차대로 (단계 2 에서) live URL Playwright 를 실제 구동하는가".

- **단위/통합**: 해당 없음 (rev 절차 문서).
- **E2E**: 본 spec 이 정의하는 것이 곧 E2E 절차. 검증 = web-e2e-playwright impl PR 1 머지 후 rev sub-agent 가 단계 2 에서 `PLAYWRIGHT_BASE_URL=<dev>` 재실행을 실제 수행하고 PR 코멘트에 결과를 남기는지 1 사이클 sanity.
- **mock 전략**: 단계 2 는 **live deploy 대상이므로 mock 불요**(실제 BE 호출). 단계 1 local build 의 BE 의존 mock 은 web-e2e-playwright §7 (Playwright `page.route()`) 소관.
- **실패 회귀 가드**: 단계 2 에서 의도적으로 dev 에 깨진 변경을 deploy 했을 때 rev smoke 가 실제 `regression:dev` 를 잡는지 sanity 1회(검증 PR).

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | (web-e2e.yml CI 도입 후) 단계 1 rev 브라우저 실행 = CI 와 환경 중복인데 실제로 돌릴지 | (a) green 재확인 + 탐색적 보강만 / (b) 매번 full 재실행 / (c) CI green 시 skip, 탐색적만 | @mobruji-maestro / impl PR 1 전. 권장 = (a) |
| Q2 | dev live URL 의 canonical 값 | (a) `http://101.79.20.94/` 직접 / (b) `.env` `PLAYWRIGHT_DEV_URL` 주입 | @mobruji-maestro / impl PR 1 전. 권장 = (b) — 하드코딩 회피, ncp-dev-deployment.md 실제 URL 과 일치 |
| Q3 | 단계 2 dev mutation 플로우 — 전용 테스트 계정 vs read-only 만 | (a) read-only(`@smoke`)만 / (b) dev 전용 테스트 계정 + cleanup | @mobruji-maestro / impl PR 1 전. 권장 = (a) — dev 데이터 누적 오염 회피 (production 부재로 단계 3 제약은 무관) |
| Q4 | 탐색적 일회성 script 의 보관 위치 (rev 파일 수정 금지 제약) | (a) `/tmp` 휘발 + trace 만 PR 코멘트 / (b) `tools/rev-e2e/` 에 fe 가 커밋 | @mobruji-maestro / impl PR 1 전. 권장 = (a) — rev pre-push hook 제약 준수 |
| Q5 | 단계 2 live URL 실행 주체 — rev 워크트리(macOS) 직접 vs NCP ssh fallback | (a) rev macOS 직접(네트워크 가능 시) / (b) flock-fallback 정신의 NCP ssh fallback | @mobruji-maestro / impl PR 3 전. 권장 = (a), 네트워크 격리 시 (b) graceful |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-31: 초안 작성 (status=draft). directive `rev 브라우저 E2E 검증 환경 Feature Spec` 대응. 도구 = **Playwright** 결정 (web-e2e-playwright.md 와 단일 스위트 공유 + live URL `baseURL` env override + 무인 headless 적합 — §5-3). Cypress 명시 제외. 본 spec 경계 = rev 워크트리 **실행 환경 + 단계 2 live deploy 검증 레이어** (스위트 자체는 web-e2e-playwright, 시각 회귀는 visual-regression-ci 소관).
- 2026-05-31: rev 감사(🔴) findings 반영 재작성 (PR #1400 / 이슈 #1405). (1) 폐기된 단계 3(production) 전면 제거 — `rev-e2e-2-stages.md §1-1` 2 단계 모델 정렬, production live(`mobruji.com`) 시나리오·시퀀스·역할분담 삭제. (2) 죽은 링크 `rev-e2e-3-stages.md` → 실재 `rev-e2e-2-stages.md` 전부 교체. (3) `web-e2e.yml` 현재형 단정 → "도입 예정"(web-e2e-playwright impl PR 3 산출물) 정정. (4) 죽은 메모리 링크 `[[feedback-spec-frontmatter-required]]` → frontmatter 강제 실체인 `.github/workflows/spec-status-check.yml` 인용으로 교체.

## 10) 관련

- 기존 spec `docs/features/rev-e2e-2-stages.md` (§3-2 live URL 절차 보강 대상 — Pre-merge review / Post-merge audit 2 단계 SoT)
- 기존 spec `docs/features/web-e2e-playwright.md` (`web/e2e/` 스위트 + config + CI `web-e2e.yml` — 본 spec 이 구동할 대상, 현재 미구현 도입 예정)
- 기존 spec `docs/features/visual-regression-ci.md` (시각 회귀 baseline — 본 spec 범위 밖)
- 기존 spec `docs/features/rev-sla.md` (단계별 응답 SLA — live URL 실행 timing 영향)
- 기존 spec `docs/features/rev-qa-protocol.md` (`rev단계N: 🟢/🟡/🔴` 코멘트 + Discord push 차등)
- 런북 `docs/ai-harness/actors/sub-agent.md §2-rev` (rev 환경 + 2 단계 + flock-fallback macOS caveat 선례)
- 런북 `docs/ai-harness/07-testing-guide.md` (E2E 룰)
- 배포 `docs/features/ncp-dev-deployment.md` (dev live URL canonical 출처 — Q2)
- 워크플로우 `.github/workflows/spec-status-check.yml` (Feature Spec frontmatter 형식·status enum 강제 — 본 spec frontmatter 8 필드 통과 게이트)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-verify-and-iterate]]
