---
feature: 다크모드·디자인 토큰 swap 회귀 가드 — Playwright visual regression CI
slug: visual-regression-ci
status: draft
owner: @goohong
scope: infra
related_issues: [1044, 1283]
related_prs: [1305, 1309, 1319]
last_reviewed: 2026-05-29
---

# 다크모드·디자인 토큰 swap 회귀 가드 — Playwright visual regression CI

> **status: draft.** ADR-0026 (`docs/decisions/0026-visual-regression-ci.md`, proposed, 2026-05-29) 의 accepted 격상 트리거 spec. ADR-0018 (design tokens) 단계 4 swap 시리즈의 다크모드 회귀 자동 감지 가드. 본 spec 은 도구 / 절차 / baseline 룰 만 박는다 — 실제 baseline `.png` 파일 / Playwright config / workflow yaml 은 후속 PR (§6) 분할.

## 1) 개요 (What / Why)

- **What**: Playwright screenshot 기반 visual regression CI 를 단계 1 (`rev-gate.yml` 이전 신규 workflow `visual-regression.yml`) + 단계 2 (dev 환경 nightly) 에 도입. PR diff 가 화면에 영향을 주면 자동으로 baseline 과 pixel diff 비교 → 0.1% 초과 시 fail.
- **Why**: ADR-0018 단계 4 swap 시리즈 (color / typography / spacing) 가 다크모드 대비 부족 / 일부 컴포넌트 토큰 미적용 사고를 일으킬 수 있고, rev sub-agent 가 단계 1 에서 screenshot 검증을 매뉴얼로 수행하면 학습 의존 (망각 가드 부재 — ADR-0019 정신 위반). 코드 강제 = 회귀 catch rate ↑ + rev 부담 ↓.
- **대상 actor**: fe sub-agent (baseline 갱신 PR 작성자) + rev sub-agent (baseline drift 의도 명시 review) + CI (자동 fail / 차단).

## 2) 사용자 시나리오

- **시나리오 1 (UI 변경 PR, fe sub-agent)**: fe sub-agent 가 design tokens swap PR 생성 → CI 가 자동으로 대상 페이지 screenshot 캡처 → baseline 비교 → diff > 0.1% 시 fail. PR body 에 `## visual baseline update` 섹션이 비어 있으면 (의도 명시 안 함) rev 가 `🔴 시각 회귀 의심` 코멘트.
- **시나리오 2 (baseline 갱신 PR, fe sub-agent)**: fe sub-agent 가 의도된 디자인 변경 시 `npx playwright test --update-snapshots` 실행 → baseline `.png` 갱신 commit + PR body `## visual baseline update` 섹션에 N 페이지 갱신 / 사유 명시 → rev 가 사유 검토 후 통과.
- **시나리오 3 (nightly 회귀, 단계 2 rev sub-agent)**: dev 서버 deploy 직후 nightly cron 이 시각 회귀 시나리오 자동 실행 → diff 발견 시 rev sub-agent 가 `rev-post-merge-regression:visual` 라벨 + DIGEST push (`rev-qa-protocol §5-9-3` 단계 2 보고 형식).

## 3) 요구사항

### 기능 요구사항

#### 3-1) 대상 페이지 (5-10 개)

ADR-0018 단계 4 swap 영향 핵심 페이지:

