---
feature: Design tokens residual zinc swap 매트릭스 — ADR-0018 단계 4 완결성 추적
slug: design-tokens-residual-swap-matrix
status: shipped
owner: @goohong
scope: web
related_issues: [1044, 1659]
related_prs: [1219, 1249, 1253, 1256, 1259, 1659]
last_reviewed: 2026-06-03
---

# Design tokens residual zinc swap 매트릭스 — ADR-0018 단계 4 완결성 추적

## 1) 개요 (What / Why)

ADR-0018 (design tokens) 단계 4 구현이 PR 1 (#1131 tokens 도입) 부터 PR 14 (#1219 recommend zinc swap) + 후속 (#1249 / #1253 / #1256 / #1259) 까지 16+ PR 누적 머지로 진행 중. 본 spec 은 **현 시점 (develop @ 20f3cb8) 잔존 zinc hardcode 페어를 파일 단위로 measure-grep 박제하고, 후속 sub-PR 분할 + 의존성 + 신규 토큰 결정 게이트를 정의**한다.

ADR-0018 자체는 결정값 (color / typography / spacing / radius / shadow / motion 30+ token 명세) SoT 이고, `ui-ux-redesign.md §6` 은 9 PR 전체 backlog (Button/SongCard/Sheet/VoiceWave 등 컴포넌트 redesign goal) SoT. 본 spec 은 그 사이 — **"PR 1 (token 도입) 완료 후 PR 2-9 컴포넌트 redesign 으로 넘어가기 전 zinc → token 1:1 swap 완결성"** — 의 진행도 추적 + 잔존 매트릭스 single SoT.

PR #1259 audit 결과 5 카테고리 후속 백로그 (SongDetailModal 전용 토큰 / primitive 일괄 swap / ThemeToggle+BottomNav focus ring / SongCard 비활성 페어 / hover-focus-within ring 토큰 미정) 가 식별됨. 본 spec 은 각 카테고리별 sub-PR 우선순위 + 의존성 그래프 + "단계 4 swap 완결 게이트" 정의로 ADR-0018 의 status 가 `accepted` → `implemented` 로 전이 가능한 시점을 명시한다.

대상 액터: fe sub-agent (구현) / rev sub-agent (audit) / plan sub-agent (본 spec + ADR-0018 갱신) / nmae (백로그 launch).

## 2) 사용자 시나리오

본 spec 은 도메인 사용자 시나리오 (시각 변화) 가 아닌 **agent 운영 시나리오** (백로그 진행) 가 SoT.

### 시나리오 A: fe sub-agent 가 다음 zinc swap 사이클 launch 받음
"nmae 가 백로그 등록 → fe sub-agent 가 본 spec §5 잔존 매트릭스 + §6 sub-PR 분할 으로 즉시 (1) 현 단계 sub-PR ID 확인 (2) 영향 파일 + 신규 토큰 필요 여부 (3) 다른 sub-PR 와 conflict 회피 경로 결정. 본 spec 외 매번 grep audit 반복 비용 제거."

### 시나리오 B: rev sub-agent 가 zinc swap PR audit
"rev 가 본 spec §5 매트릭스 와 PR diff 비교 → 다음 잔존 카운트가 spec 갱신 없이 줄어드는지 검증. swap 누락 / 새 hardcode 도입 / 토큰 명명 위반 즉시 단계 1 코멘트."

### 시나리오 C: plan sub-agent / 사용자 가 단계 4 swap 완결 시점 판정
"단계 4 진행도 % 가 본 spec §3 게이트로 자동 계산. 잔존 매트릭스 활성 카운트 == 0 + 신규 토큰 4종 결정 + 테스트 동기 swap 완료 시 ADR-0018 status `accepted` → `implemented` 전이 PR + ui-ux-redesign.md 단계 4 PR 2-9 (Button/Sheet 등 redesign goal) 진입."

## 3) 요구사항

### 기능 요구사항

#### 잔존 매트릭스 measure (본 spec §5)
- [x] develop @ 20f3cb8 기준 활성 zinc hardcode 41건 / 10 파일 grep 박제
- [x] 카테고리 5종 (primitive / recommend container / modal 전용 / nav+toggle / 페이지 잔여) 분류
- [x] 비활성 (주석 마커) 75건 별도 추적 — swap 완료 시 동시 정리 (정보성 cleanup)

#### sub-PR 분할 + 의존성 (본 spec §6)
- [x] PR 분할 5종 정의 (우선순위 / 의존성 / 신규 토큰 필요 / LOC 예상 / 회귀 risk)
- [x] 의존성 그래프 (한 PR 가 다른 PR 의 토큰 결정에 의존) 명시
- [x] 단계 4 swap 완결 게이트 (active count == 0 + 신규 토큰 결정 + 테스트 동기 swap) 정의 — **PR #1659 에서 게이트 통과 (활성 0)**

#### ADR-0018 status update (본 spec 의존)
- [x] ADR-0018 본문에 "## Status timeline" 섹션 신설 — `accepted (2026-05-24)` → `implementing (2026-05-26 PR 1 #1131 머지부터)` → `implemented (2026-06-03 PR #1659 게이트 통과)` 전이 박제
- [x] ADR-0018 `## Consequences` 에 "단계 4 진행도" 표 — sub-PR 수 / 누적 swap 라인 수 / 잔존 카운트 (본 spec §5 cross-link)

### 비기능 요구사항

- **추적 비용 ↓**: agent 가 매번 grep 반복 X — 본 spec 의 매트릭스 표를 다음 PR 머지 시 즉시 갱신 (PR 본문 자기 점검에 "본 spec §5 매트릭스 갱신 여부" 포함).
- **drift 가드**: 본 spec `related_prs` frontmatter 가 단계 4 누적 PR 목록 SoT. PR 머지 시 누락 → rev 단계 1 audit 경고.
- **명명 일관성**: 신규 토큰 도입 시 ADR-0018 `### 1) Color token` ~ `### 6) Motion token` 카테고리 안에서만. 새 카테고리 신설 = 별도 ADR.
- **단계 4 완결 정의**: "100% zinc 제거" 가 아니라 **"활성 hardcode == 0 + 의도적 zinc 사용 (예: PR #1219 의 `--cta-secondary-ring` 값이 light/dark 모두 zinc-500) 은 token 정의 내부로 박제"**. 명세 외부에 zinc 가 남는 것을 회귀로 본다.

## 4) 범위 / 비범위 (중요)

### 포함
- 현 단계 4 PR 1~14+ 머지 후 잔존 zinc hardcode 매트릭스 (활성 + 비활성 분리)
- 다음 5 sub-PR 분할 + 의존성 그래프 + 우선순위
- 신규 토큰 4종 결정 게이트 (--modal-backdrop / --surface-modal / --ring-soft-hover / --ring-soft-focus-within)
- ADR-0018 status timeline + 단계 4 진행도 % 정의
- 테스트 동기 swap 룰 (Button.test / Chip.test bg-zinc-900 assertion 어떤 PR 에서 갱신할지)

### 제외 (Out of Scope)
- **ui-ux-redesign.md §6 PR 2-9 컴포넌트 redesign goal** — 본 spec 은 zinc → token 1:1 swap 만. SongCard gradient stripe / Bottom Sheet drag handle / pitch wave 시각화 등은 swap 완결 후 별도 사이클.
- **신규 토큰 카테고리 신설** — 본 spec 의 4종 신규 토큰은 ADR-0018 의 기존 카테고리 (color / shadow / radius) 내부 alias. 새 카테고리 도입은 별도 ADR.
- **테스트 visual regression CI 통합** — Percy / Chromatic 등은 단계 4 범위 외 (ui-ux-redesign.md §7 와 동일).
- **Tailwind v4 → v5 마이그레이션 추적** — design tokens 매트릭스와 분리.
- **dark mode 토글 사고 후속** (#1236 hydration 워닝 / same-tab custom event 채널) — ThemeToggle.tsx zinc swap 만 본 spec 추적, 토글 동작 회귀는 별도 spec.

## 5) 설계

### 5-1) 잔존 zinc 매트릭스 (develop @ 20f3cb8 기준)

> **완결 (2026-06-03, PR #1659)**: 아래 표는 초안 측정 시점 (41건 / 10 파일) 의 박제. sub-PR 1~5 + PR #1659 누적 swap 으로 **활성 hardcode 카운트 == 0** (§5-1 측정 명령 재현 결과 빈 출력). PR #1659 가 잔존 primitive (Button/Card/Chip) + nav/floating (ThemeToggle/HomeLink/BottomNav) + recommend (SongCard/SongDetailModal) + soft ring / floating / nav / subtle border 신규 토큰 7종을 마감. tokens.css / `*.test.ts` / `darkModeCoverage.test.ts` 의 의도적 zinc 참조는 보존 (토큰 정의 내부 박제).

#### 활성 hardcode (실제 className 사용) — 41건 / 10 파일 (→ 0, PR #1659 완결)

| # | 파일 | 카운트 | 분류 | 다음 sub-PR | 신규 토큰 필요 |
|---|---|---:|---|---|---|
| F1 | `web/components/ui/Button.tsx` | 4 | primitive | sub-PR 2 (primitive 일괄) | 0 (기존 `--cta-neutral` / `--cta-secondary` 재사용) |
| F2 | `web/components/ui/Card.tsx` | 4 | primitive | sub-PR 2 | 0 |
| F3 | `web/components/ui/Chip.tsx` | 3 | primitive | sub-PR 2 | 0 |
| F4 | `web/components/ui/ThemeToggle.tsx` | 3 | nav+toggle | sub-PR 3 (fe round 5 진행 중) | 0 |
| F5 | `web/components/nav/BottomNav.tsx` | 2 | nav+toggle | sub-PR 3 | 0 |
| F6 | `web/app/recommend/components/SongCard.tsx` | 11 | recommend container + 비활성 페어 | sub-PR 4 (분할 가능) | 2 (`--ring-soft-hover` + `--ring-soft-focus-within`) |
| F7 | `web/app/recommend/components/SongDetailContent.tsx` | 3 | recommend container | sub-PR 4 | 0 |
| F8 | `web/app/recommend/components/SongDetailModal.tsx` | 3 | modal 전용 | sub-PR 1 (신규 토큰 필요) | 2 (`--modal-backdrop` + `--surface-modal`) |
| F9 | `web/app/history/page.tsx` | 5 | 페이지 잔여 | sub-PR 5 | 0 |
| F10 | `web/app/history/components/VoiceRangeProgressCard.tsx` | 3 | 페이지 잔여 | sub-PR 5 | 0 |
| **소계** | | **41** | | | **4 신규** |

> 측정 명령 (재현 가능):
> ```bash
> cd /home/mobruji/mobruji-plan
> git --no-pager grep -nE "(ring-zinc|bg-zinc|text-zinc|border-zinc|hover:bg-zinc|hover:ring-zinc|focus-within:ring-zinc|focus-visible:ring-zinc|dark:bg-zinc|dark:text-zinc|dark:border-zinc|dark:ring-zinc|dark:hover:bg-zinc|dark:hover:ring-zinc|dark:focus-within:ring-zinc|dark:focus-visible:ring-zinc|dark:focus:ring-zinc|focus:ring-zinc)" \
>   origin/develop -- 'web/app/**' 'web/components/**' \
>   | grep -v '.test.' \
>   | grep -vE '(:[[:space:]]*\*|^[^:]+:[^:]+:[[:space:]]*//)'
> ```

#### 비활성 (주석 마커) — 75건 추적 cleanup
- PR 11~14 작업 시 **swap 직전 마커 주석** ("`bg-zinc-50` → `--bg-subtle`" 형식) 으로 박힌 잔여물. 활성 hardcode 가 없으면 codebase 동작 영향 0 — 다만 코드 노이즈.
- **cleanup 룰**: 각 sub-PR 머지 시 해당 파일의 주석 마커도 동시 제거 (PR 본문 self-check 포함). 별도 sub-PR 신설하지 않음.
- 측정: 활성 grep 결과 (41건) 의 약 1.8배 = 약 75건 (각 swap 카테고리당 마커 1-3행).

#### 테스트 assertion 잔여 — 2 파일
- `web/components/ui/Button.test.tsx` — `bg-zinc-900` 등 primary variant assertion. **sub-PR 2 와 동시 갱신 필수**.
- `web/components/ui/Chip.test.tsx` — `bg-zinc-100` / `bg-zinc-900` assertion. **sub-PR 2 와 동시 갱신 필수**.
- prod swap 만 하고 테스트 미갱신 = 즉시 grey 실패 → sub-PR 2 가 primitive + 테스트 한 PR.

### 5-2) sub-PR 분할 + 의존성 그래프

```
sub-PR 1 (modal 토큰)  ──→  sub-PR 4 (recommend container — F6/F7/F8)
                            │
                            └→ F6 SongCard 비활성 페어 일부 sub-PR 2 의존
                                  ↓
sub-PR 2 (primitive 일괄 + 테스트)  ──→  sub-PR 4 (F6 비활성 페어 정렬)

sub-PR 3 (ThemeToggle + BottomNav, fe round 5 진행 중) — 독립 (의존 0)

sub-PR 5 (history 페이지) — 독립 (의존 0)

sub-PR 0 (신규 토큰 4종 ADR-0018 보강) — 모든 sub-PR 의 선행 조건
```

#### sub-PR 0 — ADR-0018 보강 (신규 토큰 4종 결정)
- **본 spec 자체가 sub-PR 0** (본 PR). ADR-0018 본문에 4 신규 토큰 정의 추가:
  - `--modal-backdrop` (light: `rgb(24 24 27 / 0.6)` = zinc-900/60, dark: 동일 또는 강화)
  - `--surface-modal` (light: white, dark: zinc-900) — 기존 `--bg-base` 재사용 가능 여부 평가 필요 → 평가 결과 본 spec §8 Q1
  - `--ring-soft-hover` (light: zinc-300, dark: zinc-600) — `--cta-secondary-ring` (zinc-500 균일) 와 명확히 분리
  - `--ring-soft-focus-within` (light: zinc-400, dark: zinc-500) — focus visible 보다 약한 ring
- LOC 예상: ADR-0018 +20 / 본 spec +200 = 220 LOC

#### sub-PR 1 — SongDetailModal 전용 토큰 swap
- 브랜치: `feat/modal-tokens-swap-#1044`
- 영향: `web/app/recommend/components/SongDetailModal.tsx` (3 카운트) + tokens.css (2 신규)
- 의존: sub-PR 0 (토큰 정의)
- 회귀 risk: 낮음 — modal backdrop / surface 만 swap, 동작 변화 0
- LOC 예상: ~15 (css) + ~5 (tsx)

#### sub-PR 2 — primitive 일괄 swap (Button / Card / Chip + 테스트 동기 갱신)
- 브랜치: `feat/primitive-tokens-swap-#1044`
- 영향: `Button.tsx` (4) + `Card.tsx` (4) + `Chip.tsx` (3) + `Button.test.tsx` + `Chip.test.tsx` (assertion 동기 갱신)
- 의존: sub-PR 0 (`--cta-secondary-ring` 등 기존 토큰 재사용 — 신규 토큰 없음)
- 회귀 risk: **높음** — primitive 가 페이지 다수에서 import 됨. lint+typecheck+test+build 게이트 통과 의무 + 시각 회귀 수동 확인 (Chrome DevTools mobile + desktop + dark mode).
- LOC 예상: ~30 (3 tsx) + ~20 (2 test) = 50 LOC
- **사이클 추정**: 1 사이클 (primitive 11 swap + 테스트 5+ assertion 갱신 = 한 PR 큰 영향 / 작은 LOC).

#### sub-PR 3 — ThemeToggle + BottomNav focus ring (fe round 5 진행 중)
- 브랜치: `feat/themetoggle-bottomnav-focus-ring-zinc-swap-#1044` (현 fe 워크트리 brnach 동일)
- 영향: `ThemeToggle.tsx` (3) + `BottomNav.tsx` (2)
- 의존: sub-PR 0 — **단**, 현 fe 진행 중 PR 는 신규 토큰 0 으로 `--cta-secondary-ring` 만 사용 가능 (PR #1259 패턴). sub-PR 0 토큰 결정 전이라도 자율 진행 가능.
- 회귀 risk: 낮음 — focus ring 색만 변경, 동작 0
- LOC 예상: ~10

#### sub-PR 4 — recommend container 잔여 + SongCard 비활성 페어
- 브랜치: `feat/recommend-container-zinc-swap-#1044`
- 영향: `SongCard.tsx` (11 — 가장 큰 단일 파일) + `SongDetailContent.tsx` (3) + `SongDetailModal.tsx` (3) (단 sub-PR 1 가 SongDetailModal 처리 시 본 PR 에서 제외)
- 의존: sub-PR 0 (`--ring-soft-hover` + `--ring-soft-focus-within`) + sub-PR 2 (primitive Button secondary variant 정렬 후 SongCard 의 Like/Bookmark/YouTube/Expander 비활성 페어 정렬 가능)
- 회귀 risk: 중 — SongCard 가 추천 페이지 핵심 카드. 시각 회귀 수동 확인.
- LOC 예상: ~50 (SongCard 11 swap + SongDetailContent 3 + ~30 비활성 페어 정렬)

#### sub-PR 5 — history 페이지 잔여
- 브랜치: `feat/history-page-tokens-swap-#1044`
- 영향: `web/app/history/page.tsx` (5) + `web/app/history/components/VoiceRangeProgressCard.tsx` (3)
- 의존: sub-PR 0 — **단**, 기존 토큰만으로 swap 가능 (페이지 잔여 = `--text-primary` / `--text-secondary` / `--bg-subtle` 표준 패턴).
- 회귀 risk: 낮음 — 페이지 잔여 5+3 = 8 swap, 동작 0
- LOC 예상: ~20

### 5-3) 단계 4 swap 완결 게이트

다음 4 조건 동시 충족 시 **ADR-0018 status: `implementing` → `implemented`** 전이 PR (plan sub-agent).

1. **활성 hardcode == 0** (본 spec §5-1 매트릭스 카운트 합산)
2. **신규 토큰 4종 결정 + 정의** (ADR-0018 본문 반영)
3. **테스트 assertion 동기 갱신** (`Button.test.tsx` / `Chip.test.tsx` 등)
4. **주석 마커 cleanup** (비활성 75건 → 0)

게이트 4 충족 시 `ui-ux-redesign.md §6` 의 단계 4 PR 2-9 (Button press scale / SongCard gradient stripe / Bottom Sheet 등 컴포넌트 redesign goal) 진입 가능. swap 미완 상태로 redesign 진입 = "어떤 토큰을 swap 한 건지" + "어떤 토큰을 redesign 한 건지" 혼동 사고 risk → 본 spec 으로 강제 분리.

### 5-4) 추적 절차

| 시점 | 행동 | 주체 |
|---|---|---|
| sub-PR 머지 직후 | 본 spec `related_prs` frontmatter 에 PR 번호 추가 + §5-1 카운트 갱신 | plan sub-agent (또는 fe self) |
| ADR-0018 status 전이 시 | ADR-0018 본문 update + 본 spec status `implementing` → `shipped` 전이 | plan sub-agent |
| 회귀 발견 (새 zinc hardcode 도입) | 본 spec §5-1 카운트 ↑ + rev 단계 1 audit 코멘트 | rev sub-agent |
| 신규 토큰 추가 결정 | 본 spec §8 오픈 질문 → §9 결정 로그 이동 + ADR-0018 본문 반영 | plan sub-agent |

### 5-5) DB 마이그레이션
해당 없음 (web design tokens 전용).

### 5-6) 프론트엔드 화면
영향 범위는 §5-1 매트릭스 10 파일. 라우트 영향:
- `/` (홈) — F1-F3 primitive 의존 (sub-PR 2)
- `/voice-range`, `/voice-range/auto` — F1-F3 primitive
- `/recommend` — F1-F8 (primitive + recommend container + modal)
- `/songs`, `/songs/[id]` — F1-F3 primitive
- `/history` — F1-F3 + F9-F10 (sub-PR 2 + sub-PR 5)
- `/likes`, `/bookmarks` — F1-F3 primitive

전체 페이지 = sub-PR 2 (primitive) 가 시각 영향 1순위.

## 6) 작업 분할 (예상 PR 리스트)

| sub-PR | 이름 | 의존 | LOC | 우선순위 | 비고 |
|---|---|---|---:|---|---|
| 0 | 본 spec + ADR-0018 신규 토큰 4종 정의 | — | ~220 | P0 | 본 PR |
| 1 | SongDetailModal 토큰 swap | 0 | ~20 | P1 | clean / 회귀 risk 낮음 |
| 5 | history 페이지 잔여 swap | (0) | ~20 | P1 | 독립 / clean / sub-PR 0 토큰 의존 0 |
| 3 | ThemeToggle + BottomNav focus ring | (0) | ~10 | P1 | fe round 5 진행 중 (이미 launch) |
| 2 | primitive 일괄 swap + 테스트 동기 | 0 | ~50 | P2 | **회귀 risk 높음** — 전 페이지 영향, 세심 검증 |
| 4 | recommend container + SongCard 비활성 페어 | 0 + 2 | ~50 | P3 | SongCard 11 = 최대 단일 파일 / Button secondary variant 정렬 후 |

### 작업 순서 추천
1. **sub-PR 0** (본 PR) 머지 → 신규 토큰 4종 ADR 확정
2. **sub-PR 1 + sub-PR 5 + sub-PR 3** 병렬 (의존 0 또는 약함) — 3 워크트리 활용
3. **sub-PR 2** (primitive 일괄, 회귀 risk 높음) — 단독 사이클 + 광범위 검증
4. **sub-PR 4** (recommend container) — sub-PR 0 + 2 머지 후 진입
5. 4 sub-PR 모두 머지 → 활성 카운트 == 0 확인 → 주석 cleanup 동시 → **단계 4 swap 완결 게이트 통과 PR** (plan)

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: 없음 — 본 spec 은 docs/features/* + docs/decisions/* 만 수정. 후속 sub-PR 1~5 도 `web/app/**` + `web/components/**` + 테스트 파일만 변경 (`web/package.json` / lockfile 변경 0).

## 7) 테스트 전략

본 spec 자체는 docs (테스트 N/A). 후속 sub-PR 별 테스트 룰:

- **sub-PR 0 (본 PR)**: `head -1 docs/features/design-tokens-residual-swap-matrix.md` == `---` frontmatter 확인 (spec-status-check.yml 자동). spec link integrity 수동 확인.
- **sub-PR 1 (modal)**: `npm run lint && typecheck && test && build` — SongDetailModal 테스트 회귀 없음 + close button focus ring 시각 변화 확인.
- **sub-PR 2 (primitive)**: lint+typecheck+test+build + **Button.test.tsx + Chip.test.tsx assertion 동시 갱신 (`expect(...).toHaveClass('bg-[var(--cta-neutral-bg)]')` 등)**.
- **sub-PR 3 (ThemeToggle + BottomNav)**: lint+typecheck+test+build. focus state 시각 변화 수동 확인.
- **sub-PR 4 (recommend container)**: lint+typecheck+test+build + SongCard.test 회귀 + 시각 회귀 (`/recommend` mobile + desktop + dark mode).
- **sub-PR 5 (history)**: lint+typecheck+test+build + history 페이지 시각 회귀.

### 단계 4 swap 완결 검증
- grep `(ring-zinc|bg-zinc|text-zinc|border-zinc|...)` 활성 카운트 == 0
- 주석 마커 (`bg-zinc-50 → --bg-subtle` 형식) 카운트 == 0
- `npm run test` 전체 grey 0
- lighthouse 시각 회귀 수동 확인 (홈 + /recommend + /voice-range/auto + dark mode toggle)

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | `--surface-modal` 신규 토큰 vs `--bg-base` 재사용 | (a) 신규 토큰 — modal 전용 의미 명확 / (b) `--bg-base` 재사용 — light=white / dark=zinc-950 (현 modal 은 dark=zinc-900 → 통합 시 변경 risk) | @goohong / sub-PR 1 launch 전 |
| Q2 | `--modal-backdrop` light vs dark 동일 값 vs 강화 | (a) 동일 zinc-900/60 (현 값) / (b) dark 강화 zinc-950/70 (대비 ↑) | fe sub-agent / sub-PR 1 launch 시 자율 결정 가능 |
| Q3 | `--ring-soft-hover` zinc-300 정의 vs `--brand-200` 사용 | (a) zinc-300 (현 값 유지 — 무채색) / (b) `--brand-200` 사용 — brand 일관성 ↑ but 의도 변화 | @goohong / sub-PR 0 (본 PR) 시 결정 |
| Q4 | 단계 4 swap 완결 후 ADR-0018 status `implemented` 전이 시점 | (a) sub-PR 1-5 모두 머지 + 게이트 4 통과 직후 / (b) ui-ux-redesign.md §6 PR 2-9 모두 머지 후 (token + redesign 일괄 완결) | @goohong / 게이트 통과 시점 |
| Q5 | 테스트 assertion 갱신 패턴 | (a) `.toHaveClass('bg-[var(--cta-neutral-bg)]')` — 토큰 클래스 직접 / (b) `.toHaveStyle({ background: ... })` — computed style / (c) snapshot 만 | sub-PR 2 launch 시 fe 자율 결정 (현 테스트 패턴 유지 권장) |

### 자율 결정 default (질문 미해소 시 사용자 부재 가정)
- Q1 → (a) 신규 `--surface-modal` 토큰 — modal 의미 명확 + 미래 dark 값 변경 자유도 ↑
- Q2 → (a) 동일 값 — 현 zinc-900/60 회귀 0
- Q3 → (a) zinc-300 — `--cta-secondary-ring` (zinc-500) 와 단계적 명도 그라데이션 유지
- Q4 → (a) swap 게이트 통과 즉시 — token vs redesign 단계 명확 분리
- Q5 → (a) 토큰 클래스 직접 — 가독성 ↑ + computed style 환경 의존 회피

## 9) 결정 로그

- **2026-05-29**: 초안 작성 (status=implementing). PR #1259 audit 발견 5 카테고리 백로그 → 5 sub-PR + 1 신규 토큰 spec 으로 단계 4 swap 완결 경로 명확화. 활성 hardcode 41 / 10 파일 / 신규 토큰 4종 measure-grep 박제.
- **2026-05-29**: ADR-0018 status timeline 신설 결정 — `accepted (2026-05-24)` → `implementing (2026-05-26 PR 1 #1131 머지부터)` → `implemented (단계 4 swap 완결 게이트 통과 시)`.
- **2026-05-29**: 단계 4 swap 완결 게이트 4 조건 (활성 == 0 + 신규 토큰 결정 + 테스트 동기 + 주석 cleanup) 정의 — `ui-ux-redesign.md §6` 컴포넌트 redesign 진입 사전 조건.
- **2026-06-03 (PR #1659)**: 잔존 zinc swap 완결 — 활성 hardcode 카운트 0 달성. 신규 의미 토큰 7종 (`--ring-soft-hover` / `--ring-soft-focus-within` / `--surface-floating` / `--surface-floating-hover` / `--surface-nav` / `--surface-nav-blur` / `--border-subtle`) 추가. opacity-suffix 배경 (ThemeToggle/HomeLink/BottomNav `bg-*/90·95·80`) 은 Tailwind `/opacity` 와 `var()` 비호환 회피 위해 baked-alpha rgba 토큰으로 고정. Button.test/Chip.test assertion 동기 갱신 + tokens.test 신규 7종 가드 추가. ADR-0018 status `implementing` → `implemented` 전이. 매트릭스 status `implementing` → `shipped`. 게이트 4조건 중 "주석 마커 cleanup" 은 활성 swap 8 파일의 swap-history 주석만 갱신 (이미 완료된 파일의 설명용 JSDoc 은 문서 가치로 보존 — scope creep 회피).
