---
feature: web/ Playwright e2e 인프라 도입 (design tokens 시각 회귀 가드)
slug: web-e2e-playwright
status: draft
owner: @mobruji-maestro
scope: web
related_issues: [1191, 1044]
related_prs: []
last_reviewed: 2026-05-28
---

# web/ Playwright e2e 인프라 도입 (design tokens 시각 회귀 가드)

## 1) 개요 (What / Why)

ADR-0018 (design tokens) 단계 4 마이그레이션 PR 11+ 가 진행되며 hardcode `bg-zinc-50` /
`text-3xl` / `rounded-2xl` 호출을 토큰 (`bg-bg-subtle` / `text-(--text-h1)` / `rounded-(--radius-lg)`) 으로
swap 한다. 단위 테스트 (`app/tokens.test.ts`) 는 토큰 값 자체와 변수 명칭만 검증할 뿐
실제 렌더링 결과 (라이트/다크 모드, 페이지 레이아웃, CTA 배치) 의 시각 회귀를 가드하지 못한다.

rev sub-agent #1174 단계 3 (당시 명, 2026-05-30 폐기) e2e 시도가 Playwright 미설정 사유로 no-op pass 처리되었다 (rev-e2e-2-stages
§3 룰: e2e 불가능 = 단계 1/2 no-op pass). 후속 design tokens PR 다수가 page 변경을 동반하므로
시각 회귀 위험이 누적된다.

대상 액터: fe sub-agent / rev sub-agent / nmae (머지 결정).

해결: `@playwright/test` 도입 + `web/e2e/` 디렉토리 + smoke 5-7건 + GitHub Actions 통합 +
rev 🟡 Pre-merge review (단계 1) / 🔵 Post-merge audit (단계 2) trigger 명문화 (단계 3 폐기 — `rev-e2e-2-stages.md §1-1`). 본 spec 은 도입 사유 / 범위 / 시나리오 / CI 통합 옵션 비교 /
후속 PR 분담 만 정의하고 실제 구현은 후속 PR (impl PR 1-3) 에서 처리한다.

## 2) 사용자 시나리오

본 기능의 직접 사용자 = 개발 사이클 액터 (fe / rev / nmae). end-user 가시화는 없다.

1. **fe sub-agent — design tokens PR 작성 시**: token swap 한 page 의 라이트/다크 모드
   smoke 가 로컬에서 통과하는지 `npx playwright test` 로 확인한 뒤 PR 생성.
2. **rev sub-agent — 🟡 Pre-merge review (단계 1) / 🔵 Post-merge audit (단계 2) audit 시**: 라벨 / 코드 변경 분석 → e2e 가능 판정 →
   Playwright 실행 → smoke 시나리오 5-7건 통과 시
   `reviewed:claude` / `rev-post-merge-pass` 라벨 부여. (단계 3 폐기 — `rev-e2e-2-stages.md §1-1`)
3. **GitHub Actions — PR open / synchronize**: `web-ci.yml` 또는 별도 job 이 Playwright smoke 실행 +
   결과 PR check 보고. red 시 머지 차단.

## 3) 요구사항

### 기능 요구사항

- [ ] `web/e2e/` 디렉토리 신설 (Playwright config + smoke 시나리오 5-7건)
- [ ] `@playwright/test` devDependency 추가 (impl PR 1)
- [ ] `web/playwright.config.ts` — base URL / browsers (chromium first, optional firefox/webkit) /
      retries / reporters (HTML + GitHub Actions annotations)
- [ ] smoke 시나리오 5-7건 (§5-3 목록) — 페이지 로드 + critical CTA + 다크/라이트 토큰 회귀 가드
- [ ] CI 통합 — GitHub Actions job 추가 (§5-4 옵션 비교 + 권장)
- [ ] `rev-e2e-2-stages.md` §3 갱신 — Playwright trigger 조건 명문화 (config 존재 시 실행 / 부재 시 기존 no-op pass)
- [ ] 로컬 실행 가이드 — `npm run test:e2e` script + AGENTS.md / CLAUDE.md fe 룰 1줄 안내
- [ ] **시각 회귀 스냅샷 (Visual snapshot) 도입 여부 결정 (Q1)** — `toHaveScreenshot` 활성 / 비활성

### 비기능 요구사항

