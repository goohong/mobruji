---
feature: UI/UX redesign 단계 4 PR 4 — SongDetailModal → Bottom Sheet 전환 (drag handle + swipe-to-close)
slug: ui-ux-redesign-pr-4-bottom-sheet
status: approved
owner: @goohong
scope: web
related_issues: [1044]
related_prs: []
last_reviewed: 2026-05-29
---

# UI/UX redesign 단계 4 PR 4 — SongDetailModal → Bottom Sheet 전환 (drag handle + swipe-to-close)

## 1) 개요 (What / Why)

`docs/features/ui-ux-redesign.md` §6 PR 4 (SongDetailModal → Bottom Sheet) 단독 SoT. `ui-ux-redesign-pr-2-9-component-matrix.md` (6 PR 묶음) 에서 분리한 사유:
- **drag gesture / swipe-to-close** 구현 = touch event 회귀 가드 깊이 ↑ (passive listener / overscroll-behavior / iOS rubber-band 스크롤 충돌)
- **focus trap / ESC / backdrop click** 기존 회귀 가드 유지 의무 (현 modal 동작 보존)
- **scroll lock** body / html scroll lock vs sheet 내부 scroll 분리 — 잘못 구현 시 iOS Safari 스크롤 사고
- **라이브러리 도입 여부** (vaul / react-spring) 결정 깊이 ↑

토스 / 카카오뱅크 / iOS HIG 의 bottom sheet 패턴 = 모바일 노래방 도메인에서 (1) 한 손 조작 (2) 곡 상세 확인 후 즉시 dismiss (3) 익숙한 native 패턴 모두 충족. `SongDetailModal.tsx` (현 231 LOC, `items-end` 로 하단 정렬만) 를 swipe gesture + drag handle 가 있는 진짜 Bottom Sheet 으로 재설계.

대상 액터: fe sub-agent (구현) / rev sub-agent (audit — focus trap + ESC + backdrop + scroll lock + swipe 5 회귀 가드 grep) / plan sub-agent (본 spec 갱신 + 라이브러리 도입 결정 시 ADR 트리거) / nmae (백로그 launch).

### 본 spec 의 역할 vs 기존 SoT

| SoT | 역할 |
|---|---|
| `docs/features/ui-ux-redesign.md` | 상위 SoT — 4 단계 흐름 + PR 4 골자 (link only) |
| `docs/decisions/0018-design-tokens.md` | token 결정값 SoT — `--modal-backdrop` `--surface-modal` `--ease-emphasized` 등 |
| `docs/features/design-tokens-residual-swap-matrix.md` | swap 진행도 SoT — sub-PR 1 (SongDetailModal 토큰 swap) 완료 후 본 spec PR 진입 |
| `docs/features/ui-ux-redesign-pr-2-9-component-matrix.md` | 분리 spec — PR 2/3/6/7/8/9 묶음 (본 spec 은 PR 4 단독) |
| **본 spec** | **PR 4 단독 SoT** — drag handle / swipe / focus trap / scroll lock / 라이브러리 결정 |

## 2) 사용자 시나리오

### 시나리오 A: 모바일 한 손 사용 — 곡 상세 확인 후 swipe-to-dismiss
"`/recommend` 에서 추천 카드 탭 → bottom sheet 가 slide-from-bottom 300ms 으로 진입. drag handle bar 가 시트 상단에 잘 보임 — '아 아래로 끌면 닫히는구나' 인지. 곡 정보 (제목 / 아티스트 / 음역대 / 좋아요 / 유튜브 링크) 확인 후 drag down → 시트가 손가락 따라 내려가다 임계 지점 (드래그 거리 > 시트 높이 25%) 넘으면 close. 임계 미만 = 원위치 spring back."

### 시나리오 B: 데스크탑 / 접근성 사용자 — ESC / backdrop click
"PC 사용자가 `/recommend` 에서 카드 클릭 → bottom sheet 진입 (데스크탑에서도 sheet 패턴 유지 — modal 별도 분기 X). ESC 키 / backdrop 클릭 / close button 클릭 = close (기존 modal 회귀 보존). 키보드 사용자 = focus trap 안에서 Tab 으로 닫기 버튼 / 좋아요 / 유튜브 링크 순서 이동. 스크린리더 사용자 = `role="dialog"` `aria-modal="true"` `aria-labelledby` 부착."

