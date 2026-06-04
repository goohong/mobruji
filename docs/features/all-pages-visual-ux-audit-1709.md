---
feature: 전 페이지 스크린샷 기반 시각 UX 점검 + 개선안 (/, /voice-range, /songs, /history, /likes, /bookmarks)
slug: all-pages-visual-ux-audit-1709
status: draft
owner: @goohong
scope: web
related_issues: [1709]
related_prs: []
last_reviewed: 2026-06-04
---

# 전 페이지 스크린샷 기반 시각 UX 점검 + 개선안

> 사용자 directive(이슈 #1709): "/recommend(#1708)와 같은 방법으로 다른 페이지들도 분석해달라."
> 본 문서는 **dev 배포본을 실제로 렌더해 캡처한 시각 증거** 기반 점검이다.
> `/recommend`(+`/recommend/host`)는 [[recommend-page-visual-ux-audit-1708]] 가 SoT —
> 본 문서는 **나머지 핵심 페이지**(`/`, `/voice-range`, `/songs`, `/history`, `/likes`,
> `/bookmarks`)에 집중하고, 여러 페이지가 공유하는 `SongCard`/레이아웃/네비게이션의
> **페이지 간 일관성** 갭을 별도 절로 묶었다. 구현은 fe 사이클 후속 PR 단위 — 본 문서는
> 점검 + 우선순위 개선안까지.

## 1) 점검 방법 (증거)

- 도구: Playwright(chromium) → dev 배포본 `https://101-79-20-94.nip.io` 실제 렌더 → fullPage PNG.
  (이슈 산출물 규칙 "로컬 dev 기동 금지(배포본 스크린샷)" 준수 — 로컬 서버 미기동.)
- 세션: 신규 익명 세션(빈 localStorage)으로 진입 → 빈 상태 캡처 → `/voice-range` 제출로
  추천 1건 생성 + 첫 카드 좋아요/북마크 → 데이터 있음 상태 캡처. 데스크톱 패스는 같은 세션이라
  **재방문(측정 완료) 사용자** 변형까지 함께 잡혔다.
- 캡처 매트릭스: **뷰포트 2** (모바일 375px / 데스크톱 1280px) × **페이지·상태 다수** = **17컷**.
  `docs/features/assets/all-pages-ux-1709/` 에 커밋.

| 페이지 | 상태 | 모바일 | 데스크톱 |
|---|---|---|---|
| `/` 홈 | 신규 사용자 | `mobile-home-newuser.png` | — |
| `/` 홈 | 재방문(측정완료) | — | `desktop-home-returning.png` |
| `/voice-range` | 초기(기본 C3~A4) | `mobile-voice-range.png` | `desktop-voice-range.png` |
| `/songs` | 검색 전(빈) | `mobile-songs-empty.png` | — |
| `/songs` | 결과("사랑") | `mobile-songs-results.png` | `desktop-songs-results.png` |
| `/history` | 빈 상태 | `mobile-history-empty.png` | — |
| `/history` | 데이터 있음 | `mobile-history-populated.png` | `desktop-history-populated.png` |
| `/likes` | 빈 상태 | `mobile-likes-empty.png` | — |
| `/likes` | 데이터 있음 | `mobile-likes-populated.png` | `desktop-likes-populated.png` |
| `/bookmarks` | 빈 상태 | `mobile-bookmarks-empty.png` | — |
| `/bookmarks` | 데이터 있음 | `mobile-bookmarks-populated.png` | `desktop-bookmarks-populated.png` |
| `/recommend` | 제출 직후(흐름 확인용) | `mobile-recommend-after-submit.png` | — (점검 SoT = #1708) |

## 2) 페이지별 점검 결과 — 시각·경험 갭

### A. 홈 (`/`)

**A-1. h1 이 모바일에서 단어 중간에 줄바꿈 (🟡)**
- `mobile-home-newuser.png`: "오늘 노래방, 뭐 부르지?" 가 375px 에서 `text-3xl`(sm `text-4xl`)
  크기 탓에 **"뭐 부르 / 지?"** 로 단어 중간이 끊긴다. 첫 화면 가장 큰 카피라 깨짐이 눈에 띈다.
- 개선: 모바일 폰트 한 단계 축소 또는 `word-break: keep-all`(한글 어절 보존)로 어절 단위 줄바꿈.

**A-2. 빠른 진입(SecondaryNav)이 하단 탭바와 부분 중복 + 라벨 불일치 (🟡)**
- `mobile-home-newuser.png`: 카드 아래 4버튼(곡 검색 / **받은 추천** / 좋아요 / 북마크)과
  화면 하단 고정 탭바(홈 / 측정 / 추천 / **이력** / 북마크)가 같은 화면에 공존한다.
  같은 `/history` 를 홈은 "받은 추천", 탭바는 "이력" 으로 **다르게 라벨링**하고, 북마크는 양쪽
  중복이다. 모바일에선 같은 목적지가 두 군데로 보여 인지 부담.
- 단, 데스크톱(`desktop-home-returning.png`)은 탭바가 숨겨져(`md:pb-0` + BottomNav `md:hidden`)
  SecondaryNav 가 유일 네비라 **정당**. 즉 중복은 **모바일 한정**.
- 개선: (a) `/history` 라벨을 한 용어로 통일(받은 추천 ↔ 이력), (b) 모바일에서 탭바와 겹치는
  항목은 SecondaryNav 에서 덜어내고 "곡 검색·좋아요" 등 탭바에 없는 진입만 남기는 방향 검토.

**A-3. 신규 사용자 첫 액션까지 스크롤이 길다 (🟢)**
- `mobile-home-newuser.png`: 워드마크 → 큰 h1 → 부제 → 카드(제목+4단계 리스트+페르소나 3카드+
  "그냥 둘러보기"/"직접 입력으로 시작") → 빠른 진입 4버튼. 핵심 진입(페르소나/측정)이 카드 중간에
  묻혀 첫 화면에서 "무엇부터?"가 한눈에 안 들어온다. 4단계 안내가 차지하는 비중이 큰 편.

### B. 음역대 (`/voice-range`)

**B-1. 음역 직관 막대가 페이지에서 가장 무거운 요소 (🟡 — #1708 A-3 와 동일 컴포넌트)**
- `mobile-voice-range.png` / `desktop-voice-range.png`: `VoiceRangeIntuition` 막대가 거의
  솔리드 블랙 바로 렌더돼 폼에서 시선이 가장 먼저 꽂힌다. 담는 정보("고음이 평균보다 훨씬 더
  올라가요 · 음역 폭이 넓은 편")는 보조적인데 시각 비중이 과하다. 톤다운(대비/높이) 권장.
  #1708 A-3 와 같은 컴포넌트라 함께 묶어 개선.

**B-2. "Step 1" 표기인데 단계 모델이 안 보인다 (🟡)**
- 헤더 캡션이 "Step 1"이지만 화면 어디에도 Step 2/진행도 표시가 없다(다음은 `/recommend`지만
  "Step 2"로 연결되지 않음). 단계 라벨이 고아 상태 → 진행 표시기를 넣거나 캡션에서 단계 표기 제거.

### C. 곡 검색 (`/songs`)

**C-1. 검색 전(빈 상태)에도 난이도 필터 chip 이 노출 (🟡)**
- `mobile-songs-empty.png`: 결과가 없는데 난이도(Easy/Normal/Hard) chip 이 이미 떠 있다.
  거를 대상이 없어 무의미하고, "검색어를 입력해 보세요" 안내와 충돌한다. (장르 chip 은 결과가
  있을 때만 노출돼 올바름.) 난이도 chip 도 결과가 1건 이상일 때만 노출하는 방향이 일관적.

**C-2. 결과 카드 우측이 "Unknown" 키로 도배 (🟡 — 공유 카드, §3 CC-2 참조)**
- `mobile-songs-results.png` / `desktop-songs-results.png`: 다수 카드의 "키" 값이 "Unknown"
  으로 카드 우측에 또렷이 뜬다. 비어 있는 값이 가장 눈에 띄는 자리를 차지해 노이즈. 데이터
  완결성(BE) + 표면 라벨(없으면 칸 자체 숨김) 양쪽 개선 여지.

**C-3. 검색 로딩이 "검색 중..." 텍스트뿐 (🟢)**
- likes/bookmarks 는 shimmer 스켈레톤(#1493)을 쓰는데 `/songs` 검색 결과는 맨 텍스트라
  로딩 감각이 페이지마다 다르다. 결과 리스트도 동일 스켈레톤으로 통일 여지.

### D. 받은 추천 (`/history`)

**D-1. 방금 만든 추천이 "9시간 전"으로 표시 — 상대시간 타임존 오프셋 의심 (🟡, 검증 필요)**
- `mobile-history-populated.png` / `desktop-history-populated.png`: 캡처 직전 제출로 **방금**
  생성한 추천 entry 가 "9시간 전"으로 뜬다. 9시간 = KST(UTC+9) 차이라 `requestedAt` 의
  타임존 처리(서버 naive 시각 ↔ 클라 `new Date` UTC 해석) 오프셋 버그 가능성이 높다.
  `formatRelativeKorean` 입력값·BE `requestedAt` 직렬화 포맷 확인 필요(BE 사이클 연계 가능).

**D-2. "음역 발전 그래프는 측정 2번부터" CTA 가 첫 entry 위를 점령 (🟢)**
- `*-history-populated.png`: 추천 1건만 있는 사용자에게 ProgressEmptyCta 카드가 실제 히스토리
  카드보다 위에 크게 떠, "받은 추천 다시 보기"라는 목적보다 부가 기능 안내가 먼저 보인다.
  첫 entry 아래로 내리거나 비중 축소 검토.

**D-3. 빈 상태 CTA 가 likes/bookmarks 와 다른 패턴 (🟡 — §3 CC-4 참조)**
- `mobile-history-empty.png`: CTA 1개("음역대 입력하러 가기" → `/voice-range`). 반면
  likes/bookmarks 빈 상태는 CTA 2개(`/recommend` + `/songs`). 개념적으로 같은 "아직 없음"
  화면인데 진입 동선이 페이지마다 갈린다.

### E. 좋아요 / 북마크 (`/likes`, `/bookmarks`)

**E-1. 두 빈 상태가 거의 동일 — 좋은 일관성 (🟢, 긍정 확인)**
- `mobile-likes-empty.png` / `mobile-bookmarks-empty.png`: 헤더·문구·CTA 2개 구성이 대칭이라
  학습 비용이 낮다. 의미 구분(좋아요=호불호 / 북마크=다시 부를 곡)도 부제로 잘 드러난다. 유지.

**E-2. 북마크 이모지가 깨진 글리프로 렌더 (🟡)**
- `mobile-bookmarks-populated.png` / `desktop-likes-populated.png`: 카드 footer 북마크 버튼의
  🔖 이모지가 배포 환경 폰트 fallback 으로 모노크롬 박스(≣ 형태)로 나온다. 좋아요 ❤️ 는 정상.
  이모지 의존 대신 인라인 SVG 아이콘으로 교체하면 환경 무관 일관 렌더.

**E-3. 데스크톱에서 카드 우측 여백 과다 (🟡 — §3 CC-2 참조)**
- `desktop-likes-populated.png`: 카드가 좌측 1/3 만 채우고(제목/아티스트), 키 값이 우측 끝에
  붙어 가운데가 텅 빈다. 카드가 와이드 폭에 최적화돼 있지 않다.

## 3) 페이지 간 일관성 (공유 컴포넌트·레이아웃)

**CC-1. 데스크톱 전 페이지 좁은 단일 컬럼 → 좌우 거대 여백 (🟡 — #1708 C-1 을 전 페이지로 확장)**
- `desktop-home-returning.png`(max-w-md ~448px) · `desktop-songs-results.png` /
  `desktop-history-populated.png` / `desktop-likes-populated.png`(max-w-2xl ~672px) 모두 1280px
  화면 가운데만 채우고 양옆 ~600px 가 빈다. "모바일을 확대한 듯"한 인상이 **전 페이지 공통**.
  #1708 은 `/recommend` 한정 시각 증거였으나, 본 점검으로 **전역 반응형 갭**임을 확인.

**CC-2. 공유 `SongCard` 가 와이드/Unknown 처리에 약함 (🟡)**
- `/recommend`·`/songs`·`/history`·`/likes`·`/bookmarks` 가 같은 `SongCard` 를 쓴다. 데스크톱
  와이드에서 우측 여백 과다(E-3), "키 Unknown" 노이즈(C-2)가 **모든 곡 표면에 동시 발생**한다.
  카드 1곳 개선이 5개 페이지에 파급 — ROI 높은 지점. 결측 키 칸 숨김 + 와이드 레이아웃(2열 그리드
  or 카드 내부 정렬 정비) 검토.

**CC-3. 하단 고정 탭바 ↔ 콘텐츠 겹침 (🟡 — #1708 C-2)**
- 전 모바일 fullPage 캡처에서 탭바가 카드 위에 겹쳐 찍힌다. body `pb-20`(80px)이 있어 실사용
  스크롤 끝은 대체로 가려지지 않지만(상당 부분 fullPage 캡처 artifact), 긴 페이지 마지막
  카드·CTA 가림 회귀가 없는지 safe-area 포함 `padding-bottom` 재확인 권장.

**CC-4. 빈 상태 CTA 개수·목적지 페이지마다 상이 (🟢)**
- history(1개→/voice-range) vs likes/bookmarks(2개→/recommend,/songs). "아직 없음" 화면의
  진입 동선을 공통 패턴(예: 주 CTA + 보조 CTA)으로 정렬하면 학습 비용↓.

**CC-5. 헤더 캡션 케이싱 불일치 (🟢)**
- 대부분 ALL CAPS 영문 한 단어(BROWSE / HISTORY / LIKES / BOOKMARKS)인데 `/voice-range` 만
  "Step 1"(혼합 케이스), 홈은 캡션 없음(워드마크). 캡션 규칙 통일 여지.

## 4) 개선안 항목화 (fe 사이클 PR 분할)

> 우선순위 = "혼란/노이즈 해소 ↔ 비용". ⚠️ = 데이터·BE 연계 또는 방향 합의 필요.

| 항목 | 내용 | 우선순위 | 난이도 | 비고 |
|---|---|---|---|---|
| **W1** | CC-2 공유 `SongCard` — 결측 "키" 칸 숨김 + 데스크톱 와이드 정렬 정비(E-3/C-2) | 🔴 P0 | M | 5개 페이지 동시 파급 |
| **W2** | D-1 `/history` 상대시간 "9시간 전" 타임존 오프셋 검증·수정 ⚠️ | 🔴 P0 | S | BE `requestedAt` 직렬화 연계 가능 |
| **W3** | A-2 홈 빠른 진입 ↔ 탭바 라벨 통일(받은 추천/이력) + 모바일 중복 정리 | 🟡 P1 | S | 동선 혼란 |
| **W4** | A-1 홈 h1 모바일 폰트 축소 or `keep-all` 어절 줄바꿈 | 🟡 P1 | S | 첫 화면 카피 깨짐 |
| **W5** | C-1 `/songs` 난이도 chip 을 결과 있을 때만 노출(장르 chip 패턴 일치) | 🟡 P1 | S | 빈 상태 정합 |
| **W6** | E-2 북마크 🔖 이모지 → 인라인 SVG 아이콘 | 🟡 P1 | S | 환경 무관 렌더 |
| **W7** | B-1 음역 직관 막대 톤다운(대비/높이) — #1708 A-3 와 합쳐 처리 | 🟡 P2 | S | 시선 정리 |
| **W8** | CC-4 빈 상태 CTA 패턴 통일(history ↔ likes/bookmarks) | 🟢 P2 | S | 동선 일관 |
| **W9** | C-3 `/songs` 검색 로딩 스켈레톤 통일(likes/bookmarks 패턴) | 🟢 P2 | S | 로딩 감각 통일 |
| **W10** | CC-1 데스크톱 2-column or 와이드 레이아웃(전 페이지) — #1708 V9 와 합류 | 🟢 P3 | M | 모바일 우선이라 후순위 |
| **W11** | B-2 `/voice-range` "Step 1" 단계 모델 명시 or 캡션 단계표기 제거 | 🟢 P3 | S | 고아 단계 라벨 |
| **W12** | D-2 `/history` ProgressEmptyCta 위치/비중 조정 | 🟢 P3 | S | 부가기능 우선노출 완화 |

**권장 착수 순서**: W1·W2(파급/버그) → W3·W4·W5·W6(즉효·저위험) → W7·W8·W9(정리) → W10·W11·W12(폴리시).

## 5) 오픈 질문 (결정 게이트)

| # | 질문 | 자율 default (사용자 부재 가정) | 담당 |
|---|---|---|---|
| Q1 | W2 "9시간 전" — FE 표시 버그인가 BE `requestedAt` 직렬화(타임존) 문제인가 | 먼저 BE 응답 원문 tz 확인 → FE 파싱이면 FE, 직렬화면 BE 사이클로 이관 | fe→be |
| Q2 | W3 `/history` 용어 — "받은 추천" vs "이력" 어느 쪽으로 통일 | "받은 추천"(사용자 언어, 홈 카피와 일치)으로 통일 | @goohong |
| Q3 | W1/W10 데스크톱 — 카드 정렬만 손볼지 2-column 그리드까지 갈지 | 1차는 카드 내부 정렬 + 결측 숨김(W1), 2-column(W10)은 별도 후순위 | fe |

## 6) 범위 / 비범위

### 포함
- `/`, `/voice-range`, `/songs`, `/history`, `/likes`, `/bookmarks` 의 **렌더 실측 기반** 시각·경험
  갭 + 페이지 간 일관성 + 개선안 우선순위 항목화.

### 제외
- **구현** — fe 사이클 후속 PR (본 문서는 점검 + 개선안까지).
- **`/recommend`(+`/recommend/host`)** — [[recommend-page-visual-ux-audit-1708]] SoT (중복 금지,
  본 문서 `mobile-recommend-after-submit.png` 는 흐름 연결 확인용).
- **BE 신호/데이터 변경**(키 "Unknown" 데이터 보강, `requestedAt` 직렬화 등) — 별도 BE 사이클.

## 7) 결정 로그
- **2026-06-04**: 이슈 #1709 스크린샷 기반 초안(status=draft). 모바일/데스크톱 × 다중 상태 17컷
  실측 캡처(배포본 nip.io, 로컬 미기동). 핵심 갭은 **(W1) 공유 SongCard 와이드/결측 키 노이즈 ·
  (W2) /history 상대시간 타임존 오프셋 · (W3) 홈 네비 라벨/중복**. 데스크톱 전 페이지 좁은
  컬럼(CC-1)은 #1708 C-1 을 전역 확장한 것으로 확인.
