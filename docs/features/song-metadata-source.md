---
feature: 곡 메타데이터 출처 (song-metadata-source)
slug: song-metadata-source
status: approved
owner: "@goohong"
scope: song
related_issues: [3]
related_prs: [4]
last_reviewed: 2026-05-20
---

# 곡 메타데이터 출처 (song-metadata-source)

## 1) 개요 (What / Why)
- 추천이 작동하려면 **추천 대상 곡 풀(pool)** 과 각 곡의 메타데이터(키, 음역, 장르, BPM, 분위기, 노래방 곡번호 등)가 필요하다.
- 본 spec은 그 데이터를 **어디서 어떻게 확보하고 갱신할지**를 정한다. `docs/ai-harness/06-domain-model.md §7 D2`를 다룬다.
- 대상 액터: 시스템(데이터 시드/배치)과 — 향후 — 관리자(큐레이션).
- 핵심 가정: 한국 노래방 사용자가 1차 타깃이므로 **노래방 곡번호(TJ/금영)** 가 곡 식별의 중요한 키 중 하나.

## 2) 사용자 시나리오
직접 사용자가 보는 화면은 없다. 다음은 시스템·운영 시나리오다.

1. **초기 시드 적재** — 첫 추천 서비스 가동 전, 50~수백 곡의 메타데이터를 DB에 적재한다.
2. **신곡 추가** — 신곡이 출시되거나 누락 곡이 발견되면 카탈로그에 추가한다.
3. **메타데이터 보정** — 키/음역/분위기 라벨이 잘못된 경우 정정한다.
4. **노래방 곡번호 매핑** — TJ/금영 곡번호를 곡과 연결한다. (사용자가 노래방 책자/태블릿에서 직접 곡번호로 검색하는 UX 연결고리)

## 3) 요구사항
### 기능 요구사항
- [ ] 곡 메타데이터를 `Song` 엔티티 형태로 영속화한다.
- [ ] 곡 식별자(내부 ID) + 외부 식별자(예: ISRC, Spotify ID, TJ 번호, 금영 번호)를 동시에 보관한다.
- [ ] 곡 음역(`SongRange`)을 곡당 1쌍(최저, 최고 MIDI note)으로 보관한다.
- [ ] 메타데이터 출처(`source`) 및 신뢰도 표기를 곡 레코드에 남긴다 (`MANUAL`, `EXTERNAL_API`, `INFERRED` 등).
- [ ] PoC 단계에선 신곡 추가/보정이 **DB 직접 또는 시드 SQL/JSON** 으로 가능하면 충분 (관리자 UI는 비범위).