- **CI 실행 시간**: smoke 5-7건 chromium-only 기준 **3분 이하**. firefox/webkit 추가 시 별도 nightly job.
- **유지보수성**: 시나리오는 페이지 라우트 1:1 매핑 (페이지 추가 시 시나리오 1건만 추가).
  selector 는 `data-testid` 우선 (텍스트 의존 fragile selector 금지).
- **재현성**: `actions/cache@v4` 로 Playwright browser binary 캐시. 캐시 부재 시 install ~30초 허용.
- **rev 통합**: rev sub-agent 가 Playwright config 존재 여부 = e2e 가능 판정 변경 조건으로 인식.
  rev-e2e-2-stages §3 룰과 trigger 명시적 연결.

## 4) 범위 / 비범위 (중요)

### 포함

- Playwright (`@playwright/test`) 도입 + chromium browser
- smoke 시나리오 5-7건 (홈 / recommend / songs / voice-range / 다크 모드 토큰 가드)
- GitHub Actions 통합 (web-ci.yml 확장 또는 별도 web-e2e.yml job)
- rev-e2e-2-stages §3 trigger 조건 갱신 (config 존재 시 Playwright 실행)
- 로컬 실행 가이드 (`npm run test:e2e`)

### 제외 (Out of Scope)

- **시각 회귀 스냅샷 자동 비교 (Percy / Chromatic / Argos)** — 별도 ADR / spec. 본 spec 은
  Playwright `toHaveScreenshot` (네이티브) 활성 여부만 결정 (Q1).
- **Visual regression test (VRT) 전용 third-party** — 외부 SaaS 의존성 + 비용 검토 필요. 단계적 도입.
- **모바일 viewport (iPhone / Android) 시나리오** — 단계 도입. 본 spec 은 desktop chromium 기본.
- **accessibility (a11y) full audit** — `axe-core` 가 이미 vitest 단위 테스트에 도입되어 있음 (package.json `axe-core: ^4.11.4`).
  Playwright a11y 통합 (`@axe-core/playwright`) 은 별도 PR.
- **백엔드 의존 E2E (RestAssured + Playwright 통합)** — 본 spec 은 frontend smoke 만.
  백엔드 의존 시나리오 (예: `/recommend` 가 BE `/api/v1/recommendations` 호출 검증) 는
  단계 도입 (mock + msw 또는 NCP dev live deploy 대상).
- ~~**production live URL 대상 단계 3 smoke 자동화 deploy 통합**~~ — **2026-05-30 폐기** (`rev-e2e-2-stages.md §1-1` production 환경 부재). 향후 production 환경 신설 시 부활 — `[[project_rev_stage_3_prod_revival]]` cross-ref.

## 5) 설계

### 5-1) 도메인 모델

해당 없음. 본 기능은 인프라 / 테스트 도구 도입이며 도메인 엔티티 변경 없음.
관련: `docs/ai-harness/07-testing-guide.md` (E2E 룰), `docs/features/rev-e2e-2-stages.md` (rev 단계).

### 5-2) API 엔드포인트

해당 없음. 본 기능은 frontend smoke 테스트 인프라이며 신규 API 도입 없음.

### 5-3) 외부 연동 — Playwright 도입 옵션

| 항목 | 선택 |
|---|---|
| 패키지 | `@playwright/test` (Playwright 공식 test runner) |
| Browser | chromium first (CI 기본). firefox / webkit 은 nightly job 옵션 |
| Reporter | `html` (local) + `github` (CI annotations) |
| Config 파일 | `web/playwright.config.ts` |
| Test dir | `web/e2e/` (Vitest 단위 테스트 `app/**/*.test.tsx` 와 격리) |
| 로컬 script | `web/package.json` scripts — `test:e2e` / `test:e2e:ui` (UI mode) |

**smoke 시나리오 목록 (5-7건)**:

