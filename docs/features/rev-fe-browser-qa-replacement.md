---
feature: rev 실 브라우저 FE QA 단계 — 기존 E2E 대체 정책 (replacement scope + 도구 결정)
slug: rev-fe-browser-qa-replacement
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-31
---

# rev 실 브라우저 FE QA 단계 — 기존 E2E 대체 정책 (replacement scope + 도구 결정)

> **세 자매 spec 과의 경계 (중복 방지 — 본 spec 은 "정책 SoT")**
> - `web-e2e-playwright.md` = `web/e2e/` **테스트 스위트 자체** (시나리오 / config / CI `web-e2e.yml`) 소관 (fe).
> - `rev-browser-e2e-env.md` = rev 워크트리 **실행 환경 레이어** (headless 설치 / `PLAYWRIGHT_BASE_URL` 단계 1·2 override / 탐색적 구동) 소관.
> - `rev-e2e-2-stages.md` = rev **2 단계 워크플로우** (Pre-merge / Post-merge / 라벨 / SLA) SoT.
> - **본 spec** = 위 세 채널이 도입하는 브라우저 QA 가 **기존(legacy) E2E 를 어디까지 대체하고 무엇을 남기는가** 라는 *정책·판정 기준* SoT. 실행 환경·스위트·단계 정의는 재서술하지 않고 cross-ref 한다.

> **directive "3단계" 정정**: 본 spec 을 트리거한 directive 문구는 "rev 3단계 e2e 검증과의 통합"이나, 단계 3(release 후 production 검증)은 production 환경 부재 사유로 2026-05-30 사용자 결정에 의해 **폐기**됐다 (`rev-e2e-2-stages.md §1-1`). 따라서 본 spec 의 통합 대상은 **2 단계 모델**(🟡 Pre-merge review / 🔵 Post-merge audit)이다. production 환경 신설 시 단계 3 부활과 함께 본 정책도 갱신한다.

## 1) 개요 (What / Why)

rev 사이클의 FE(`web/**`) PR 검증은 현재 **"HTTP 200 + DOM 문자열 grep / curl"** 수준에 머문다 (`rev-browser-e2e-env.md §1`). 이 방식은 실제 브라우저가 렌더한 결과(라이트/다크 적용, 클라이언트 라우팅, CTA 동작, JS 런타임 에러)를 검증하지 못해 시각·상호작용 회귀가 사각지대로 남는다.

`web-e2e-playwright.md` + `rev-browser-e2e-env.md` 가 **실 브라우저(headless Playwright) 기반 FE QA 단계**를 도입한다. 본 spec 은 그 단계가 도입된 뒤 **무엇을 브라우저 QA 로 대체하고 무엇을 기존 E2E(RestAssured / curl / DOM grep)로 남길지** 결정하는 *대체 정책*과 *판정 기준*을 박제한다. 정책 부재 시 두 검증 채널이 같은 것을 중복 검사하거나(낭비) 사각지대가 둘 다에서 빠지는(누락) 위험이 있다.

대상 액터: **rev sub-agent**(정책 실행 주체) / nmae(머지 결정) / fe sub-agent(스위트 작성 — 본 spec 범위 밖).

## 2) 사용자 시나리오

본 기능의 직접 사용자 = 개발 사이클 액터(rev / nmae). end-user 가시화 없음.

1. **rev — scope:web PR 단계 1 검증**: rev 가 PR diff 를 분류해 "브라우저로 검증 가능한 행동(렌더/라우팅/CTA)" 은 Playwright 스위트로, "API 계약/데이터/비가시 동작" 은 RestAssured/curl 로 검증한다. 같은 항목을 양쪽에서 중복 검사하지 않는다 (본 spec §5-4 판정 매트릭스).
2. **rev — 신규 엔드포인트 동반 PR**: 신규 BE 엔드포인트는 CLAUDE.md 의무에 따라 RestAssured 성공 E2E 가 **잔존**한다. 그 엔드포인트를 호출하는 FE 페이지의 렌더/상호작용은 브라우저 QA 가 검증한다 (역할 분리).
3. **rev — 브라우저 실행 불가 환경**: rev 워크트리에 browser binary 부재/네트워크 격리 시, 브라우저 QA 는 graceful skip 되고 해당 항목은 **legacy curl/DOM grep 으로 fallback** 한다 (no-op 누락 방지, `rev-browser-e2e-env.md §3` macOS caveat 정신).

