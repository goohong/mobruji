# web/e2e — Playwright smoke 시나리오

> spec SoT: [`docs/features/web-e2e-playwright.md`](../../docs/features/web-e2e-playwright.md)
> 이슈: #1191 / ADR-0018 design tokens 시각 회귀 가드 layer.

본 디렉토리는 mobruji web 의 Playwright e2e smoke 시나리오를 담는다. Vitest 단위 테스트 (`app/**/*.test.tsx`) 와 격리되어 있고, 페이지 라우트 1:1 매핑 + critical CTA + 다크/라이트 토큰 회귀 가드 목적.

## 디렉토리 구성

```
web/
├── playwright.config.ts   # Playwright 공식 config (chromium-only, baseURL=http://localhost:3000)
└── e2e/
    ├── README.md          # 본 문서
    └── home.spec.ts       # S1 (홈 신규 사용자)  ← impl PR 1 범위
```

후속 impl PR 2 에서 S2-S7 (`recommend.spec.ts` / `songs.spec.ts` / `voice-range.spec.ts` / `tokens-regression.spec.ts`) 추가 예정.

## 로컬 실행

```bash
# 1) 의존성 설치 (최초 1회)
npm install

# 2) Playwright chromium 바이너리 설치 (최초 1회)
npx playwright install --with-deps chromium

# 3) Next.js 서버 기동 (별 터미널)
npm run dev        # 개발 모드 — fast feedback
# 또는
npm run build && npm run start   # 프로덕션 빌드 모드 — CI 와 동일 조건

# 4) e2e 실행
npm run test:e2e             # headless
npm run test:e2e:ui          # Playwright UI 모드 (디버깅 / 시나리오 작성 보조)
```

## 환경 변수

| 변수 | 기본값 | 의미 |
|---|---|---|
| `PLAYWRIGHT_BASE_URL` | `http://localhost:3000` | smoke 가 hit 할 서버 URL. CI / production smoke 시 override |
| `CI` | (unset) | `1` 일 때 retries=1 / workers=1 / reporter=github+html / forbidOnly=true |

## CI 통합 (예정)

impl PR 3 (별도 PR) 에서 `.github/workflows/web-e2e.yml` 신설. spec §5-4 옵션 A — `web-ci.yml` 와 분리된 별도 workflow + Playwright browser binary 캐시 + HTML report artifact 업로드.

본 PR (impl 1) 단계에서는 CI 자동 trigger 가 없다 — 로컬 실행 + 후속 PR 에서 CI 게이트 도입.

## rev sub-agent 통합 (예정)

`docs/features/rev-e2e-3-stages.md §3` 갱신은 impl PR 1 머지 후 별도 docs PR 예정. config 존재 시 rev 가 `cd web && npx playwright test --reporter=line` 실행하는 룰 명문화.

## 시나리오 작성 가이드

- selector: `data-testid` 또는 `getByRole({ name })` 우선. 텍스트 의존 fragile selector 금지.
- BE 의존 시나리오 (예: `/recommend` 가 `/api/v1/recommendations` 호출) → Playwright `page.route()` 로 mock (spec §7).
- 다크/라이트 모드 토큰 검증은 `toHaveCSS('background-color', ...)` 직접 비교 — `toHaveScreenshot` (visual snapshot) 은 비활성 (spec Q1 결정 (b)).
- 시나리오 1건당 페이지 라우트 1:1.

## 관련

- spec: `docs/features/web-e2e-playwright.md`
- ADR: `docs/decisions/0018-design-tokens.md`
- 룰: `docs/ai-harness/07-testing-guide.md` (§E2E)
- rev 통합: `docs/features/rev-e2e-3-stages.md`