### 시나리오 C: iOS Safari 사용자 — rubber-band 스크롤 회피
"iPhone Safari 에서 sheet 진입 → 시트 내부 스크롤 (긴 곡 상세 텍스트) 시 body 가 따라 스크롤 X (scroll lock). sheet 최상단에서 추가 drag down = swipe-to-close 시작. sheet 하단에서 overscroll = `overscroll-behavior: contain` 으로 body 영향 0. 시트가 닫힐 때 body scroll 위치 복원."

## 3) 요구사항

### 기능 요구사항

#### 사전 게이트 (필수 선행)
- [ ] **`design-tokens-residual-swap-matrix.md sub-PR 1` (SongDetailModal 토큰 swap) 머지 후 진입** — `--modal-backdrop` / `--surface-modal` 토큰 의존
- [ ] `ui-ux-redesign-pr-2-9-component-matrix.md` PR 2 (Button) 머지 권장 — sheet 내부 close button / 좋아요 / 유튜브 버튼 일관성

#### Drag handle bar
- [ ] 시트 상단에 drag handle bar 표시 (`width: 36px / height: 4px / radius: full / bg: var(--text-tertiary)`)
- [ ] 시각만 — 클릭/탭 기능 0 (drag gesture 가 핵심)

#### Slide-from-bottom 진입 / 종료
- [ ] enter: `transform: translateY(100%) → translateY(0)` 300ms `var(--ease-emphasized)`
- [ ] exit: `transform: translateY(0) → translateY(100%)` 250ms `var(--ease-out)`
- [ ] backdrop fade: `opacity: 0 → 1` 200ms `var(--ease-out)` (enter 시 sheet 보다 50ms 빠르게 시작)

#### Swipe-to-close (touch gesture)
- [ ] touchstart → touchmove (translateY 손가락 따라) → touchend
- [ ] 임계 지점: 드래그 거리 > 시트 높이 25% OR velocity > 0.5px/ms → close
- [ ] 임계 미만 → spring back (translateY(0) 200ms `var(--ease-emphasized)`)
- [ ] 시트 내부 scroll 위치 != 0 시 swipe gesture 비활성 (scroll 우선)

#### Focus trap / ESC / backdrop click (기존 회귀 보존)
- [ ] focus trap: sheet 열림 시 close button 으로 focus 이동. Tab → close / 좋아요 / 유튜브 / (반복). Shift+Tab 역방향.
- [ ] ESC: close
- [ ] backdrop click: close
- [ ] sheet 닫힘 시 트리거 (SongCard) 로 focus 복원

#### Scroll lock
- [ ] sheet 열림 시 `body { overflow: hidden; position: fixed; top: -{scrollY}px; width: 100% }` — iOS Safari 호환
- [ ] sheet 닫힘 시 scroll 위치 복원 (`window.scrollTo(0, scrollY)`)
- [ ] sheet 내부: `overflow-y: auto; overscroll-behavior: contain;`

#### a11y
- [ ] `role="dialog"` + `aria-modal="true"` + `aria-labelledby="<title-id>"` + `aria-describedby="<desc-id>"`
- [ ] drag handle: `role="separator"` + `aria-orientation="horizontal"` (시각 hint, 의미 보조)
- [ ] `prefers-reduced-motion: reduce` 시 모든 motion 0.01ms (대신 즉시 표시)
- [ ] 스크린리더: sheet 진입 시 title 자동 발화 (focus 이동 시점)

### 비기능 요구사항

- **성능**: drag gesture 60fps 유지 (translateY 만 변경 → composite layer 최적화). `will-change: transform` 신중 사용 (열림 / 드래그 중에만).
- **iOS Safari 호환**: rubber-band 스크롤 / 100vh viewport 사고 회피 (dvh 또는 calc 사용). passive event listener 적용 (`{ passive: true }` 또는 `{ passive: false }` 분기 — preventDefault 필요한 경우만 passive: false).
- **접근성 WCAG 2.1 AA**: focus trap / 키보드 navigation / 스크린리더 dialog role 의무.
- **번들 사이즈**: CSS-only + native touch event 우선. vaul (~14KB gzipped) / react-spring (~24KB) 도입 시 본 spec §8 결정 게이트.
- **회귀 0**: 기존 `SongDetailModal.test.tsx` 회귀 0 + 기존 focus trap / ESC / backdrop 동작 모두 보존.
- **다크 모드**: `--modal-backdrop` + `--surface-modal` 토큰 자동 swap.

## 4) 범위 / 비범위 (중요)