## 3) 요구사항

### 기능 요구사항

- [ ] **대체 정책 진술**: 브라우저로 렌더·구동 가능하고 *사용자 가시 행동*을 검증하는 항목은 브라우저 QA 로 **대체**, 기존 FE curl/DOM grep 검증은 **폐기**한다.
- [ ] **잔존 정책 진술**: API 응답 계약 / 신규 엔드포인트 성공 E2E / DB·마이그레이션 상태 / 인증·헤더·에러코드 / 성능(p95) 은 기존 E2E(RestAssured / k6 / 통합 테스트)로 **잔존**한다.
- [ ] **판정 기준 매트릭스**(§5-4) — 검증 클래스별로 "브라우저 QA 대체 / legacy 잔존 / 신규 브라우저 전용" 3 분류를 명시.
- [ ] **도구 결정**(§5-3) — Playwright vs Puppeteer 비교 + 결정 근거. (Cypress 는 `rev-browser-e2e-env.md §5-3` 에서 이미 제외.)
- [ ] **CI / rev 워크트리 실행 환경**(§5-5) — headless 브라우저 설치·실행 환경 설계 (실행 레이어 상세는 `rev-browser-e2e-env.md` cross-ref, 본 spec 은 *대체 정책이 CI ↔ rev 어디서 강제되는지*만 명시).
- [ ] **rev 2 단계 통합**(§5-6) — 🟡 Pre-merge review / 🔵 Post-merge audit 각 단계에서 대체 정책이 어떤 검증을 돌리는지 명문화. `rev-e2e-2-stages.md §3` 갱신 지점 명시.
- [ ] **fallback 규약** — 브라우저 실행 불가 시 legacy 로 graceful fallback (검증 누락 금지).

### 비기능 요구사항

- **중복 제거**: 같은 검증 의도(예: 홈 페이지 200)를 브라우저 QA + curl 양쪽에서 돌리지 않는다 — 판정 매트릭스가 단일 채널을 지정.
- **무회귀 보장**: 대체로 인해 기존 검증 커버리지가 *축소되지 않음*을 보장 — legacy 에서 잡던 것을 브라우저 QA 가 동등 이상으로 잡거나, 못 잡으면 legacy 잔존.
- **관측성**: 단계별 어떤 채널(browser / legacy)로 무엇을 검증했는지 PR 코멘트(`rev단계N: 🟢/🟡/🔴`, `rev-qa-protocol.md §5-9` SoT)에 명시.
- **유지보수성**: 판정 기준은 *검증 클래스* 단위(페이지당 X) — 페이지 추가 시 정책 재논의 불요.

## 4) 범위 / 비범위 (중요)

### 포함

- 브라우저 QA ↔ 기존 E2E **대체/잔존 정책** 진술 + 판정 기준 매트릭스 (본 spec 핵심)
- 도구 결정 — **Playwright vs Puppeteer** 비교 + 결정
- CI ↔ rev 실행 환경에서 대체 정책이 강제되는 지점 명시
- rev 2 단계(`rev-e2e-2-stages.md`) 통합 — 단계별 대체 정책 적용
- 브라우저 실행 불가 시 legacy fallback 규약

### 제외 (Out of Scope)