| 라우트 | 뷰포트 | 모드 | 비고 |
|---|---|---|---|
| `/` | 1280x720, 375x812 | light + dark | 홈 |
| `/voice` | 1280x720, 375x812 | light + dark | 음역 입력 |
| `/song/<id>` (`/song/seed-1`) | 1280x720, 375x812 | light + dark | 곡 상세 |
| `/history` | 1280x720, 375x812 | light + dark | 추천 이력 (PR #1263 swap 완료) |
| `/recommendations` | 1280x720, 375x812 | light + dark | 추천 결과 |
| `/likes` (옵션) | 1280x720 | light + dark | 좋아요 |

- viewport 2 종 (desktop 1280, mobile 375) × mode 2 종 (light, dark) = 페이지당 4 baseline.
- 페이지 5 개 × 4 baseline = 20 baseline `.png` (옵션 페이지 포함 시 24개).

#### 3-2) baseline 관리

- **저장소**: git (직접 commit) — 최초 도입 단계 LFS 사용 X. baseline 1 개 평균 30-80 KB × 24 = 약 1-2 MB / 갱신 1 회. 분기당 swap 시리즈 10-20 회 갱신 시 누적 10-40 MB / 분기 — repo 비대화 (`§8 Q1`). LFS 전환은 누적 100 MB 도달 시 별 마이그레이션 ADR 트리거.
- **갱신 메커니즘 (proposed: ADR-0026 §Decision 2 후보 a 채택)**: `npx playwright test --update-snapshots` 명시 PR 만 baseline 갱신 + PR body `## visual baseline update` 섹션 의무.
- **PR body 의무 양식 (§3-3 참조 + PR B 별도 PR 로 template 갱신)**:
  ```text
  ## visual baseline update
  - baseline 변경 없음
  ```
  또는
  ```text
  ## visual baseline update
  - N 페이지 baseline 갱신
  - 사유: ADR-0018 단계 4 color swap (예: `--zinc-` → `--neutral-`)
  - 영향 페이지: /, /history, /voice (light + dark, desktop + mobile)
  ```
- **scope 조건**: `type:feat scope:web` PR 만 의무 부착. 그 외 scope (예: `scope:infra`, `scope:song`) 는 옵션 — workflow auto-label.yml 가 `scope:web` PR 에서 visual diff 발생 시 본 섹션 부재를 fail 처리 (별 PR `visual-baseline-pr-body-check.yml` workflow 신설 권고).

#### 3-3) drift threshold

- **proposed**: pixelmatch `threshold: 0.1` (0.1% pixel 차이 시 fail). 첫 baseline 확보 후 1 주일 운영 결과 보고 후 조정 (§8 Q3).
- **SSIM 검토**: SSIM (Structural Similarity Index) 은 pixelmatch 보다 anti-aliasing / font rendering 차이에 robust 하나 CI 셋업 복잡도 ↑. 1차 도입은 pixelmatch — SSIM 전환은 false-positive 5% 초과 시 별 ADR.
- **OS font rendering 가드**: Playwright Docker (`mcr.microsoft.com/playwright:v<X>`) 통일 — 로컬 / CI 환경 OS 차이 제거.

#### 3-4) rev 자율 판단 룰

baseline drift 발견 시 rev sub-agent 의 판단:

| 상황 | rev 결정 | 라벨 / 코멘트 |
|---|---|---|
| diff > 0.1% + PR body `## visual baseline update` 명시 (예: "N 페이지 baseline 갱신, 사유 ADR-0018 swap") | 의도된 변경 — 사유 합리성 검토 후 통과 | `reviewed:claude` 라벨 + `rev단계1: 🟢 visual baseline 갱신 의도 확인` 코멘트 |
| diff > 0.1% + PR body 섹션 부재 또는 "baseline 변경 없음" | 회귀 의심 — `reviewed:claude` 라벨 부착 차단 | `rev단계1: 🔴 시각 회귀 의심 — PR body \`## visual baseline update\` 섹션 누락 또는 회귀 발생` 코멘트 + fe sub-agent 에 root cause + PR body 보강 위임 |
| diff 0% (no change) | 통과 | `rev단계1: 🟢 visual diff 없음` (코멘트 생략 가능) |

본 spec 의 §3-4 표는 `docs/ai-harness/actors/sub-agent.md §2-rev` 의 단계 1 e2e 룰 확장 — 본 spec 머지 후 sub-agent.md 에 1 줄 reference 추가 (별 PR §6 PR 4).

#### 3-5) CI 실행 위치

| 위치 | 실행 시점 | workflow | 단계 |
|---|---|---|---|
| **PR (`scope:web` 만)** | PR push / synchronize | `.github/workflows/visual-regression.yml` (신설) | 단계 1 — 머지 차단 게이트 |
| **dev 서버 nightly** | cron `0 18 * * *` (KST 03:00) | `.github/workflows/visual-regression-nightly.yml` (신설) | 단계 2 — 머지 후 회귀 감시 |
| **production smoke** | release 직후 manual trigger | 단계 3 — production 환경 구축 후 (deployment-infrastructure.md 의존) | 단계 3 — `rev-direct-qa-extension §3 후보 C` 후속 |

### 비기능 요구사항

- **CI runtime**: 단계 1 visual regression workflow wall-clock < 3 분 (Playwright 1 페이지 캡처 평균 2-5 초 × 24 baseline ≈ 48-120 초 + setup overhead 60-90 초).
- **artifact 저장 비용**: GitHub Actions artifact 90 일 보존 default — diff `.png` (fail 시만) 평균 50-200 KB × 24 = 1-5 MB / fail PR. 월 100 fail PR 가정 시 100-500 MB / 월 (free tier 한도 2 GB 안).
- **false-positive ↓**: Docker 통일 + `Animations: disabled` + `fonts ready promise wait` 으로 < 5% 목표.
- **graceful 실패**: Playwright Docker pull 실패 / dev 서버 down 시 workflow skip + DIGEST push (`rev-skip-env-down:visual-regression` 라벨, `rev-qa-protocol §5-9` 룰 reference).

## 4) 범위 / 비범위

### 포함

- 단계 1 (PR 차단 게이트) 의 visual regression workflow 도입 절차.
- baseline 관리 룰 (ADR-0026 §Decision 2 후보 a — `--update-snapshots` 명시 PR 의무).
- PR body `## visual baseline update` 섹션 의무 양식 (별 PR B 가 template 갱신).
- drift threshold (proposed 0.1%) + OS font 가드 (Playwright Docker).
- rev 자율 판단 표 (§3-4).

### 제외 (Out of Scope)

- **Playwright e2e infra 신설**: PR #1200 (Playwright headless e2e infra) 머지 의존 — 본 spec 은 visual regression workflow 만, e2e 시나리오는 `rev-direct-qa-extension.md` 후보 B 별 spec.
- **baseline LFS 전환**: 초기 도입은 git 직접 commit. 누적 100 MB 도달 시 별 마이그레이션 ADR (`baseline-lfs-migration`).
- **production 환경 visual smoke**: production 환경 구축 (deployment-infrastructure.md Hetzner CX22) 머지 의존 — 단계 3 별 cycle.
- **SSIM 전환**: 1차 pixelmatch 도입 후 false-positive 5% 초과 시 별 ADR.
- **percy / Chromatic SaaS**: ADR-0026 Alternatives (A) 에서 보안 정책 위반 risk 로 채택 X.
- **모바일 디바이스 매트릭스 확장**: viewport 2 종 (1280, 375) 만. iPad / 4K 등은 false-positive ↑ + runtime ↑ — 별 spec.

## 5) 설계

### 5-1) 도메인 모델

- 새 도메인 용어 없음 — `06-domain-model.md §4` 갱신 의무 없음.
- 본 spec 은 infra / 룰 만 — backend / web 도메인 entity 변경 없음.

### 5-2) API 엔드포인트

- 신규 endpoint 없음. Playwright 가 dev 서버 (`http://101.79.20.94/`) 또는 PR branch deploy 환경의 기존 endpoint 만 GET.

### 5-3) 외부 연동

- **Playwright Docker image**: `mcr.microsoft.com/playwright:v1.4x` (PR #1200 머지 시 정확한 버전 확정). public registry — 인증 불요.
- **dev 서버 (NCP)**: `http://101.79.20.94/` — Phase 4 머지 완료, `cd-dev.yml` 가 develop tip 자동 deploy. 단계 2 nightly workflow 가 본 서버 화면 캡처.
- **GitHub Actions artifact storage**: 90 일 보존 default. fail PR 의 diff `.png` 업로드 (검증용).

### 5-4) 데이터 흐름 / 시퀀스

단계 1 (PR 차단 게이트) 흐름:

```
1. fe sub-agent PR push (scope:web)
2. GitHub Actions: visual-regression.yml trigger
3. Playwright Docker pull → web 빌드 → `npm run dev` background
4. 대상 페이지 24 baseline 순차 캡처 → repo 의 기존 baseline `.png` 와 pixelmatch
5a. diff = 0 → workflow success → 다른 단계 1 게이트 (rev-gate.yml) 진행
5b. diff > 0.1% → workflow fail + diff `.png` artifact 업로드 (PR comment 에 artifact URL)
6. rev sub-agent: §3-4 판단 표 적용 → 코멘트 + 라벨
```

baseline 갱신 PR 흐름:

```
1. fe sub-agent 로컬 또는 PR branch 에서 `npx playwright test --update-snapshots`
2. 갱신된 `.png` baseline commit + PR body `## visual baseline update` 섹션 작성 (N 페이지 / 사유)
3. CI visual-regression.yml: 갱신된 baseline 으로 비교 → diff = 0 → pass
4. rev: PR body 사유 검토 → §3-4 판단 표 통과 row → `reviewed:claude` 라벨
```

### 5-5) DB 마이그레이션

- 없음.

### 5-6) 프론트엔드 화면 (해당 시)

- 본 spec 자체는 화면 변경 X. baseline `.png` 가 web/ 하위 (예: `web/tests/visual/__snapshots__/`) 저장.
- 다크모드 toggle: 이미 web 에 구현되어 있음 (ADR-0018 단계 4 swap 대상). Playwright `page.emulateMedia({ colorScheme: 'dark' })` 로 캡처.

## 6) 작업 분할 (예상 PR 리스트)

본 spec 머지 후 후속 PR 분할 (사이클별):

- [ ] **PR 1 (이번 사이클, plan)**: 본 spec 머지 (`docs/features/visual-regression-ci.md` 신설).
- [ ] **PR 2 (별 PR, plan)**: `.github/PULL_REQUEST_TEMPLATE.md` 본문 끝에 `## visual baseline update` 섹션 추가 (조건부 — `type:feat scope:web` 만 의무). ADR-0026 §Decision 2 후보 (a) 검증.
- [ ] **PR 3 (PR #1200 머지 후, fe)**: `.github/workflows/visual-regression.yml` 신설 + Playwright spec (`web/tests/visual/`) + 최초 baseline 24 개 commit. **상세 설계 = §6-PR3 detailed design** (사전 박제 — plan round 12 / 2026-05-29).

#### §6-PR3 detailed design (사전 박제, plan round 12)

> 본 sub-section 은 PR 3 fe sub-agent 가 launch 시 first-class reference. workflow yml 본문 / Playwright config 실제 코드 작성은 PR 3 본 사이클 — 본 spec 은 골격 / 근거 / 룰만.

**1) Playwright workflow yaml 골격** (`.github/workflows/visual-regression.yml`)

```yaml
name: visual-regression

on:
  pull_request:
    types: [opened, synchronize, reopened]
    paths:
      - 'web/**'
      - '.github/workflows/visual-regression.yml'

concurrency:
  group: visual-regression-${{ github.head_ref }}
  cancel-in-progress: true

jobs:
  visual-regression:
    if: contains(github.event.pull_request.labels.*.name, 'scope:web')
    runs-on: ubuntu-latest
    timeout-minutes: 10
    container:
      image: mcr.microsoft.com/playwright:v1.4x-jammy   # PR #1200 머지 시 정확한 tag 확정
    steps:
      - uses: actions/checkout@v4
        with:
          lfs: false                                    # baseline LFS 미사용 (§3-2)
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: 'web/package-lock.json'
      - name: install (preserve symlinks)
        working-directory: web
        run: npm ci --no-audit --no-fund               # symlink 보존 의무 ([[feedback-npm-install-symlink-swap]])
      - name: build web
        working-directory: web
        run: npm run build
      - name: start web (background)
        working-directory: web
        run: npm run start &
      - name: wait for web ready
        run: npx wait-on http://localhost:3000 --timeout 60000
      - name: visual regression test
        working-directory: web
        env:
          CI: 'true'
        run: npx playwright test --config=tests/visual/playwright.config.ts
      - name: upload diff artifacts (on failure)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: visual-diff-${{ github.run_id }}
          path: web/tests/visual/__snapshots__/**/*-diff.png
          retention-days: 30
```

근거:
- `if: contains(... 'scope:web')` — §3-5 단계 1 적용 범위 (scope:web 만). auto-label.yml 가 PR 생성 시 자동 부착하므로 trigger 순서 race 시 retry 로 자연 해소. label 미부착 PR 은 skip = green default (false-positive ↓).
- `concurrency` + `cancel-in-progress: true` — synchronize push 다중 시 이전 run cancel (artifact 비용 ↓).
- `timeout-minutes: 10` — §비기능 wall-clock < 3 분 + 2x 안전 margin.
- container Playwright official image — §3-3 OS font 가드 (Docker 통일).
- `actions/setup-node@v4 cache` — install 시간 단축 (1-2 분 → 20-30 초).
- `npm ci --no-audit --no-fund` — `npm install` 금지 룰 ([[feedback-npm-install-symlink-swap]]), `ci` 만 허용 + lockfile 의무.
- `wait-on` — `npm run start` 가 ready 전 Playwright 실행 시 ECONNREFUSED 가드.
- `retention-days: 30` — §비기능 GitHub Actions free tier 2 GB 한도 안 (90 일 default → 30 일 단축, 1/3 비용).

**2) baseline 디렉토리 구조** (`web/tests/visual/__snapshots__/`)

```
web/tests/visual/
├── playwright.config.ts                      # config (Docker container + viewport matrix)
├── home.spec.ts                              # `/` 캡처
├── voice.spec.ts                             # `/voice` 캡처
├── song-detail.spec.ts                       # `/song/seed-1` 캡처
├── history.spec.ts                           # `/history` 캡처
├── recommendations.spec.ts                   # `/recommendations` 캡처
├── likes.spec.ts                             # `/likes` (옵션) 캡처
└── __snapshots__/
    ├── home.spec.ts-snapshots/
    │   ├── home-desktop-light-chromium-linux.png
    │   ├── home-desktop-dark-chromium-linux.png
    │   ├── home-mobile-light-chromium-linux.png
    │   └── home-mobile-dark-chromium-linux.png
    ├── voice.spec.ts-snapshots/             # 4 baseline
    ├── song-detail.spec.ts-snapshots/       # 4 baseline
    ├── history.spec.ts-snapshots/           # 4 baseline
    ├── recommendations.spec.ts-snapshots/   # 4 baseline
    └── likes.spec.ts-snapshots/             # 4 baseline (옵션 포함 시)
```

근거:
- Playwright snapshot 명명 convention: `<test-name>-<viewport>-<mode>-<browser>-<os>.png` — 명시적 차원이 grep 친화 + 갱신 PR review 시 diff 한눈.
- `chromium-linux` 만 (Firefox / WebKit / Windows 미포함) — false-positive ↓ + runtime ↓ (1 browser × 1 OS 만 = 24 baseline 이 5-7 분 안).
- `web/tests/visual/` 격리 — 기존 unit / integration 테스트 (`web/__tests__/` 등) 와 path 충돌 회피, Playwright e2e (PR #1200) 의 `web/tests/e2e/` 와 sibling.

**3) pixelmatch threshold 0.1% 근거** (§3-3 보강)

| threshold | 의미 | trade-off |
|---|---|---|
| 0% (strict) | 단일 pixel 차이도 fail | font sub-pixel rendering 차이로 false-positive 50%+ — 즉시 운영 불가 |
| 0.05% | 24 baseline × 1280×720 ≈ 22M pixel × 0.05% = 11000 pixel 변동 허용 | anti-aliasing edge 변동 catch, 의도된 마이크로 UI 조정도 fail risk |
| **0.1% (proposed)** | 22M × 0.1% = 22000 pixel 변동 허용 = 1 button 크기 변동 catch | pixelmatch 공식 권장 default + Chromium font rendering 안정 시 false-positive < 5% (사내 경험치, web-e2e-playwright.md §3 확인) |
| 0.5% | 100K pixel 변동 = 1 section 변동도 통과 | 회귀 catch rate ↓↓ — ADR-0018 swap 시리즈 같은 토큰 변경 detect 불가 |
| 1%+ | 화면의 1% 변경도 통과 | 사실상 visual regression 의미 상실 |

선정 사유:
- pixelmatch 공식 README 권장: "good for most uses" = 0.1.
- Chromium 단일 browser 통일 시 sub-pixel rendering 안정 — 0.1% 가 anti-aliasing margin + 의도된 마이크로 변동 모두 흡수.
- 운영 후 false-positive > 5% 발견 시 §8 Q2 따라 (b) 0.05% / (c) SSIM 0.99 전환 (별 ADR 트리거).
- ADR-0026 §Decision 3 와 일치.

**4) artifact 저장 룰** (§3-2 + §비기능 보강)