### 포함
- `web/app/recommend/components/SongDetailModal.tsx` → Bottom Sheet 컴포넌트 변환 (rename: 컴포넌트 명은 `SongDetailSheet` 으로 변경 권장 — 의미 명확화)
- drag handle bar + slide-from-bottom + swipe-to-close + focus trap + ESC + backdrop + scroll lock
- token 사용: `--modal-backdrop` / `--surface-modal` / `--ease-emphasized` / `--ease-out` / `--shadow-2xl`
- a11y `role="dialog"` `aria-modal` + reduced-motion 대응
- 기존 회귀 가드 보존 (focus trap / ESC / backdrop click)

### 제외 (Out of Scope)
- **multi-snap point** (예: 25% / 50% / 100% 시트 높이) — 토스 패턴이지만 본 PR 단순화 위해 1 snap point (full sheet) 만. 별도 후속 PR 후보.
- **시트 안에서 다른 sheet 진입** (nested sheet) — 본 PR 범위 외
- **데스크탑 modal 별도 분기** — 데스크탑에서도 sheet 패턴 유지 (단순성 + 일관성). 후속 PR 에서 데스크탑 modal 분기 평가.
- **view-transition-api 통합** — `ui-ux-redesign-pr-2-9-component-matrix.md` PR 7 SoT (PR 7 가 sheet 진입에 view-transition 추가 시 본 spec 갱신)
- **시트 안 다른 컴포넌트 redesign** (제목 hierarchy / 좋아요 버튼 redesign 등) — `ui-ux-redesign-pr-2-9-component-matrix.md` PR 2/6 SoT
- **라이브러리 default 도입** — 본 PR default = CSS-only + native touch event. 도입 시 §8 결정 게이트 + 별도 ADR.

## 5) 설계

### 5-1) 컴포넌트 구조

```
SongDetailSheet (구 SongDetailModal)
├─ Backdrop (div, role=presentation, onClick=close, fade-in/out)
└─ Sheet (div, role=dialog, aria-modal, slide-from-bottom)
   ├─ DragHandle (div, role=separator, 시각만)
   ├─ Header (h2 with id, close button)
   ├─ Body (scrollable region — overflow-y: auto, overscroll-behavior: contain)
   │  ├─ 곡 메타 (제목 / 아티스트)
   │  ├─ 음역대 시각
   │  ├─ 좋아요 / 북마크 버튼 (PR 2 Button 사용)
   │  └─ 유튜브 링크
   └─ (no footer — close 는 swipe-to-close + drag handle hint)
```

### 5-2) 핵심 API / state

```ts
// useBottomSheet hook (자체 구현 권장, 라이브러리 X default)
type UseBottomSheetOptions = {
  isOpen: boolean
  onClose: () => void
  closeThresholdPercent?: number // default 25
  closeVelocityThreshold?: number // default 0.5 (px/ms)
}

const { sheetRef, backdropRef, translateY, isDragging } = useBottomSheet({
  isOpen,
  onClose,
  closeThresholdPercent: 25,
  closeVelocityThreshold: 0.5,
})

// 내부:
// - touchstart: translateY 0 lock + lastY/lastT 기록
// - touchmove: deltaY = currentY - startY; translateY = max(0, deltaY); velocity update
// - touchend: deltaY > sheetHeight * 0.25 OR velocity > 0.5 → onClose(); else spring back
// - prefers-reduced-motion: drag gesture 비활성 + close button / ESC / backdrop 만
```

### 5-3) 회귀 가드 매트릭스 (rev 단계 1 grep 패턴)

| # | 가드 항목 | rev grep / 체크 |
|---|---|---|
| G1 | focus trap | `SongDetailSheet.test.tsx` 안에 `expect(closeButton).toHaveFocus()` + Tab cycle verify |
| G2 | ESC close | `fireEvent.keyDown(document, { key: 'Escape' })` → onClose 호출 verify |
| G3 | backdrop click close | `fireEvent.click(backdrop)` → onClose 호출 verify |
| G4 | scroll lock body | sheet 열림 시 `document.body.style.overflow === 'hidden'` verify + 닫힘 시 복원 |
| G5 | scroll lock iOS Safari | `document.body.style.position === 'fixed'` + `top === -{scrollY}px` verify |
| G6 | swipe-to-close 임계 | touchstart → touchmove (deltaY > sheetHeight * 0.25) → touchend → onClose 호출 verify |
| G7 | swipe spring back | touchstart → touchmove (deltaY < threshold) → touchend → onClose 미호출 + translateY 0 복원 verify |
| G8 | velocity 분기 | touchmove fast (velocity > 0.5) → touchend → 거리 미달이어도 onClose 호출 verify |
| G9 | sheet 내부 scroll != 0 시 swipe 비활성 | scroll position > 0 + touchmove → onClose 미호출 verify |
| G10 | a11y role/aria | `expect(sheet).toHaveAttribute('role', 'dialog')` + `aria-modal` + `aria-labelledby` verify |
| G11 | reduced-motion | matchMedia mock → motion duration 0.01ms + drag gesture 비활성 verify |
| G12 | focus 복원 | sheet 닫힘 시 트리거 (SongCard) 로 focus 복원 verify |