- **`web/e2e/` 스위트 시나리오·config·CI workflow 작성** — `web-e2e-playwright.md` 소관(fe).
- **rev 워크트리 headless 설치 절차 / `PLAYWRIGHT_BASE_URL` 단계별 override / 탐색적 구동 절차** — `rev-browser-e2e-env.md` 소관. 본 spec 은 그 환경 위에서의 *대체 정책*만.
- **rev 2 단계 워크플로우 정의(라벨/SLA/큐)** — `rev-e2e-2-stages.md` / `rev-sla.md` SoT.
- **시각 회귀 baseline 자동 비교(Percy/Argos/`toHaveScreenshot`)** — `visual-regression-ci.md` / `web-e2e-playwright.md §5-3 Q1` 소관. 본 spec 은 "시각 검증을 브라우저 QA 가 담당" 이라는 *귀속*만 진술.
- **BE RestAssured E2E 룰 자체** — `07-testing-guide.md` SoT. 본 spec 은 그 잔존 경계만.
- **단계 3(release 후 production live) 대체 정책** — production 환경 부재로 폐기(`rev-e2e-2-stages.md §1-1`). 환경 신설 시 갱신.

## 5) 설계

### 5-1) 도메인 모델

해당 없음. 본 기능은 rev 사이클 검증 정책이며 product 도메인 엔티티 변경 없음 — `06-domain-model.md §4` 유비쿼터스 랭귀지 신규 등재 불요.
관련: `07-testing-guide.md`(E2E 룰), `rev-e2e-2-stages.md`(단계), `rev-browser-e2e-env.md`(실행 환경), `web-e2e-playwright.md`(스위트).

### 5-2) API 엔드포인트

해당 없음. 검증 정책 문서 — 신규 API 도입 없음.

### 5-3) 도구 결정 — Playwright vs Puppeteer

본 spec 의 결정 항목 중 하나. (Cypress 는 `rev-browser-e2e-env.md §5-3` 에서 이미 제외 — Electron 런타임 부담 + 무인 headless 에서 GUI 강점 무의미.)

| 항목 | Playwright | Puppeteer | rev/web 적합성 |
|---|---|---|---|
| **test runner** | `@playwright/test` 내장 (runner + assertion + fixture) | 브라우저 제어 **라이브러리만** — Jest/Mocha 등 별도 runner + 별도 assertion lib 글루 필요 | **Playwright** — 글루 최소 |
| **web 스위트 일관성** | `web-e2e-playwright.md` 가 이미 `@playwright/test` 채택 → rev 가 **동일 스위트 재사용** | 별도 스위트/문법 → 스위트 2벌, drift 비용 | **Playwright 압도** |
| **web-first assertion** | `toHaveCSS` / `toBeVisible` auto-wait 내장 (다크/라이트 토큰 회귀 가드 직접) | 수동 `page.evaluate` + 외부 expect | **Playwright** — 토큰 회귀 검증 핵심 |
| **임의 live URL 실행** | `baseURL` config / `PLAYWRIGHT_BASE_URL` env override (단계 2 핵심) | `page.goto(url)` 가능하나 baseURL 추상화 부재 | **Playwright** |
| **멀티 브라우저** | chromium/firefox/webkit 네이티브 | chromium + firefox(실험) | **Playwright** (chromium-only 기본이라 차이 작음) |
| **trace/디버깅** | trace viewer / screenshot·video on-failure 내장 | 수동 구현 | **Playwright** — 무인 실패 진단 |
| **설치/캐시(macOS)** | `npx playwright install chromium` 단일 + 캐시 경로 명확 | `puppeteer` install 시 chromium 동봉, 버전 핀 관리 | 무승부 |

**결정 = Playwright.** 결정적 근거:
1. `web-e2e-playwright.md` 가 web/ 스위트로 이미 Playwright 채택 — Puppeteer 도입은 **스위트 2벌 + drift**. 단일 스위트를 CI / rev 단계 1·2 가 공유하는 것이 본질 (`rev-browser-e2e-env.md §5-3` 결정과 동일 정신).
2. Puppeteer 는 *제어 라이브러리*일 뿐 test runner/assertion 이 없어 별도 글루가 필요 — rev 무인 환경에서 유지보수 부담.
3. 토큰 회귀 가드(다크/라이트 `toHaveCSS`)·trace·`baseURL` env override 등 본 정책이 의존하는 기능이 Playwright 에 내장.