| # | 시나리오 | 페이지 | 검증 |
|---|---|---|---|
| S1 | 홈 로드 + 측정 안 한 사용자 진입 경로 | `/` | 페이지 200, 페르소나 카드(`내 목소리부터 알아보기` → `/voice-range/auto`) + `직접 입력으로 시작`(→ `/voice-range`) 존재, 콘솔 에러 0건 |
| S2 | 홈 로드 + 측정 한 사용자 CTA | `/` (localStorage seed) | `text=추천 받기` CTA 존재, 음역대 요약 표시 |
| S3 | recommend 페이지 로드 | `/recommend` | 페이지 200, 추천 목록 영역 렌더, 콘솔 에러 0건 |
| S4 | songs 목록 페이지 로드 | `/songs` | 페이지 200, 곡 카드 1+ 렌더 또는 empty state |
| S5 | voice-range 측정 진입 | `/voice-range` | 페이지 200, "측정 시작" CTA, 마이크 권한 안내 표시 |
| S6 | 다크 모드 토큰 회귀 가드 | `/` (prefers-color-scheme: dark) | `body` background = `--bg-base` 다크값 (`rgb(9, 9, 11)`), text = `--text-primary` 다크값 (`rgb(250, 250, 250)`) |
| S7 (선택) | 라이트 모드 토큰 회귀 가드 | `/` (prefers-color-scheme: light) | `body` background = `rgb(255, 255, 255)`, text = `rgb(24, 24, 27)` |

**S6/S7 의의**: ADR-0018 token swap 시 `--bg-base` / `--text-primary` 가 의도치 않게 변경되면
다크/라이트 양쪽 visual contract 깨짐 → smoke 가 실패. tokens.test.ts 단위 테스트는 변수 값만
검증할 뿐 실제 body 적용을 검증하지 않으므로 본 smoke 가 보완 layer.

**Visual snapshot (`toHaveScreenshot`) — Q1 결정 대기**:
- (a) 활성 — `web/e2e/__snapshots__/` 디렉토리 + flaky tolerance threshold (예: `maxDiffPixelRatio: 0.02`)
- (b) 비활성 — DOM/CSS 검증만 (`expect(locator).toHaveCSS('background-color', ...)`)
- 권장 = (b). 사유: snapshot 은 디바이스/폰트 렌더링 차이로 flaky. token 회귀 가드 목적은 `toHaveCSS` 직접 검증으로 충분. snapshot 도입은 별도 spec.

### 5-4) CI 통합 옵션 비교

| 옵션 | 장점 | 단점 | 권장 |
|---|---|---|---|
| **(A) 별도 job `web-e2e.yml`** | lint/typecheck/build 와 격리 → 실패 디버깅 용이 / browser cache 독립 / e2e flaky 시 다른 check 영향 0 | 별도 workflow 파일 / `web-ci.yml` 과 trigger path 중복 / Node setup 중복 | **선택** |
| (B) `web-ci.yml` 안 matrix 추가 | 단일 workflow 유지 / cache 공유 | matrix expansion → 모든 step (lint/typecheck/test/build/e2e) 중복 실행 / e2e flaky 시 lint check 도 같이 red |  |
| (C) `web-ci.yml` 안 별도 job (matrix 없이) | 단일 workflow 안 분리 / lint 와 격리 | workflow 가 길어짐 / cache key 충돌 우려 |  |

**(A) 별도 job 권장**:
- e2e 는 lint/typecheck 와 본질 다른 layer — 격리가 유지보수성 ↑
- Playwright browser binary 캐시 (`~/.cache/ms-playwright`) 는 lint/typecheck 캐시와 무관 → 독립 cache key
- `paths:` trigger 동일 (`web/**`) → 양 workflow 가 같은 PR 에서 모두 trigger 되지만 다른 job 으로 분리되어 GitHub Actions UI 에서 인식 명확

**`web-e2e.yml` 스켈레톤** (impl PR 3 에서 작성):

```yaml
name: Web E2E - mobruji
on:
  pull_request:
    branches: [ "develop", "main" ]
    types: [ opened, synchronize, reopened ]
    paths:
      - 'web/**'
      - '.github/workflows/web-e2e.yml'
permissions:
  contents: read
  checks: write
  pull-requests: write
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
jobs:
  e2e-smoke:
    runs-on: ubuntu-22.04
    defaults: { run: { working-directory: web } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm', cache-dependency-path: web/package-lock.json }
      - run: npm ci
      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('web/package-lock.json') }}
      - run: npx playwright install --with-deps chromium
      - run: npm run build
      - run: npm run test:e2e
      - name: Upload report
        if: always()
        uses: actions/upload-artifact@v4
        with: { name: playwright-report, path: web/playwright-report/, retention-days: 7 }
```

### 5-5) DB 마이그레이션