### 5-4) Visual ref + 디자인 결정

| ref | 인용 패턴 | 본 PR 적용 |
|---|---|---|
| **토스** | drag handle bar 36x4px + radius full + 30% opacity bg | drag handle 시각 1:1 차용 |
| 토스 | 4-step snap point (25% / 50% / 75% / 100%) | 본 PR = 1 snap (full sheet) 만, multi-snap 은 후속 PR |
| 토스 | slide-from-bottom 300ms + spring back 200ms | duration 동일 채택 |
| **카카오뱅크** | sheet enter 시 backdrop fade 50ms 빠르게 시작 | 채택 — backdrop 200ms / sheet 300ms |
| 카카오뱅크 | close 시 drag handle 가 사라지면서 sheet 동시 fade | exit duration 250ms (enter 보다 50ms 빠르게 — close 가 빠른 느낌) |
| **iOS HIG** | sheet 최소 44px 터치 타겟 | close button 44x44 의무 + drag area (handle 주변) 44px 세로 영역 |
| iOS HIG | rubber-band 스크롤 분리 — sheet 내부 vs body | `overscroll-behavior: contain` + body scroll lock |
| **Apple Music** | sheet 상단에 곡 cover blur 배경 | 본 PR 범위 외 (후속 — `ui-ux-redesign-pr-2-9-component-matrix.md` PR 3 SongCard hash 색 활용 검토) |

### 5-5) DB 마이그레이션
해당 없음 (web UI 전환 전용).

### 5-6) 프론트엔드 화면

| 라우트 | 영향 |
|---|---|
| `/recommend` | SongCard 탭 시 SongDetailSheet 진입 |
| `/songs/[id]` | deep-link 시 SongDetailSheet 진입 (full page sheet — 또는 별도 분기) |
| `/likes` `/bookmarks` | SongCard 탭 시 SongDetailSheet 진입 |
| `/history` | SongCard 탭 시 SongDetailSheet 진입 |

전 페이지 영향 = SongCard import 하는 모든 페이지 (PR 3 SongCard 와 동일 scope).

## 6) 작업 분할 (예상 PR 리스트)

### 본 spec 추적 1 PR

| PR | 이름 | LOC | 의존 | risk | 우선순위 |
|---|---|---:|---|---|---|
| 4 | SongDetailModal → Bottom Sheet (drag + swipe + focus trap) | ~250 | swap-matrix sub-PR 1 + 본 spec PR 2 권장 | **높** | P2 (PR 2/3/8/9 머지 후) |

### 단일 PR 권장 사유
본 PR 의 5 회귀 가드 (focus trap / ESC / backdrop / scroll lock / swipe) 가 하나의 컴포넌트에 동시 적용 — 분할 시 회귀 가드 일관성 깨질 risk. fe sub-agent 한 사이클 안에 완결 권장 (LOC 250 = 5분 reasoning 룰 안에 가능).

### 후속 PR 후보 (본 PR 머지 후)

- **multi-snap point** — 토스 패턴 (25% / 50% / 100%) 후속 PR. 본 PR 1 snap 완결 후 ROI 평가 후 결정.
- **데스크탑 modal 별도 분기** — 데스크탑에서 sheet 가 화면 하단 1/3 만 차지 → desktop 에서 중앙 modal 패턴이 더 자연스러운지 사용성 테스트 후 결정.
- **시트 안 곡 cover blur 배경** — Apple Music 패턴. SongCard hash 색 활용. 별도 PR.

### 보호 영역 변경 여부 (필수 명시)

- 본 PR: 없음 (`web/app/**` 만 변경)
- 본 spec 자체: 없음 (`docs/features/*` 만 신설)

## 7) 테스트 전략

### 단위 / 통합 테스트
- 신규 `web/app/recommend/components/SongDetailSheet.test.tsx` (구 `SongDetailModal.test.tsx` 변경) — §5-3 G1~G12 12 가드 모두 verify
- 신규 `web/hooks/useBottomSheet.test.ts` (hook 자체 구현 시) — touchstart/touchmove/touchend 시뮬레이션 + 임계 / velocity / spring back verify
- 기존 `SongDetailModal.test.tsx` rename (또는 신규로 대체) — 기존 회귀 가드 모두 보존

