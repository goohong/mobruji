---
id: 0029
title: 앨범 커버 아트 출처 — MusicBrainz+Cover Art Archive vs Spotify vs Apple Music
status: proposed
date: 2026-06-03
deciders: [@goohong]
---

# 0029. 앨범 커버 아트 출처 — MusicBrainz+Cover Art Archive vs Spotify vs Apple Music

## Context

현재 mobruji 의 곡 표시는 **텍스트 메타데이터만** (`Song.title` / `Song.artist` / 난이도 / 음역 Hz) 으로 운영된다. 시각적 요소가 곡명·가수명 텍스트뿐이라 (1) 추천 결과 화면의 첫인상이 빈약하고, (2) 쇼츠식 스와이프 선곡 UX (#1489) / 트렌딩 (#1488) 같은 카드 중심 UX 의 시각 임팩트가 약하다. 사용자 아이디어 (#1519, 2026-06-03): **앨범 커버 아트 표시**.

저작권은 절대 차단 요소가 아니다 — 합법 출처가 약관 하에 커버 아트를 제공한다. 후보 3종: **MusicBrainz + Cover Art Archive** (무료 / CC), **Spotify Web API**, **Apple Music API (iTunes Search)**. 본 ADR 은 라이선스·약관 (표시 방식 / 출처 표기 / 캐싱 제한), 매칭 가능성, 비용, 구현 난이도를 비교해 출처를 결정한다.

**결정의 1차 제약 — 매칭 키**: `06-domain-model.md §5-2` 의 `Song` 엔티티는 현재 `title` / `artist` (자유 텍스트) + `lowMidi` / `highMidi` / `difficulty` 만 보유한다. **ISRC / Spotify track ID / MBID / album 필드가 없다.** (`song-metadata-source.md §3` 이 외부 식별자 보관을 "예정"으로 적었으나 실제 엔티티 컬럼에는 미반영.) 따라서 어떤 출처든 **`title` + `artist` 자유 텍스트 검색으로 fuzzy 매칭** 해야 하며, 매칭 정확도가 출처 선택의 핵심 변수다.

본 ADR 의 적용 plan (캐싱 전략 / 엔티티 필드 추가 / fe 표시) 은 후속 Feature Spec `docs/features/album-cover-art.md` 에서 상세화한다 (본 ADR 은 출처 결정 + 근거만).

## Decision

**1차 출처 = iTunes Search API (Apple Music 계열, 무인증 공개 엔드포인트), 폴백 = MusicBrainz + Cover Art Archive.** Spotify 는 채택하지 않는다.

근거 요약:
1. **무인증 + 즉시 매칭**: iTunes Search API (`https://itunes.apple.com/search?term=<artist title>&entity=song`) 는 토큰/OAuth 없이 `artist`+`title` 텍스트 검색으로 `artworkUrl100` 을 즉시 반환. 현 엔티티가 외부 식별자를 안 가진 제약과 가장 잘 맞는다.
2. **약관상 캐싱·재호스팅 제한**: Apple 은 artwork URL **직접 링크** 를 요구 (서버 저장·재호스팅은 약관 위반 risk). 따라서 **커버 이미지 자체는 캐싱하지 않고**, **매칭 결과 (artwork URL 문자열) 만 우리 DB 에 캐싱** 한다. 이미지 바이트는 Apple CDN 에서 클라이언트가 직접 로드.
3. **폴백 = Cover Art Archive**: iTunes 무매칭 (한국 노래방 곡 / 인디 / 구곡) 시 MusicBrainz 로 release 검색 → MBID → Cover Art Archive (`https://coverartarchive.org/release/<mbid>/front`) 폴백. CAA 는 CC0/CC 라이선스로 캐싱·재호스팅 제약이 가장 느슨하다.
4. **Spotify 미채택**: artwork 표시는 무료지만 (a) OAuth client-credentials 토큰 발급·갱신 필요, (b) 30일 캐시 후 갱신 의무 등 약관 제약, (c) artwork 단독 표시는 Spotify 임베드/Attribution 정책과 마찰. 커버 1개 얻으려는 비용 대비 과하다.

### 매칭·캐싱 동작 (요약)
- `Song` 에 `coverArtUrl` (nullable, 캐싱된 artwork URL) + `coverArtSource` (enum: `ITUNES` / `COVER_ART_ARCHIVE` / `NONE`) + `coverArtFetchedAt` 추가 (상세 컬럼·마이그레이션은 후속 Feature Spec).
- 배치/온디맨드로 `title`+`artist` → iTunes 검색 → 최상위 매치 artwork URL 저장. 무매칭 시 CAA 폴백, 그래도 없으면 `NONE` 마킹 (placeholder 표시).
- **이미지 바이트는 우리 서버에 저장하지 않는다** (약관 준수). DB 에는 URL 문자열만.
- fe 는 `coverArtSource == NONE` 이면 브랜드 placeholder (이니셜 / `--brand-gradient`) 렌더 — 스와이프 카드 (#1489) 일관성 유지.

## Consequences

### 긍정적
- **무인증으로 빠른 도입**: iTunes Search 는 토큰 없이 호출 가능 — secret 관리 부담 0, 구현 난이도 최저.
- **현 엔티티 제약과 정합**: 외부 ID 없는 상태에서 `title`+`artist` 텍스트로 바로 매칭 가능.
- **약관 준수 단순화**: 이미지 미저장 (URL만 캐싱) + Apple CDN 직접 링크로 재호스팅 위반 회피.
- **폴백으로 매칭율 보완**: 한국 노래방/인디 곡은 iTunes 미수록 가능성 → CAA 폴백으로 커버리지 확대.
- **스와이프/트렌딩 UX 시각 강화**: 카드에 커버 표시로 #1489 / #1488 임팩트 향상.

### 부정적
- **매칭 정확도 불확실**: 자유 텍스트 fuzzy 매칭 — 동명이곡 / 표기 차이 (괄호 / feat. / 한영 혼용) 로 오매칭·무매칭 발생. 신뢰도 임계값·수동 보정 경로 필요.
- **외부 CDN 의존**: artwork URL 이 Apple CDN 을 가리켜 CDN 장애·URL 만료 시 깨짐. `coverArtFetchedAt` 기준 주기적 재검증 필요.
- **약관 변경 risk**: iTunes Search 는 공식 SLA 없는 무인증 엔드포인트 — rate limit / 정책 변경 가능. CAA 폴백이 있어 단일 장애점은 아님.
- **이미지 캐싱 불가의 trade-off**: 우리 서버 캐싱 금지로 클라이언트가 매번 외부 CDN 로드 (단 브라우저 캐시로 완화).

### 학습 비용
- be sub-agent: iTunes Search 응답 (`results[].artworkUrl100`, `100x100` → `600x600` URL 치환 트릭) + CAA MBID 2단계 lookup 패턴 학습.
- 신규 도메인 용어 `coverArtSource` 는 `06-domain-model.md §4` 등재 후 코드 사용 (후속 Feature Spec PR 에서).

## Alternatives (considered)

### (A) Spotify Web API — 선택되지 않음
- artwork 표시 자체는 무료지만 **OAuth client-credentials 토큰 발급·갱신** 인프라 필요 (secret 관리 + 토큰 만료 처리). 약관상 30일 캐시 후 갱신 의무 + Attribution / 임베드 정책으로 artwork 단독 표시에 마찰. 커버 1장 확보 비용 대비 과중. 단, `title`+`artist` 매칭 정확도는 가장 높은 편 — 후속에 ISRC/Spotify ID 를 엔티티에 보관하게 되면 재평가 가치 있음 (`06-domain-model.md §7` 오픈 이슈 후보).

### (B) MusicBrainz + Cover Art Archive 단독 (1차) — 폴백으로만 채택
- **라이선스 최우수**: MusicBrainz CC0, CAA 이미지 CC0/CC — 캐싱·재호스팅 제약이 가장 느슨해 우리 서버 저장도 가능. 그러나 (1) MusicBrainz rate limit 엄격 (1 req/sec, User-Agent 의무), (2) release ↔ recording 매칭이 2단계 (recording 검색 → release group → front cover) 로 복잡, (3) **K-pop / 한국 가요 / 노래방 곡 커버리지가 iTunes 대비 낮음** (편집자 커뮤니티 의존). 1차 출처로는 매칭율·난이도 열위 → **iTunes 무매칭 폴백**으로 배치.

### (C) 직접 크롤링 (멜론 / 지니 / 위키) — 선택되지 않음
- robots.txt / 약관 위반 risk 가 높고 (`song-metadata-source.md §3` 비기능 요구사항 위반), 이미지 핫링크/저장이 명백한 저작권 침해. #1519 "합법 출처" 전제와 정면 충돌. 배제.

### (D) 커버 아트 미지원 유지 (현행) — 선택되지 않음
- 구현 0 비용이나 #1489 스와이프 / #1488 트렌딩 UX 의 시각 임팩트 약점을 방치. 사용자가 명시 요청 (#1519) 한 기능이라 미채택.

## References

- 이슈 #1519 — 앨범 커버 아트 표시 (사용자 아이디어 2026-06-03)
- 이슈 #1489 — 쇼츠식 스와이프 선곡 UX (커버 표시 수혜 UX)
- 이슈 #1488 — 트렌딩 (커버 표시 수혜 UX)
- 이슈 #1490 — 곡 분석 파이프라인 / #1496 — 카탈로그 (메타 보강 연계)
- `docs/ai-harness/06-domain-model.md §5-2` — `Song` 엔티티 (현 필드: title/artist/lowMidi/highMidi/difficulty, 외부 식별자 미보유)
- `docs/features/song-metadata-source.md` — 곡 메타데이터 출처 (외부 식별자 보관 "예정" 명세)
- iTunes Search API — https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
- Cover Art Archive — https://musicbrainz.org/doc/Cover_Art_Archive/API
- (후속) `docs/features/album-cover-art.md` — 캐싱 전략 / 엔티티 마이그레이션 / fe 표시 상세 (본 ADR 채택 시 작성)