→ **Puppeteer 명시 제외.** 별도 ADR 불요 (`web-e2e-playwright.md §8 Q6` "Vitest/RestAssured 와 동일 단순 도구 도입, 본 spec 결정으로 갈음" 정신 계승).

### 5-4) 대체 범위 판정 기준 (본 spec 핵심)

**판정 룰 (1줄)**: *브라우저로 렌더·구동 가능하고 **사용자 가시 행동**을 검증하면 → 브라우저 QA 로 대체. **API 계약·데이터·비가시 동작**을 검증하면 → 기존 E2E 잔존.*

| # | 검증 클래스 | 판정 | 채널 | 비고 |
|---|---|---|---|---|
| C1 | 페이지 로드/렌더/레이아웃 | **대체** | 브라우저 QA | 기존 `curl + status 200 / DOM grep` 폐기 |
| C2 | 라이트/다크 토큰 적용 (visual contract) | **대체** | 브라우저 QA (`toHaveCSS`) | `web-e2e-playwright.md S6/S7`. baseline 자동비교는 visual-regression-ci 소관 |
| C3 | 클라이언트 라우팅 / 페이지 전이 | **대체** | 브라우저 QA | curl 로 검증 불가했던 영역 |
| C4 | CTA 클릭 / 폼 제출 / 상호작용 | **대체** | 브라우저 QA | 동상 |
| C5 | JS 런타임 / 콘솔 에러 | **신규(브라우저 전용)** | 브라우저 QA | legacy 부재 — 순증 커버리지 |
| C6 | API 응답 status / JSON schema / 계약 | **잔존** | RestAssured / curl | 브라우저는 화면만, 계약 정밀 검증 불가 |
| C7 | 신규 엔드포인트 성공 E2E | **잔존(의무)** | RestAssured | CLAUDE.md §4 / `07-testing-guide.md` 의무 — 대체 금지 |
| C8 | DB 상태 / 마이그레이션 | **잔존** | 통합 테스트 | 비가시 |
| C9 | 인증 / 헤더 / 에러 status 코드 | **잔존** | RestAssured / curl | 비가시 |
| C10 | 성능 / p95 | **잔존** | k6 (`recommendation-p95-regression-guard.md`) | 별 layer |
| C11 | BE 의존 FE 플로우 (렌더는 보되 BE 호출은 mock) | **대체(부분)** | 브라우저 QA + `page.route()` mock | mock 강도는 `web-e2e-playwright.md §7` 소관 |

**fallback 룰**: 브라우저 실행 불가(binary 부재 / 네트워크 격리 / `web/playwright.config.ts` 미도입) 시 C1~C5/C11 은 **legacy curl/DOM grep 으로 일시 fallback** — 대체로 인한 커버리지 *축소 금지*. config 도입 시점부터 대체 활성 (`rev-e2e-2-stages.md §3-1` Playwright trigger 조건과 동기).

**무회귀 원칙**: "대체" 는 *동등 이상 커버리지일 때만* legacy 폐기. 브라우저 QA 가 못 잡는 잔여가 있으면 해당 항목은 "잔존" 으로 분류 (둘 다 누락 방지).

### 5-5) CI / rev 워크트리 실행 환경 — 대체 정책 강제 지점

> 환경 셋업 절차 자체는 `rev-browser-e2e-env.md §3·§5` + `web-e2e-playwright.md §5-4` SoT. 본 절은 *대체 정책이 어느 채널에서 강제되는가* 만 정의 (중복 제거).

| 채널 | 환경 | 대체 정책 강제 방식 |
|---|---|---|
| **CI `web-e2e.yml`** (도입 예정, web-e2e-playwright impl PR 3) | ephemeral build (GitHub runner) | C1~C5/C11 을 PR build 대상 Playwright 로 강제 — red 시 머지 차단. 기존 web curl 기반 CI step 은 제거(대체). |
| **rev 단계 1** (로컬 build, rev 워크트리) | 로컬 build | CI 도입 후 = green 재확인 + 탐색적 보강(`rev-browser-e2e-env.md §5-7`). CI 미도입 동안 = 로컬 Playwright 가 C1~C5 유일 채널. C6~C10 은 RestAssured/curl 잔존. |
| **rev 단계 2** (NCP dev live) | dev live | live URL 대상 C1~C5 재실행(CI 가 못 봄). C6/C9 계약 검증은 live curl 잔존. |