### E2E (rev 단계 1 e2e)
- `/recommend` 카드 탭 → sheet 진입 시각 (slide-from-bottom 300ms)
- ESC / backdrop click / close button → close 동작
- 모바일 viewport (Chrome DevTools 390x844) drag down → close 시뮬레이션 (touch event)
- 다크 모드 토글 — backdrop / surface 색 자동 swap
- `prefers-reduced-motion: reduce` 시 motion 0.01ms + drag gesture 비활성
- 스크린리더 (VoiceOver / NVDA) — dialog role 발화 + focus trap

### 시각 회귀
- 자동 (Percy / Chromatic) 미적용 — `ui-ux-redesign.md §7` 와 동일 정책
- 수동: sheet 진입 / drag down / spring back / 다크 모드 / reduced-motion 5 case

### 외부 연동 mock 전략
해당 없음 (web 전용).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 라이브러리 도입 — vaul (~14KB) / react-spring (~24KB) / CSS-only 자체 구현 | (a) CSS-only + native touch event 자체 구현 (자율 default) / (b) vaul 도입 (14KB + drag gesture 정밀도 ↑) / (c) react-spring (24KB + spring physics 정밀도 ↑) | fe sub-agent / PR 4 launch 시 (자율 default 채택 가능 — 도입 시 PR 본문 `## 자율 결정 (사유)` 명시) |
| Q2 | 컴포넌트 명 — SongDetailModal 유지 vs SongDetailSheet rename | (a) Modal 유지 (회귀 risk ↓ — import 경로 안 깨짐) / (b) Sheet rename (자율 default — 의미 명확화, codemod 또는 alias export) | fe sub-agent / PR 4 launch 시 (자율 default 채택 가능) |
| Q3 | 임계 지점 — 25% vs 33% vs 50% | (a) 25% (자율 default — 가벼운 swipe 로도 close) / (b) 33% (중간) / (c) 50% (의도적 swipe 만 close) | fe sub-agent / PR 4 launch 시 |
| Q4 | velocity 임계 — 0.5px/ms vs 1.0px/ms | (a) 0.5 (자율 default — fast swipe 도 거리 미달 시 close) / (b) 1.0 (의도적 fast swipe 만) | fe sub-agent / PR 4 launch 시 |
| Q5 | 데스크탑 - sheet vs modal 분기 | (a) sheet 패턴 유지 (자율 default — 단순성) / (b) viewport > 768px 시 modal 분기 (사용자 친숙도 ↑) | fe sub-agent / PR 4 launch 시 → 후속 사용성 테스트 후 분기 결정 |
| Q6 | multi-snap point — 본 PR vs 후속 PR | (a) 1 snap (full sheet) 만 (자율 default — 본 PR 단순화) / (b) 본 PR 안에 3 snap 동시 구현 (LOC ~400 + 회귀 risk 높) | fe sub-agent / PR 4 launch 시 (a 권장) |

### 자율 결정 default (질문 미해소 시 사용자 부재 가정 자율 결정)
- Q1 → (a) CSS-only + native touch event — 자체 구현
- Q2 → (b) SongDetailSheet rename
- Q3 → (a) 25% 임계
- Q4 → (a) 0.5px/ms velocity
- Q5 → (a) sheet 패턴 유지
- Q6 → (a) 1 snap (full sheet) — multi-snap 별도 PR 후속

## 9) 결정 로그

- **2026-05-29**: 초안 작성 (status=approved). `ui-ux-redesign.md §6 PR 4` 골자를 단독 spec 으로 분리 — drag gesture / focus trap / scroll lock / swipe 5 회귀 가드 깊이 + 라이브러리 결정 깊이 + iOS Safari 호환 = 묶음 spec (`ui-ux-redesign-pr-2-9-component-matrix.md`) 평균 깊이 초과로 단독 spec 채택.
- **2026-05-29**: 사전 게이트 = `design-tokens-residual-swap-matrix.md sub-PR 1` (SongDetailModal token swap) 머지 후 진입 + 본 spec PR 2 (Button) 머지 권장.
- **2026-05-29**: 자율 결정 default 6건 박제 (CSS-only / Sheet rename / 25% 임계 / 0.5 velocity / sheet 패턴 유지 / 1 snap).
- **2026-05-29**: 회귀 가드 매트릭스 12 항목 박제 (G1~G12) — rev 단계 1 audit 시 즉시 비교 패턴.
- **2026-05-29**: 단일 PR 권장 — 5 회귀 가드 동시 적용 = 분할 시 일관성 깨질 risk (LOC 250 = 5분 reasoning 룰 안 가능).