- 저장 위치: GitHub Actions artifact (`actions/upload-artifact@v4`).
- 업로드 시점: workflow `failure()` 만 (success 시 zero artifact = 비용 0).
- 업로드 대상: `web/tests/visual/__snapshots__/**/*-diff.png` (pixelmatch 가 자동 생성하는 diff png 만).
- retention: 30 일 (default 90 일 단축 → free tier 2 GB 비용 절감).
- 접근 권한: PR author + repo collaborator (gh-actions default).
- 사용자 가시화: PR comment 에 artifact URL 자동 (별 PR 후보 — `.github/workflows/visual-regression-comment.yml` 신설, §6 PR 5 의존).

근거: §비기능 "월 100 fail PR 가정 시 100-500 MB / 월" 추정 + 30 일 retention → 누적 200 MB 안. free tier 한도 (2 GB) 의 10% 안.

**5) fail 조건 (workflow fail / PR 머지 차단)**

| 조건 | workflow 결과 | rev 단계 1 판단 (§3-4) |
|---|---|---|
| diff = 0 (no change) | ✅ success | 통과 (`rev단계1: 🟢 visual diff 없음`, 코멘트 생략 가능) |
| 0 < diff ≤ 0.1% | ✅ success (threshold 안) | 통과 (자동) |
| diff > 0.1% + PR body `## visual baseline update` 명시 | ❌ failure | rev 사유 검토 후 통과 (`rev단계1: 🟢 visual baseline 갱신 의도 확인`) — fe sub-agent 가 `--update-snapshots` 로 baseline 갱신 + 같은 PR push → success 재진입 |
| diff > 0.1% + PR body 섹션 부재 또는 "baseline 변경 없음" | ❌ failure | 회귀 의심 — `reviewed:claude` 라벨 부착 차단 (`rev단계1: 🔴 시각 회귀 의심`) + fe sub-agent root cause + PR body 보강 위임 |
| Playwright Docker pull 실패 / dev 서버 down (PR branch deploy 미배포) | ⚠️ skip (graceful) | DIGEST push (`rev-skip-env-down:visual-regression`) + rev 단계 1 skip pass (`rev-qa-protocol §5-9` 룰 reference) |
| timeout (10 분 초과) | ❌ failure | rev 가 root cause 분석 (network / Docker image cold pull / web build 시간 초과) — workflow tuning 별 PR 트리거 |