### 비기능 요구사항
- 외부 API 호출 시 키/토큰은 환경변수로만 (`docs/ai-harness/04-security-policy.md`).
- 외부 출처 사용 시 라이선스/약관 위반 없음.
- 곡 데이터 정합성: `lowest_note <= highest_note`, 키는 표준 표기(C, C#, ...).
- 크롤링 시 robots.txt 및 약관 우선 검토 (해당 안 하기로 결정되면 제외).

## 4) 범위 / 비범위 (중요)
### 포함
- 곡 메타데이터 출처 채택 의사결정 (Q1).
- 1차 PoC 시드 데이터 수급 방식 + 규모 결정 (Q4).
- 노래방 곡번호 입수 경로 (Q2).
- 곡 음역(`SongRange`) 산정 방식 (Q3).
- `Song` 엔티티 필드 1차 컷 제안.

### 제외 (Out of Scope)
- **추천 알고리즘** — 메타데이터를 어떻게 매칭에 쓸지는 `recommendation-algorithm-v1.md`(미작성, D3).
- **관리자 UI / 큐레이션 워크플로우** — 1차 PoC에선 DB/시드 파일 직접 편집. UI는 별도 spec.
- **음원 스트리밍/미리듣기 통합** — 추천 결과 화면에 30초 미리듣기를 붙일지 등은 web 측 별도 결정.
- **AGPL 라이선스와의 호환성 심층 검토** — 외부 데이터셋(예: MusicBrainz CC0, Wikidata CC0)의 라이선스 호환은 채택 시점에 별도 검토.

## 5) 설계

### 5-1) 도메인 모델
- 신규: **`Song`** (Entity) — 카탈로그의 1행.
  - 필드(잠정): `id`, `title`, `artist`, `releaseYear`, `keyOriginal`(곡 원곡 키), `songRangeLow`, `songRangeHigh`(MIDI), `bpm`, `mood`(enum, 다중), `language`, `genre`, `tjNumber`, `kyNumber`, `spotifyId`, `isrc`, `metadataSource`, `metadataConfidence`, `createdAt`, `updatedAt`.
- 신규: **`SongRange`** (Value Object) — `(lowestNote, highestNote)` MIDI 표현. `VoiceRange`와 동일 표현 규약 사용해 매칭 비용 절감.
- 도메인 모델 §4 유비쿼터스 랭귀지에 이미 등재된 용어: `Song`, `SongRange`, `Key`, `Mood`. 추가 후보: `MetadataSource`, `KaraokeNumber` (TJ/금영의 추상화).

### 5-2) API 엔드포인트
PoC 단계에선 **읽기만 노출**. 등록/수정은 시드 파일 또는 admin 도구로.

| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| GET | /api/v1/songs/{id} | 곡 상세 조회 | 익명 가능 | - | `SongResponse` |
| GET | /api/v1/songs?keyword=... | 키워드(제목/아티스트) 검색 | 익명 가능 | query | `List<SongResponse>` |

> 추천 결과에서 호출되는 read API만 1차로 둔다. POST/PUT은 admin 분리 후 결정.

### 5-3) 외부 연동 후보
| 출처 | 장점 | 단점/리스크 |
|------|------|-------------|
| **수기/시드 JSON** | 정확도·라이선스 안전, PoC에 즉시 가능 | 양 부족, 운영 인건비 |
| **MusicBrainz / Wikidata** | CC0, 약관 깔끔, ISRC·아티스트 풍부 | 키/BPM/음역 정보 빈약, 곡 음역 직접 못 얻음 |
| **Spotify Web API** | 곡 키(`key`), 템포(BPM), 에너지/밸런스 등 audio-features 풍부 | OAuth 필요, 약관상 데이터 캐싱 제약, **곡 음역 자체는 제공 안 함**, 한국 곡 커버리지 불균일 |
| **Apple Music API** | 한국 곡 커버리지 양호 | 약관 까다로움, 개발자 등록 비용 |
| **TJ/금영 사이트 크롤링** | 노래방 곡번호 직결 | 약관·저작권법 리스크 큼, 차단/구조 변경 취약, **AGPL 프로젝트에서 권장 비추천** |
| **사용자 기여(UGC)** | 장기적으로 풍부 | 1인 PoC 단계엔 0건, 신뢰도 검증 필요 |

### 5-4) 데이터 흐름 / 시퀀스 (PoC 권장 안)
```
[수기 시드 JSON ~100곡]
    ↓ Flyway 또는 ApplicationRunner로 DB 시드 적재
[외부 API: MusicBrainz/Spotify (선택)]
    ↓ 배치/onetime 스크립트로 audio-features 보강 (캐시 정책 약관 준수)
[Song 테이블]
    ↑ /api/v1/songs/* 조회
```
원본 호출 응답은 약관에 따라 단기 캐시만 두거나, 식별자만 저장하고 필요 시 재조회.

### 5-5) DB 마이그레이션
- 신규 테이블 `song` (필드는 §5-1 참조).
- 인덱스: `tj_number`, `ky_number`, `(title, artist)`, `metadata_source`.
- 마이그레이션 도구는 `voice-range-input.md Q3`에서 결정. (Flyway 권고 유지)

### 5-6) 프론트엔드 화면
- 본 spec 범위 아님. 추천 결과 화면에서 `SongResponse`만 소비.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 본 spec 초안 PR — 본 PR.
- [ ] PR B (docs): Q1~Q4 합의 결과 spec 갱신 + `Song` 엔티티 도메인 모델 §5/§6 반영.
- [ ] PR C (feat:song): `Song` 엔티티 + Repository + read API.
- [ ] PR D (chore:song): 시드 데이터 입수·정규화 스크립트 + 시드 JSON 커밋.
- [ ] PR E (chore:song): (선택) 외부 API 보강 배치 — Q1 결정에 따라 생략 가능.
- [ ] PR F (test): 곡 검색/조회 E2E (RestAssured).

## 7) 테스트 전략
- **단위**: `SongRange` 불변식, MIDI 변환, 외부 API 응답 → 도메인 매핑.
- **통합/Repository**: testcontainers MySQL에 시드 적재 후 검색 인덱스 동작 확인.
- **E2E (필수)**: 곡 상세 조회 + 키워드 검색 성공 케이스 RestAssured.
- **외부 API mock**: WireMock 또는 응답 픽스처. 실제 토큰 사용 테스트는 CI에서 제외.
- **데이터 정합성 테스트**: 시드 JSON → DB 라운드트립에서 unique 제약, 음역 불변식.

## 8) 오픈 질문
> 모든 항목 해소. 본 spec은 `approved`. 새 질문이 생기면 본 테이블에 추가.

| #  | 질문 | 상태 |
|----|------|------|
| Q1 | 1차 메타데이터 주 출처 | **결정됨** → §9 (2026-05-20) |
| Q2 | 노래방 곡번호 입수 방법 | **결정됨** → §9 (2026-05-20) |
| Q3 | 곡 음역(`SongRange`) 산정 | **결정됨** → §9 (2026-05-20) |
| Q4 | PoC 시드 데이터 규모/장르 분포 | **결정됨** → §9 (2026-05-20) |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-20: 초안 작성 (status=draft). D2를 본 spec Q1으로 다룸. 출처: #3
- 2026-05-20: Q1~Q4 작성자 권고안대로 확정, status=approved. 라이선스 안전성과 PoC 적정 규모 우선. 차후 재검토 가능. 출처: #4
  - **Q1 → (b) 수기 시드 + MusicBrainz 보강** — AGPL 프로젝트와 라이선스 호환(CC0)이 매끄럽고 Spotify 약관 캐싱 제약을 피할 수 있다. Spotify는 audio-features가 필요해지는 시점에 후속 PR.
  - **Q2 → (b) 수기 입력 (시드 100곡 대상)** — 크롤링은 법적 리스크 + 운영 부담으로 후순위.
  - **Q3 → (d) 본 단계 보류** — 1차 추천은 "사용자 음역대가 곡 원곡 키 ±3 semitone에 들어가는가" 규칙으로 단순화. 본격 `SongRange` 산정은 recommendation-algorithm 후속에서.
  - **Q4 → (b) 100곡, 발라드/댄스/팝 가중** — 적은 양으로도 추천 흐름 검증 가능 + 큐레이션 부담 합리적.