- headless 브라우저 설치: `npx playwright install chromium` (캐시 `~/.cache/ms-playwright`, macOS `~/Library/Caches/ms-playwright`) — `rev-browser-e2e-env.md §3` SoT.
- rev 파일 수정 금지(pre-push hook) — 브라우저 실행은 read-only 검증, `playwright-report/`·`test-results/` 는 `.gitignore` (web-e2e-playwright impl PR 1 소관).

### 5-6) rev 2 단계 통합 — `rev-e2e-2-stages.md §3` 갱신 지점

본 정책 도입 시 `rev-e2e-2-stages.md` 의 다음을 명문화 갱신한다 (impl PR = docs):

- **§3-1 (🟡 Pre-merge review, scope:web)**: 현재 "scope:web → Playwright headless 또는 NCP dev 직접 호출" 을, 본 spec §5-4 매트릭스 cross-ref 로 "C1~C5/C11 = Playwright 대체 / C6~C10 = RestAssured·curl 잔존 / config 부재 시 legacy fallback" 으로 정밀화.
- **§3-2 (🔵 Post-merge audit)**: live URL 대상 C1~C5 재실행 (`rev-browser-e2e-env.md §5-8` 보강과 묶음).
- ~~§3-3 (단계 3)~~ — 폐기 (production 환경 부재).

`sub-agent.md §2-rev` 에는 "web PR 검증 채널 분리(브라우저 QA = C1~C5 / RestAssured = C6~C10) — `rev-fe-browser-qa-replacement.md §5-4` SoT" 1줄 추가.

### 5-7) DB 마이그레이션

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

> 본 spec 자체는 docs(정책 SoT). 실제 대체 활성 시점은 `web-e2e-playwright impl PR 1`(config) 머지에 의존.

- [x] **본 PR (type:docs scope:infra)**: `docs/features/rev-fe-browser-qa-replacement.md` spec 신설 + `README.md` 인덱스 row 추가 (본 spec + 누락된 `rev-browser-e2e-env` row 동반 보강).
- [ ] **impl PR 1 (type:docs scope:infra)**: `rev-e2e-2-stages.md §3-1/§3-2` 에 §5-4 매트릭스 cross-ref 명문화 + `sub-agent.md §2-rev` 채널 분리 1줄 — **web-e2e-playwright impl PR 1 머지 후**.
- [ ] **impl PR 2 (type:docs scope:infra, 선택)**: `07-testing-guide.md` 에 "web E2E 채널 분리(브라우저 QA vs RestAssured)" 절 추가 + 기존 web curl 검증 deprecated 명시.
- [ ] **검증 PR (type:test scope:infra, 선택)**: rev sub-agent 1 사이클이 scope:web PR 에서 본 매트릭스대로 채널을 분리 적용하는지 sanity (메모리 [[feedback-verify-and-iterate]]).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (본 spec 은 docs only).
  - impl 후속의 `web/playwright.config.ts` / `web/package.json` / `.github/workflows/web-e2e.yml` 변경은 **web-e2e-playwright.md 소관**이며 그 spec §6 에 정보성 보호 영역으로 이미 박제됨. 본 spec PR 들은 보호 영역 미변경.

## 7) 테스트 전략

본 spec 자체는 정책 docs — 검증 = "후속 rev 사이클이 §5-4 매트릭스대로 채널을 분리 적용하는가".