차단 메커니즘:
- `rev-gate.yml` required check 에 `visual-regression` 추가 — workflow fail = `reviewed:claude` 라벨 부착 차단 = squash merge 차단.
- `auto-label.yml` 보조 — fail 시 `regression:visual` 라벨 자동 부착 (rev triage 가시화).
- 후속 PR 3 자체에서는 `rev-gate-required-check-enforcement.md` spec 머지 후 추가 — PR 3 첫 머지 시점에는 `continue-on-error: true` 박아 self-bootstrap (baseline 부재 시 모든 PR 차단 risk 회피).
- [ ] **PR 4 (PR 3 머지 후, plan)**: `docs/ai-harness/actors/sub-agent.md §2-rev` 에 단계 1 visual diff 판단 표 (§3-4) reference 1 줄 추가.
- [ ] **PR 5 (PR 3 머지 후, fe / 옵션)**: `.github/workflows/visual-regression-nightly.yml` 신설 (단계 2 dev 서버 nightly cron).
- [ ] **PR 6 (별 spec, 후보 E ADR accepted 격상 트리거)**: ADR-0026 status `proposed` → `accepted` (PR 3 머지 + 첫 baseline 확보 완료 후).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 있음 — 후속 PR 3 / 5 가 `.github/workflows/` 신설 (`visual-regression.yml`, `visual-regression-nightly.yml`). PR 2 가 `.github/PULL_REQUEST_TEMPLATE.md` 변경.
  - 본 spec PR (PR 1) 자체는 docs 만 — 보호 영역 변경 없음.
  - **정보성 분류** (CLAUDE.md §4 2026-05-28: `needs-human-review` 라벨 의무 폐지, rev 사이클이 review 대행). 후속 PR 3 / 5 는 rev 단계 1 가중도 정보로 활용.