해당 없음.

### 5-6) 프론트엔드 화면

해당 없음. 본 기능은 화면 변경 없음. 단 `data-testid` 추가가 smoke selector 안정성을 위해
일부 page 에 필요할 수 있다 (홈 CTA, voice-range 측정 시작 버튼 등). 이는 impl PR 2 에서 처리.

### 5-7) rev sub-agent 통합 — `rev-e2e-2-stages.md` §3 갱신

본 spec 도입 시 `docs/features/rev-e2e-2-stages.md` §3-1 의 다음 줄:

```
- scope:web → Playwright headless 또는 NCP dev 직접 호출
```

을 다음으로 명문화 갱신한다:

```
- scope:web:
  - `web/playwright.config.ts` 존재 + `web/e2e/` 시나리오 1건+ → **Playwright 실행 의무**
    (`cd web && npx playwright test --reporter=line`)
  - config 부재 → 기존 no-op pass (🟡 Pre-merge review 라벨만, 🔵 Post-merge audit skip)
- scope:backend → RestAssured E2E 또는 curl + 응답 검증
```

~~§3-3 (단계 3 release 후) 도 같은 trigger 명문화~~ — **2026-05-30 폐기** (단계 3 폐기, production 환경 부재). 향후 production 환경 신설 시 부활 — `[[project_rev_stage_3_prod_revival]]`.

본 갱신은 impl PR 1 (devDep + dir + smoke 1건) 머지 후 별도 `type:docs scope:infra` PR
또는 본 spec PR 안 묶음 (자율 결정 — impl PR 1 머지 시점에 함께 처리 권장).

## 6) 작업 분할 (예상 PR 리스트)

- [x] **본 PR (type:docs scope:web)**: `docs/features/web-e2e-playwright.md` spec 신설
  - 옵션 A: `rev-e2e-2-stages.md` §3 갱신 묶음 (단일 PR)
  - 옵션 B: 별도 docs PR 분리 (impl PR 1 머지 후) — **권장**: spec 합의 후 trigger 갱신
- [ ] **impl PR 1 (type:feat scope:web)**: devDependency + `web/e2e/` dir + Playwright config + smoke S1 1건
  - `web/package.json` devDep `@playwright/test` 추가
  - `web/playwright.config.ts` 신설
  - `web/e2e/home.spec.ts` 신설 (S1)
  - `npm run test:e2e` script 추가
  - `.gitignore` `playwright-report/` `test-results/` 추가
  - **보호 영역** (정보성, 라벨 의무 폐지 2026-05-28 — rev sub-agent 가 review 대행): `web/package.json` / `web/package-lock.json` 변경 — rev 사이클이 추가 신중도 가중
- [ ] **impl PR 2 (type:feat scope:web)**: 나머지 smoke 시나리오 (S2-S6/S7)
  - `web/e2e/recommend.spec.ts` (S3)
  - `web/e2e/songs.spec.ts` (S4)
  - `web/e2e/voice-range.spec.ts` (S5)
  - `web/e2e/tokens-regression.spec.ts` (S6 + S7)
  - 필요 시 page 컴포넌트에 `data-testid` 추가 (최소화)
- [ ] **impl PR 3 (type:feat scope:infra)**: CI 통합
  - `.github/workflows/web-e2e.yml` 신설 (§5-4 옵션 A)
  - `actions/cache@v4` Playwright browser binary 캐시
  - Playwright HTML report artifact upload
  - **보호 영역** (정보성, 라벨 의무 폐지 2026-05-28): `.github/workflows/**` — rev 사이클이 추가 신중도 가중
- [ ] **docs PR (type:docs scope:infra)**: rev-e2e-2-stages §3 trigger 명문화 (impl PR 1 머지 후)
- [ ] **ADR 필요 여부 검토 (선택)**: §9 결정 로그 — Playwright = 외부 도구 도입. ADR-NNNN 신설 권고는 본 spec 자체 결정으로 갈음 (Vitest / RestAssured 와 같은 단순 도구 도입). 별도 ADR 불요.

## 7) 테스트 전략

본 spec 자체는 인프라 도입이므로 spec 검증 = "후속 impl PR 의 smoke 가 실제 CI 에서 통과하는가".