- **단위/통합**: 해당 없음 (정책 문서).
- **E2E**: 본 정책이 규율하는 것이 곧 E2E 채널 배분. 검증 = web-e2e-playwright impl PR 1 머지 후 rev 가 scope:web PR 에서 C1~C5 를 Playwright 로, C6~C10 을 RestAssured/curl 로 분리 적용하고 PR 코멘트에 채널을 명시하는지 1 사이클 sanity.
- **무회귀 sanity**: 기존 web curl 검증을 폐기한 항목(C1~C4)이 브라우저 QA 로 동등 이상 커버됨을 1회 대조 (mutation: 페이지 깨짐 주입 시 브라우저 QA 가 실제 fail 하는지).
- **mock 전략**: C11(BE 의존 FE 플로우)은 Playwright `page.route()` mock — `web-e2e-playwright.md §7` 소관.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 기존 web curl/DOM grep 검증의 **폐기 시점** — config 도입 즉시 vs 브라우저 QA 동등성 1 사이클 검증 후 | (a) 동등성 검증 후 폐기(권장 — 무회귀) / (b) config 도입 즉시 | @mobruji-maestro / impl PR 1 전 |
| Q2 | C11(BE 의존 FE 플로우) 단계 2 — live BE 실호출 vs `page.route()` mock 유지 | (a) 단계 1=mock / 단계 2=live 실호출(권장) / (b) 양 단계 mock | @mobruji-maestro / impl PR 1 전 |
| Q3 | C5(콘솔/런타임 에러)를 머지 차단 기준으로 승격할지 (warning vs error 만) | (a) error 만 차단(권장) / (b) warning 포함 / (c) 비차단 보고만 | @mobruji-maestro / impl PR 1 전 |
| Q4 | 본 정책 매트릭스를 `rev-e2e-2-stages.md` 안으로 흡수 vs 별 spec(본 spec) 유지 | (a) 별 spec 유지(권장 — 정책 SoT 분리) / (b) rev-e2e-2-stages §3 안 흡수 | @mobruji-maestro / 본 spec 머지 시점 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-31: 초안 작성 (status=draft). directive `rev 사이클 실 브라우저 기반 FE QA 단계 도입 spec`(replacement policy) 대응. 핵심 결정 3건: (1) **대체 정책** — 브라우저로 렌더·구동 가능한 사용자 가시 행동(C1~C5/C11)은 브라우저 QA 로 대체, API 계약·데이터·비가시 동작(C6~C10)은 기존 E2E 잔존(§5-4 매트릭스). (2) **도구 = Playwright** (Puppeteer 제외 — web-e2e-playwright 와 단일 스위트 공유 + 내장 runner/assertion + trace, §5-3). (3) **통합 대상 = 2 단계 모델** (directive "3단계" 정정 — 단계 3 폐기). 본 spec 은 *정책 SoT* 로 한정, 스위트(web-e2e-playwright)·실행 환경(rev-browser-e2e-env)·단계 정의(rev-e2e-2-stages)는 cross-ref 로 중복 회피.

## 10) 관련

- 자매 spec `docs/features/web-e2e-playwright.md` (`web/e2e/` 스위트 + config + CI `web-e2e.yml` — 도입 예정)
- 자매 spec `docs/features/rev-browser-e2e-env.md` (rev 워크트리 실행 환경 + `PLAYWRIGHT_BASE_URL` 단계별 override + 도구 Playwright vs Cypress 제외)
- 자매 spec `docs/features/rev-e2e-2-stages.md` (🟡 Pre-merge / 🔵 Post-merge 2 단계 SoT — §3 갱신 대상)
- `docs/features/rev-qa-protocol.md` (`rev단계N: 🟢/🟡/🔴` 코멘트 + Discord push 차등)
- `docs/features/visual-regression-ci.md` (시각 회귀 baseline — C2 자동비교 소관, 본 spec 범위 밖)
- `docs/features/recommendation-p95-regression-guard.md` (C10 성능 잔존 채널)
- 런북 `docs/ai-harness/07-testing-guide.md` (E2E 룰 — C6/C7 RestAssured 잔존 SoT)
- 런북 `docs/ai-harness/actors/sub-agent.md §2-rev` (rev 2 단계 + 채널 분리 1줄 추가 대상)
- 워크플로우 `.github/workflows/spec-status-check.yml` (frontmatter 형식·status enum 강제 — 본 spec 8 필드 게이트)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-verify-and-iterate]]