## 7) 테스트 전략

- 본 spec 자체는 docs 만 — 테스트 X.
- 후속 PR 별 테스트 전략:
  - PR 2 (PR template): markdown only — 테스트 X. rev review 만.
  - PR 3 (workflow + Playwright spec + baseline): CI 자체가 검증 — workflow 가 첫 baseline commit 시 pass / 후속 PR push 시 fail / pass 동작 확인. fe sub-agent 가 의도 변경 시뮬레이션 1 PR 로 fail → baseline 갱신 → pass 흐름 검증.
  - PR 4 (sub-agent.md 갱신): docs — rev review 만.
  - PR 5 (nightly workflow): cron 첫 실행 결과로 검증.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | baseline `.png` 저장 — git 직접 commit vs LFS | (a) git 직접 commit (1차 도입) / (b) 처음부터 LFS / (c) GH artifacts | @goohong / PR 3 머지 전 |
| Q2 | drift threshold — pixelmatch 0.1% 가 적정한가 | (a) 0.1% (proposed) / (b) 0.05% / (c) SSIM 0.99 / (d) baseline 1 주 운영 후 조정 | rev sub-agent / 첫 baseline 1 주 후 |
| Q3 | OS font rendering false-positive 가드 | (a) Playwright Docker 통일 (proposed) / (b) 시스템 font install / (c) `fontFamily` CSS 강제 | fe sub-agent / PR 3 |
| Q4 | nightly workflow (단계 2) 운영 — diff 발견 시 사용자 알림 방식 | (a) rev DIGEST push (rev-qa-protocol §5-9) / (b) Discord 사용자 reply / (c) GitHub issue 자동 생성 | @goohong / PR 5 머지 전 |
| ~~Q5~~ ✅ | ~~ADR-0026 §8 Q1~Q4 와 본 spec §8 Q1~Q4 의 매핑 — accepted 격상 시 ADR 결정 로그 vs 본 spec 결정 로그 우선순위~~ — **(c) 양쪽 동일 갱신** 채택. ADR-0026 §8 신설 (PR #1319 머지 후) 이후 본 spec §8 Q1~Q4 와 ADR 의 Q1~Q4 는 결정 로그 양쪽 같은 일자 / 같은 내용으로 갱신. PR #1319 본문 self-link 형태. 결정 로그 §9 참조. | — | @goohong / **closed 2026-05-29** |
| Q6 (본 spec 신규) | `## visual baseline update` 섹션 의무 부착 — 자동 검증 workflow 신설 vs rev 자율 판단 | (a) `visual-baseline-pr-body-check.yml` workflow 신설 / (b) rev §3-4 판단 표만 / (c) 둘 다 | rev sub-agent / PR 3 머지 후 |

ADR-0026 §8 Q1~Q4 인용 (proposed 단계 — 본 spec 머지 후 ADR 갱신 시 동기화):

- ADR-0026 §8 Q1 (가정): 대상 페이지 범위 — 본 spec §3-1 에서 5-10 개 확정.
- ADR-0026 §8 Q2 (가정): baseline 저장 — 본 spec Q1 으로 이동.
- ADR-0026 §8 Q3 (가정): threshold — 본 spec Q2 으로 이동.
- ADR-0026 §8 Q4 (가정): baseline drift 룰 — ADR-0026 §Decision 2 후보 (a) 채택, 본 spec §3-2 + §3-4 명시.

(주: ADR-0026 본문 자체는 §8 항목 명시 X — proposed 단계 컨텍스트만. 본 spec 의 Q1~Q4 가 사실상 ADR 의 후속 검증 매트릭스 역할.)

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-05-29**: 초안 작성 (status=draft). 트리거 — ADR-0026 (`docs/decisions/0026-visual-regression-ci.md`, proposed) + plan round 4 작업 지시 + rev-direct-qa-extension §3 후보 E + §8 Q4 (baseline drift 룰 미정).
- **2026-05-29**: §3-2 baseline 관리 — ADR-0026 §Decision 2 후보 (a) `--update-snapshots` 명시 PR 의무 채택. 사유: rev 자율 판단 (위험) 회피 + 의도 명시 강제 = ADR-0019 (event-driven, agent 망각 의존 폐기) 정신 일치.
- **2026-05-29**: §3-3 drift threshold proposed 0.1% — first baseline 확보 후 1 주 운영 결과 조정 (§8 Q2). SSIM 은 false-positive 5% 초과 시 별 ADR.
- **2026-05-29**: §3-5 CI 실행 위치 — 단계 1 (PR 차단 게이트) 의무 / 단계 2 (nightly) 옵션 / 단계 3 (production smoke) 별 cycle. ADR-0026 §Decision 3 와 일치.
- **2026-05-29**: §4 제외 — Playwright e2e infra (PR #1200) 머지 의존 명시 + baseline LFS 전환 별 ADR + percy/Chromatic SaaS 보안 정책 위반 risk 채택 X (ADR-0026 Alternatives A).
- **2026-05-29**: §6 PR 6 (ADR-0026 accepted 격상) 트리거 = PR 3 머지 + 첫 baseline 확보 완료 시점 — ADR 본문 §Decision 의 격상 조건과 일치.
- **2026-05-29**: **§8 Q5 closure** — (c) 양쪽 동일 갱신 채택. 사유: ADR-0026 §8 (PR #1319, OPEN) 신설 후에는 ADR 본문 §8 Q1~Q4 가 본 spec §8 Q1~Q4 의 super-set 이 아니라 동일 매트릭스의 1:1 mirror — 한쪽 결정 누락 시 sync drift 사고 risk ↑. 결정 로그도 양쪽 같은 일자 / 사유 / PR 번호로 동시 박제. 운영 룰: ADR-0026 §Decision 갱신 PR 은 본 spec §9 같이 갱신 의무 (rev 단계 1 audit grep — `0026-visual-regression-ci.md` 와 `visual-regression-ci.md` 동시 diff 확인). 본 closure 의 머지 가능 시기 = PR #1319 머지 후 (PR body self-link 의무 박제). 본 PR 머지 후 ADR-0026 §8 작성 PR (#1319) 본문에 본 closure 참조 link 의무.
- **2026-05-29 (plan round 12)**: **§6 PR 3 detailed design 사전 박제** — fe sub-agent 가 PR 3 launch 시 첫 reference. 5 sub-section: (1) Playwright workflow yaml 골격 (container Docker / scope:web 조건 / concurrency cancel / 30 일 artifact retention), (2) baseline 디렉토리 구조 (`web/tests/visual/__snapshots__/` + `chromium-linux` 단일 browser), (3) pixelmatch 0.1% 근거 5 row 비교표 (pixelmatch 공식 권장 + Chromium 안정 시 false-positive < 5%), (4) artifact 저장 룰 (failure 시만 / 30 일 / free tier 안), (5) fail 조건 + rev 단계 1 판단 매핑 6 row. 트리거 — plan round 12 작업 지시 + PR 3 launch 직전 사전 spec 확정 의무 (망각 가드 — ADR-0019 정신). workflow yml 실제 코드 본문은 PR 3 본 사이클, 본 spec 은 골격 / 근거 / 룰만.
