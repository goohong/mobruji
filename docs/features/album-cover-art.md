---
feature: 곡/앨범 커버 이미지 노출
slug: album-cover-art
status: draft
owner: "@goohong"
scope: song
related_issues: [322, 1519]
related_prs: [499, 500, 529, 531]
last_reviewed: 2026-06-03
---

# 곡/앨범 커버 이미지 노출

> **본 spec 은 ADR-0029(`docs/decisions/0029-album-cover-art-source.md`)가 "후속 Feature Spec `docs/features/album-cover-art.md`" 로 지정한 적용 plan 문서다.** 출처 결정의 근거는 ADR-0029 가 SoT 이고, 본 spec 은 (1) 캐싱·라이선스·도메인·API·UI 영향 범위를 상세화하고 (2) 이미 머지된 구현(이슈 #322)을 AS-IS 로 ground-truth 한다. ⚠️ 핵심 곡 카드 표시 + iTunes/Cover Art Archive backfill 은 **이미 구현·머지 완료** 상태이며, 본 spec 의 §6 작업 분할에는 잔여 후속 항목만 미체크로 둔다 (`코드-문서 충돌 시 문서 갱신` — CLAUDE.md §10).

## 1) 개요 (What / Why)

- mobruji 곡 표시는 그동안 **텍스트 메타데이터만**(제목/아티스트/난이도/음역)으로 운영돼 추천·스와이프·트렌딩 카드의 시각 임팩트가 약했다 (ADR-0029 Context). 사용자 아이디어(#1519, 2026-06-03)로 **앨범 커버 아트 표시**를 도입한다.
- 대상 액터: end user(추천/검색/스와이프 결과를 보는 사람). 직접 입력 화면은 없다 — 커버는 시스템이 외부 출처에서 자동 수집(backfill)해 표시만 한다.
- 해결 문제: 곡 카드의 빈약한 첫인상 + 카드 중심 UX(#1489 스와이프 / #1488 트렌딩)의 시각 약점.
- **현 구현 범위(이슈 #322, ADR-0029)**: `Song.albumCoverUrl` 컬럼 + iTunes Search(1차) → Cover Art Archive(폴백) 체인 backfill + 정기 배치 + FE 커버 썸네일/모달 + placeholder/onError fallback. 본 spec 은 이를 박제하고 잔여 후속(출처 provenance, 만료 재검증, attribution UI, 관측성)을 §6/§8 로 분리한다.

## 2) 사용자 시나리오

- **시나리오 A (커버 있음)**: 사용자가 추천 결과를 받으면 각 곡 카드 좌측에 앨범 커버 썸네일(lazy load)이 보인다. 카드를 눌러 상세 모달을 열면 큰 사이즈 커버가 즉시 표시된다.
- **시나리오 B (커버 없음 — 매칭 실패)**: 외부 출처에서 매칭되지 않은 곡(`albumCoverUrl=null`)은 음표 아이콘 placeholder 로 표시된다. 사용자는 깨진 이미지 대신 일관된 브랜드 placeholder 를 본다.
- **시나리오 C (외부 CDN 로드 실패)**: backfill 된 URL 이 404/CORS/만료로 브라우저에서 로드 실패하면, `<img onError>` 가 같은 placeholder 로 graceful fallback 한다(실패 상태는 rerender 후에도 보존).
- **시나리오 D (운영 backfill)**: 운영자/스케줄러가 `albumCoverUrl=null` 곡만 selective 하게 backfill 한다. 곡 단위 실패는 격리되어 배치 전체를 멈추지 않는다.

## 3) 요구사항

### 기능 요구사항
- [x] `Song` 에 `albumCoverUrl`(nullable) 보유 — 곡 카드에 표시할 외부 커버 이미지 URL. 매칭 실패 곡은 null.
- [x] 외부 출처 우선순위 체인 — iTunes Search(1차, 무인증) → MusicBrainz+Cover Art Archive(폴백, CC0). `AlbumCoverLookupClient` 추상 뒤로 출처 격리.
- [x] backfill 진입점 — `albumCoverUrl IS NULL` 곡만 selective 조회 후 곡당 외부 매칭. 매칭 성공 시에만 URL 영속화, 기존 값은 덮어쓰지 않음(큐레이터 수정 보호).
- [x] graceful degradation — 외부 호출/parse 실패는 절대 예외 전파 없이 해당 곡 skip, 배치 계속.
- [x] FE 표시 — 커버 URL 이 있으면 `<img>`(썸네일 lazy / 모달 즉시), 없거나 onError 면 음표 placeholder(`role="img"` + aria-label, 내부 SVG `aria-hidden`).
- [ ] (후속) 출처 provenance 노출 — 어떤 출처(iTunes/CAA)에서 왔는지 식별·표기. 현 구현은 URL 만 저장(출처 미보관).
- [ ] (후속) 만료/깨진 URL 주기적 재검증 — `fetchedAt` 류 기준 stale URL 재backfill.
- [ ] (후속) attribution UI — Apple/CAA 출처 표기(약관 검토 결과 의무 여부에 따라).

### 비기능 요구사항
- **결정성** — 커버는 추천 알고리즘 입력이 **아님**. backfill 전후로 같은 추천 입력 → 같은 결과(결정성 회귀 없음, ADR-0010 정합).
- **라이선스 준수** — 이미지 바이트는 **우리 서버에 저장하지 않는다**(§5-3 라이선스). DB 에는 외부 URL 문자열만. 클라이언트가 외부 CDN 에서 직접 로드.
- **외부 호출 격리** — 모든 외부 client 는 timeout(기본 5s) + graceful empty. CI/test 는 stub(MockWebServer/WireMock)로 실제 호출 금지.
- **rate limit 보호** — Apple 공식 한도 명시 없으나 곡 사이 throttle(기본 1s) 적용. CAA/MusicBrainz 는 User-Agent(contact 포함) 의무.
- **운영 안전** — 정기 배치는 prod 프로파일 한정, 수동 trigger 는 명시 플래그(`--mobruji.backfill-album-cover=true`)일 때만. 평시 부팅 무영향.
- **(후속) 관측성** — 현 구현은 요약 로그(analyzed/matched/updated/missed)만. 외부 출처별 outcome 카운터는 `external-api-fallback.md §5-5` 표준 정렬 대상(§6 후속).

## 4) 범위 / 비범위 (중요)

### 포함
- `Song.albumCoverUrl` 도메인/엔티티/ERD 박제(`06-domain-model.md §4-1 / §5-2 / §6`).
- iTunes → CAA 체인 backfill + 정기 배치 + 수동 trigger 의 설계 박제.
- FE 곡 카드 썸네일 + 상세 모달 커버 + placeholder/onError fallback 의 설계 박제.
- 세 핵심 결정(이미지 출처 / 라이선스 / 캐싱)의 근거·trade-off 문서화(ADR-0029 상세화).

### 제외 (Out of Scope)
- **출처 결정 자체** — ADR-0029 가 SoT. 본 spec 은 적용 plan 만.
- **Spotify 연동** — ADR-0029 에서 미채택(OAuth 비용 대비 과중). ISRC/Spotify ID 를 엔티티에 보관하게 되면 재평가(§8 / `06-domain-model.md §7` 오픈 이슈 후보).
- **직접 업로드/큐레이션 UGC 커버** — 사용자가 커버를 직접 올리는 경로는 본 spec 비범위. 단 backfill 은 기존 `albumCoverUrl` 값을 덮어쓰지 않아 향후 수기 큐레이션과 공존 가능.
- **이미지 self-hosting / 자체 CDN** — 라이선스(Apple 재호스팅 제약)상 배제. URL 만 캐싱.
- **추천 가중치 반영** — 커버는 UX 표시 전용, 점수 무영향.
- **노래방 곡번호(TJ/금영) 크롤링** — 무관(`song-metadata-source.md §3` 별도 거부).

## 5) 설계

### 5-1) 도메인 모델 (곡 ↔ 커버 관계)
- **관계**: 커버는 별도 엔티티가 아니라 **`Song` 의 단일 nullable 표시 속성**(`albumCoverUrl`)이다. 한 곡 ↔ 0..1 커버 URL. 외부 출처/release 와의 N:M 관계는 모델링하지 않는다(매칭 결과 1건만 캐싱).
- **유비쿼터스 랭귀지**(`06-domain-model.md §4-1` 등재): `앨범 커버 / AlbumCover (Song.albumCoverUrl)` — 곡 카드에 표시할 외부 커버 이미지 URL. 외부 출처(iTunes/CAA) backfill 결과를 캐싱한 nullable 표시 속성. 추천 결정성 무영향.
- **엔티티 불변/메서드**(`06-domain-model.md §5-2` 박제): `Song.backfillAlbumCoverUrl(url)` — 기존 값이 null 일 때 + 비-blank URL 일 때만 적용하고 변경 여부를 boolean 으로 반환(no-overwrite 가드). 시드/생성 경로(`Song.create(...)`/builder)에서도 직접 지정 가능.
- **ADR 스케치와의 의도된 차이**: ADR-0029 §매칭·캐싱 동작은 `coverArtSource`(enum) + `coverArtFetchedAt` 까지 스케치했으나, 현 구현은 **단일 `album_cover_url` 컬럼으로 단순화**(출처 enum·fetchedAt 미도입). 출처 provenance/재검증은 §6/§8 후속.

### 5-2) API 엔드포인트
- **신규 사용자 노출 endpoint 없음.** 기존 곡/추천 응답 DTO 가 필드 1개만 추가한다.

| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| (기존) | `GET /api/v1/songs/...`, 추천 응답 등 | `SongResponse` 에 `albumCoverUrl`(nullable string) 추가 | 기존과 동일 | (변경 없음) | `SongResponse.albumCoverUrl` |

- backfill 은 사용자 API 가 아니라 운영 trigger: **수동** = `bootRun --args='... --mobruji.backfill-album-cover=true'`(ApplicationRunner, `@Profile("!test")`), **정기** = `@Scheduled(cron "0 30 4 * * SUN", Asia/Seoul)` (`@Profile("prod")`). 별도 admin HTTP endpoint 는 현재 없음(§8 Q3 — 필요 시 MusicBrainz backfill 패턴의 admin endpoint 추가 후보).

### 5-3) 외부 연동 + 라이선스 정책 (결정 근거)

| 출처 | 우선순위 | 인증 | 매칭 키 | 라이선스/약관 | 캐싱 정책 |
|---|---|---|---|---|---|
| iTunes Search API | 1차 | **없음**(무인증 공개 엔드포인트) | `title`+`artist` 자유 텍스트(`term`), `entity=song`, `country=KR`, `limit=1` | Apple — artwork **URL 직접 링크** 요구. 서버 저장/재호스팅은 약관 risk | **이미지 미저장**, URL 문자열만 DB 캐싱. `artworkUrl100` 의 `100x100`→`600x600` 치환으로 카드용 해상도 확보 |
| MusicBrainz + Cover Art Archive | 폴백 | User-Agent(contact 포함) 의무 | 2단계: MusicBrainz release 검색 → MBID → CAA `front` 이미지(thumbnails 500→large→원본 선호) | CC0/CC — 캐싱·재호스팅 제약 가장 느슨(서버 저장도 가능하나 iTunes 와 일관 위해 URL 만 캐싱) | URL 문자열만 캐싱 |

**(1) 이미지 소스 결정 — 권장안 = 외부 음원 API 연동(iTunes 1차 + CAA 폴백), 직접 업로드/큐레이션 아님.**
- 근거: 현 `Song` 엔티티가 외부 식별자(ISRC/Spotify ID/MBID)를 안 가져 자유 텍스트 fuzzy 매칭이 유일한 경로 → 무인증·즉시 매칭되는 iTunes 가 제약과 최적합. 한국 가요 미수록 곡은 CAA 폴백으로 커버리지 보완. 직접 업로드는 1인 PoC 단계에 운영 인건비 과중(단, no-overwrite 가드로 향후 큐레이션 공존 여지 유지).
- 미채택: Spotify(OAuth+30일 캐시 갱신 의무, 비용 과중), 직접 크롤링(약관/저작권 위반).

**(2) 라이선스 정책 — 이미지 바이트 미저장 + URL 직접 링크.**
- Apple: artwork CDN URL 직접 링크만 허용, 서버 저장/재호스팅은 약관 risk → 우리는 URL 문자열만 캐싱하고 이미지는 클라이언트가 Apple CDN 에서 직접 로드.
- CAA: CC0/CC 로 재호스팅 제약이 가장 느슨하나, iTunes 와 정책 일관성을 위해 동일하게 URL 만 캐싱.
- **attribution**: Apple/CAA 모두 단순 표시에 강제 출처 표기 의무는 약관상 명확치 않음 → 1차는 표기 없이 표시, attribution 푸터/표기 도입 여부는 §8 Q2 + fe attribution spec 후보(MusicBrainz/Spotify attribution 과 묶음).

**(3) 캐싱 정책 — DB 에 외부 URL 문자열만, 자체 저장/CDN 없음.**
- 저장 위치: `Song.album_cover_url`(VARCHAR(512), nullable, 비-조회키라 인덱스 없음). 외부 URL 직접 참조.
- 이미지 전송: 클라이언트(브라우저)가 외부 CDN 에서 직접 로드 → 브라우저 캐시가 1차 캐싱 계층. 우리 서버는 이미지 트래픽 0.
- trade-off: 외부 CDN 의존(장애/URL 만료 시 깨짐) → onError placeholder fallback 으로 graceful 처리 + 만료 재검증은 §8 Q1 후속.

#### 5-3-1) 설정 (`application.yml` `album-cover.*`)
```yaml
album-cover:
  itunes:
    base-url: ${ITUNES_SEARCH_BASE_URL:https://itunes.apple.com/search}
    country: ${ITUNES_SEARCH_COUNTRY:KR}
    request-timeout: 5s
    throttle: 1s          # Apple 공식 한도 없음 — 보수적 1 req/s 보호
    thumb-resolution: 600x600
  cover-art-archive:
    music-brainz-base-url: ${MUSICBRAINZ_BASE_URL:https://musicbrainz.org/ws/2}
    cover-art-archive-base-url: ${COVER_ART_ARCHIVE_BASE_URL:https://coverartarchive.org}
    user-agent: ${ALBUM_COVER_USER_AGENT:mobruji-backend/0.1 (+https://github.com/goohong/mobruji)}
    request-timeout: 5s
```

### 5-4) 데이터 흐름
```
정기/수동 backfill
  → SongRepository.findMissingAlbumCover()  (albumCoverUrl IS NULL)
  → 곡마다 ChainedAlbumCoverLookupClient.lookupAlbumCoverUrl(title, artist)
        ├─ iTunes 매칭 → artworkUrl 600x600 → Optional.of
        └─ iTunes 무매칭 → CAA(MusicBrainz release→MBID→front) → Optional.of / empty
  → 매칭 성공 + 기존 null → Song.backfillAlbumCoverUrl(url) → save
  → 곡 사이 throttle(1s)
  → 요약 로그(analyzed/matched/updated/missed)

표시 (FE)
  → SongResponse.albumCoverUrl
        ├─ string → <img src> (썸네일 lazy / 모달 즉시)
        │     └─ onError → placeholder fallback
        └─ null/undefined → 음표 placeholder
```

### 5-5) DB 마이그레이션
- **V7**(`V7__song_album_cover_url.sql`, 머지 완료): `ALTER TABLE song ADD COLUMN album_cover_url VARCHAR(512) NULL;`
- 인덱스 없음(표시 필드, 조회키 아님). 추천 결정성 무영향. 보호 영역(`**/db/migration/**`) — 본 spec 은 문서만이라 신규 마이그레이션 없음.
- `06-domain-model.md §5-2 / §6` 박제는 본 spec PR 과 같은 PR(문서)에서 수행.

### 5-6) 프론트엔드 화면
- `AlbumCoverThumbnail`(곡 카드용, lazy) + `SongDetailContent` 내 large 커버(모달, 즉시 로드). placeholder 컴포넌트 공용.
- placeholder: `role="img"` + `aria-label="<곡> 앨범 커버 (이미지 없음)"`, 내부 음표 SVG `aria-hidden="true"`(스크린리더 이중 읽기 방지). large=h-48/w-48, thumbnail=h-14/w-14.
- onError fallback: 실패 상태 보존(같은 곡 rerender 시에도 placeholder 유지), idempotent.
- **next/image 미사용** — 일반 `<img>` 사용이라 `next.config.ts` `images.remotePatterns` 변경 불요(외부 CDN 도메인 화이트리스트 부담 없음). 향후 next/image 전환 시 Apple/CAA 도메인 등록 필요(§8 Q4).

## 6) 작업 분할 (예상 PR 리스트)

> 핵심 표시 + backfill 은 이미 머지 완료(아래 체크된 항목 = AS-IS 박제). 미체크 항목만 후속 구현 이슈 자동 시드 대상.

- [x] (be) `Song.albumCoverUrl` + V7 마이그레이션 + `SongResponse` 필드 노출 (이슈 #322 PR A)
- [x] (be) iTunes/CAA 체인 backfill + 정기 배치 + properties + graceful (이슈 #322 PR B, ADR-0029)
- [x] (fe) 곡 카드 썸네일 + 상세 모달 커버 + placeholder/onError fallback + 접근성 (#499/#500/#529/#531)
- [ ] (plan) `06-domain-model.md §4-1 / §5-2 / §6` 에 `albumCoverUrl` 박제 (본 spec PR 동봉 — drift 해소)
- [ ] (be) 외부 출처별 outcome 관측성 카운터를 `external-api-fallback.md §5-5` 표준(`mobruji.external.{itunes|coverartarchive}.request{outcome=...}`)으로 정렬
- [ ] (be) 커버 URL provenance(`coverArtSource` enum) + `coverArtFetchedAt` 도입 + stale URL 주기 재검증 (§8 Q1 결정 후)
- [ ] (fe) attribution 표기 도입 여부 결정 후 푸터/표기 (§8 Q2 결정 후, MusicBrainz/Spotify attribution 과 묶음 가능)

### 보호 영역 변경 여부 (필수 명시)
- 보호 영역 변경 여부: ☑ 있음(과거 머지된 #322 한정) / ☐ 본 spec PR 은 없음
  - 본 spec PR(문서)은 보호 영역 변경 없음 — `docs/` + `06-domain-model.md` 만.
  - 과거 #322 PR A 가 `**/db/migration/V7__...sql` + `application.yml`(`album-cover.*`) 을 변경했음(정보성 박제). 후속 관측성/provenance PR 이 `application.yml` 추가 시 rev 가중도 정보로 활용.

## 7) 테스트 전략
- **단위(BE)** — iTunes: 매칭/무매칭/`artworkUrl100`→600x600 치환/timeout·HTTP 오류 시 empty. CAA: release→MBID→front 2단계/404 graceful/`front=true` 추출(thumbnails 500→large→원본). 체인: iTunes 우선, 무매칭 시 CAA 위임. backfill: selective 쿼리/no-overwrite/곡 단위 격리/throttle.
- **통합(BE)** — stub server 로 외부 응답 fixture, 실제 호출 금지. `AlbumCoverIntegrationTest` 가 컬럼 영속 + 응답 노출 검증.
- **단위(FE)** — `AlbumCover.test.tsx`: string→`<img>`(썸네일 lazy / 모달 즉시), null/undefined→placeholder, onError→fallback(실패 상태 보존, idempotent, size 차등), placeholder SVG `aria-hidden`.
- **결정성 회귀** — backfill 전후 추천 결과 동일(커버는 점수 입력 아님).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 외부 CDN URL 만료/깨짐 재검증 전략 | (a) `coverArtFetchedAt` + 주기 재backfill(stale 우선) / (b) onError 신호를 서버로 보고해 재backfill 트리거 / (c) 현행 유지(클라이언트 placeholder fallback 만) | @goohong / 관측성 데이터 확보 후 |
| Q2 | attribution 표기 의무·도입 여부 | (a) 약관상 단순 표시엔 불요 → 미표기(현행) / (b) "이미지: iTunes / Cover Art Archive" 푸터 1줄 / (c) MusicBrainz/Spotify attribution fe spec 과 묶음 | @goohong / fe attribution spec 시점 |
| Q3 | backfill 수동 trigger 를 admin HTTP endpoint 로도 노출? | (a) 현행 CLI 플래그 + 정기 배치만 / (b) MusicBrainz backfill 패턴의 `X-Admin-Token` admin endpoint 추가 | @goohong / 운영 수요 확정 시 |
| Q4 | next/image 전환 + `images.remotePatterns` 화이트리스트 | (a) 현행 `<img>` 유지(도메인 등록 불요) / (b) next/image 전환 + Apple/CAA 도메인 등록(LCP/최적화 이득) | @goohong / UI/UX 최적화 라운드 |
| Q5 | 매칭 정확도(동명이곡/표기 차이) 신뢰도·수동 보정 경로 | (a) 현행 top-1 채택 + no-overwrite 큐레이션 / (b) 신뢰도 임계값 + 미달 곡 수동 확인 큐 | @goohong / 오매칭 리포트 후 |

## 9) 결정 로그
- **2026-06-03 (본 PR)**: 초안 작성(status=draft). ADR-0029 가 지정한 후속 Feature Spec 으로 신설. 세 결정(출처=iTunes 1차+CAA 폴백 / 라이선스=이미지 미저장·URL 만 캐싱 / 캐싱=DB URL 문자열·자체 저장 없음)을 ADR-0029 근거로 박제. 이슈 #322 로 이미 머지된 BE(체인 backfill·V7·정기 배치)·FE(커버 썸네일/모달·placeholder/onError) 를 AS-IS 로 ground-truth. ADR 스케치(`coverArtSource`/`coverArtFetchedAt`)와 현 구현(단일 `album_cover_url`)의 의도된 차이 명시 — provenance/재검증은 §6/§8 후속. `06-domain-model.md §4-1/§5-2/§6` 의 `albumCoverUrl` 박제를 본 PR 동봉으로 문서-코드 drift 해소.
- **2026-06-03 (ADR-0029, 별도)**: 출처 결정 — iTunes Search(1차, 무인증) + Cover Art Archive(폴백, CC0), Spotify 미채택. SoT 는 ADR-0029.