- **impl PR 1**: 로컬 `cd web && npm install && npx playwright install chromium && npm run test:e2e` 통과 + CI green
- **impl PR 2**: 5-7건 모두 CI green + 다크 모드 토큰 회귀 가드 (S6) 가 `--bg-base` 변경 시 실제 fail 인지 sanity check (token swap mutation test 1회)
- **impl PR 3**: GitHub Actions 실제 trigger + report artifact 업로드 확인 + cache hit ratio 측정 (2번째 PR 부터 cache 활용)
- **rev 통합 검증**: impl PR 1 머지 후 rev sub-agent 가 다음 PR 에서 `rev-e2e-2-stages.md` 갱신 룰을 따라 Playwright 실제 실행하는지 확인 (rev-queue.sh 첫 액션 + rev launch prompt)

### mock 전략

- **BE 의존성**: 본 smoke 는 BE 의존 페이지 (`/recommend` = `/api/v1/recommendations`) 호출 mock 전략 필요.
  - 옵션 1: `msw` (Mock Service Worker) — 이미 vitest 단위 테스트 패턴 일관
  - 옵션 2: Playwright `page.route()` API — Playwright 네이티브
  - 본 spec 권장 = 옵션 2 (Playwright 네이티브, 별 패키지 의존 없음). 단 BE 의존 시나리오는 impl PR 2 에서 결정 (S3 `/recommend` 가 BE 호출 시 mock 강도).

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | Visual snapshot (`toHaveScreenshot`) 활성 여부 | (a) 활성 + threshold / (b) 비활성, `toHaveCSS` 만 | @goohong / impl PR 1 작성 전 |
| Q2 | CI 통합 옵션 — 별도 workflow vs `web-ci.yml` 안 별 job | (A) 별도 / (B) matrix / (C) 안 별 job | @goohong / impl PR 3 작성 전. 권장 = (A) |
| Q3 | browser matrix — chromium-only vs +firefox/webkit | (a) chromium only / (b) chromium + firefox / (c) 3-browser nightly | @goohong / impl PR 1 작성 전. 권장 = (a) — flaky risk 최소 |
| Q4 | BE mock 전략 — msw vs Playwright `page.route()` | (a) msw / (b) page.route() | @goohong / impl PR 2 작성 전. 권장 = (b) |
| Q5 | rev-e2e-2-stages §3 갱신 — 본 spec PR 묶음 vs 별도 docs PR | (A) 본 PR 묶음 / (B) impl PR 1 머지 후 별도 PR | @goohong / 본 spec 머지 시점. 권장 = (B) |
| Q6 | ADR 신규 필요 여부 (Playwright = 외부 도구) | (a) ADR-NNNN 신설 / (b) 본 spec 결정으로 갈음 | @goohong / 본 spec draft 시점. 권장 = (b) — Vitest/RestAssured 와 동일 단순 도구 도입 |

## 9) 결정 로그

- 2026-05-28: 초안 작성 (status=draft). 이슈 #1191 대응. rev #1174 단계 3 (당시 명) no-op pass 사유 (Playwright 미설정) 박제 및 후속 design tokens PR 11+ 시각 회귀 가드 필요성 명문화.
- **2026-05-30 (PR rev2s-2 propagation cleanup)**: `rev-e2e-3-stages.md` → `rev-e2e-2-stages.md` rename + 단계 3 폐기. 본 spec §1 / §2 / §3 / §4 / §5-7 / §6 / §7 / §8 / §10 의 `rev-e2e-3-stages` reference → `rev-e2e-2-stages` 정정. 단계 3 (release 후 production smoke) 박제 모두 폐기 marker — production 환경 신설 시 부활 가능 (`[[project_rev_stage_3_prod_revival]]`).

## 10) 관련

- 이슈 #1191 (본 spec 트리거), #1044 (UI/UX 4단계 audit)
- ADR `docs/decisions/0018-design-tokens.md` (마이그레이션 spec)
- 기존 spec `docs/features/ui-ux-redesign.md` (단계 4 PR 11+ 마이그레이션 계획)
- 기존 spec `docs/features/rev-e2e-2-stages.md` (§3 trigger 갱신 대상)
- 테스트 가이드 `docs/ai-harness/07-testing-guide.md` (§86 "E2E: Playwright (도입 시점은 추후 ADR)" — 본 spec 으로 ADR 갈음)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-spec-frontmatter-required]]
