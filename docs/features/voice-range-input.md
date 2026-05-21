---
feature: 음역대 입력 (voice-range-input)
slug: voice-range-input
status: implementing
owner: "@goohong"
scope: voice
related_issues: [1, 15]
related_prs: [2]
last_reviewed: 2026-05-21
---

# 음역대 입력 (voice-range-input)

## 1) 개요 (What / Why)
- mobruji 추천 흐름의 **입력점**. 사용자가 자신의 음역대(부를 수 있는 최저~최고 음)를 시스템에 알려주는 절차다.
- 추천 알고리즘이 어떤 형태든(`docs/ai-harness/06-domain-model.md §7 D3`), 입력으로 "사용자 음역대"가 필요하다. 즉 이 기능이 없으면 추천 자체가 불가능하다.
- 대상 액터: 노래방 직전 "뭐 부르지?" 상태의 사용자. 본인 음역대를 **모를 확률이 높다**는 게 핵심 가정.

## 2) 사용자 시나리오
1. **자기 음역 모름 (다수 가정)** — 사용자는 자기가 어디까지 부를 수 있는지 모른다. 짧은 진단을 거쳐 음역대를 추정받고 결과를 보관한다.
2. **자기 음역 앎 (드문 케이스)** — 보컬 경험자가 본인 음역(예: G2~A4)을 직접 입력하고 바로 추천을 본다.
3. **재입력/보정** — 진단 결과가 어색하면(고음/저음을 못 부른다고 느낌) 다시 측정하거나 수동 보정한다.

## 3) 요구사항
### 기능 요구사항
- [ ] 사용자가 음역대를 등록할 수 있는 진입 경로를 제공한다 (방식은 §5에서 결정).
- [ ] 등록된 음역대를 `VoiceRange`(최저음, 최고음) 형태로 영속화한다.
- [ ] 사용자가 자신의 음역대를 조회할 수 있다.
- [ ] 사용자가 음역대를 재측정/수동 수정할 수 있다.
- [ ] 음역대 단위는 **MIDI note number 또는 과학적 음표 표기법(예: `C4`)** 둘 다 다룰 수 있는 내부 표현을 사용한다.

### 비기능 요구사항
- 진단 흐름은 **30초~1분 내**로 끝나야 한다 (노래방 직전 사용 맥락).
- 음역대 원본 값(예: 마이크 입력 wav)은 저장하지 않는다 (개인정보 최소화, `docs/ai-harness/04-security-policy.md`).
- 음역대 입력 화면은 모바일 우선.

## 4) 범위 / 비범위 (중요)
### 포함
- 음역대 등록 / 조회 / 재측정 API
- 음역대 진단 UX (방식은 D1 결정 후)
- `VoiceRange` 엔티티/값 객체 도입 (도메인 모델 §5 채우기 — 이 spec과 별 PR로 분리)

### 제외 (Out of Scope)
- **추천 알고리즘** — 음역대를 어떻게 매칭에 쓸지는 별도 spec(`recommendation-algorithm-v1.md`, 미작성). D3 오픈 이슈.
- **곡 음역(SongRange) 데이터 확보** — 곡 메타데이터 출처(D2)는 별도 spec(`song-metadata-source.md`, 미작성).
- **회원가입/인증** — 이 기능 자체는 회원/익명 둘 다에서 동작하도록 설계하되, 구체적 회원 정책(D4)은 별도 결정. 1차 PoC는 익명 세션으로 진행 가능.
- **마이크 권한·오디오 신호처리 라이브러리 선정** — D1 결정 후 후속 ADR로 분리.

## 5) 설계

### 5-1) 도메인 모델
- 신규: **`VoiceRange`** (Value Object) — `(lowestNote, highestNote)` 한 쌍.
  - 표현: `Note` (octave + pitch class) 또는 MIDI note number(int).
  - 불변식: `lowestNote <= highestNote`, 인간 음역 범위(C0~B8) 내.
- 사용자 엔티티(또는 익명 세션)와 1:1 또는 1:N(이력 보존 시) 관계.
- 도메인 모델 §4 유비쿼터스 랭귀지에 `VoiceRange = 음역대`는 이미 등재됨. 신규 용어 후보: `Note`(음표), `MidiNote`(int 표현).

### 5-2) API 엔드포인트
| Method | Path | 설명 | 인증 | Req | Res |
|---|---|---|---|---|---|
| POST | /api/v1/voice-ranges | 음역대 등록 (같은 sessionId면 덮어쓰기, Q4) | 익명 (sessionId in body) | `VoiceRangeCreateRequest` | `VoiceRangeResponse` (201) |
| GET | /api/v1/voice-ranges/{sessionId} | 음역대 조회 | 익명 | - | `VoiceRangeResponse` (200) / 404 |
| PUT | /api/v1/voice-ranges/{sessionId} | 음역대 갱신 | 익명 | `VoiceRangeUpdateRequest` | `VoiceRangeResponse` (200) / 404 |

- DTO 명명: `docs/ai-harness/08-code-conventions.md` 풀네임 + API별 분리 규칙 준수 (`VoiceRangeCreateRequest`, `VoiceRangeUpdateRequest`, `VoiceRangeResponse`).
- 인증 미들웨어 도입 시 `/{sessionId}` → `/me`로 마이그레이션 후보 (별 PR).

### 5-3) 외부 연동
- D1 결정에 따라 다름:
  - (a) 자가 진단 곡 방식: 외부 연동 없음.
  - (b) 마이크 실측 방식: 브라우저 WebAudio API + 피치 검출(예: `pitchy`, `aubio.js`). 서버 신호처리 시 별도 라이브러리/CPU 부담 검토.
  - (c) 옥타브 분류 선택: 외부 연동 없음.

### 5-4) 데이터 흐름 / 시퀀스 (마이크 실측 시 잠정)
```
[브라우저] 마이크 캡처 → 피치 검출(클라이언트) → (min, max) 추정
   ↓ POST /api/v1/voice-ranges {lowestNote, highestNote, sourceMethod}
[Spring] 검증(범위/단위) → VoiceRange 영속화 → 응답
```
- 원본 오디오 신호는 서버에 보내지 않는다(개인정보 최소화).

### 5-5) DB 마이그레이션
- 신규 테이블 `voice_range`:
  - `id` (PK)
  - `user_id` (FK, nullable — 익명 세션 지원 시 null 허용 또는 session_id 별도 컬럼)
  - `lowest_note_midi` (smallint)
  - `highest_note_midi` (smallint)
  - `source_method` (enum: `SELF_REPORT`, `MIC_MEASURE`, `OCTAVE_PICK` — D1 결정 후 축소 가능)
  - `created_at`, `updated_at`
- 마이그레이션 도구는 별도 ADR에서 결정(Flyway/Liquibase). 1차 PoC 전 `infra` scope PR로 셋업.

### 5-6) 프론트엔드 화면 (해당 시)
- `/voice-range/intro` — 진단 시작/수동 입력 분기
- `/voice-range/diagnose` — D1 결정 방식에 따른 진단 UI
- `/voice-range/result` — 결과 확인 + 보정 + 저장
- 상태 관리는 `web` ADR 확정 후 적용.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR A (docs): 이 spec 자체 — 본 PR.
- [ ] PR B (docs): D1 결정 후 spec 갱신 + 도메인 모델 §5/§6 1차 채움.
- [ ] PR C (infra): DB 마이그레이션 도구 도입(Flyway/Liquibase) — 별도 ADR + 보호 영역이므로 `needs-human-review`.
- [ ] PR D (feat:voice): `VoiceRange` 값 객체 + Repository + 등록/조회 API.
- [ ] PR E (feat:voice): 재측정/갱신 API + 검증 규칙 강화.
- [ ] PR F (feat:web): 음역대 진단/입력 화면 (D1 방식별로 분기).
- [ ] PR G (test): E2E 시나리오(등록 → 조회 → 갱신) RestAssured + 프론트 통합 테스트.

> 분할은 합의 후 조정. C는 PoC 단계에선 JPA `ddl-auto=update`로 미루는 옵션도 검토(D 작업 차단되지 않게).

## 7) 테스트 전략
- **단위**: `VoiceRange` 값 객체의 불변식(저음 ≤ 고음, 범위 제한)과 Note 변환(MIDI ↔ 음표명).
- **통합/Repository**: H2 또는 testcontainers MySQL로 영속화 라운드트립.
- **E2E (필수)**: 등록 → 조회 → 갱신 시나리오, RestAssured 기반 성공 케이스 (`docs/ai-harness/07-testing-guide.md`).
- **프론트**: 진단 흐름 컴포넌트 단위 + (가능 시) Playwright E2E.
- **외부 연동 mock**: 피치 검출 라이브러리는 클라이언트 모듈 수준에서 stub.

## 8) 오픈 질문
> 모든 항목 해소. 본 spec은 `approved`. 새 질문이 생기면 본 테이블에 추가.

| #  | 질문 | 상태 |
|----|------|------|
| Q1 | 음역대 진단 방식 | **결정됨** → §9 (2026-05-20) |
| Q2 | 익명 세션 vs 회원가입 | **결정됨** → §9 (2026-05-20) |
| Q3 | DB 마이그레이션 도구 | **결정됨** → §9 (2026-05-20) |
| Q4 | 음역대 이력 보관 정책 | **결정됨** → §9 (2026-05-20) |

## 9) 결정 로그
> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-20: 초안 작성 (status=draft). D1을 본 spec Q1로 이관. 출처: #1
- 2026-05-20: Q1~Q4 작성자 권고안대로 확정, status=approved. PoC 단계 단순성·학습 속도 우선. 차후 재검토 가능. 출처: #2
  - **Q1 → (d) 하이브리드** — 1차 PoC는 (c) 옥타브 분류만 구현해 가장 빨리 추천 흐름을 닫고, (a) 마이크 실측은 후속 옵션. 정확도는 §3 비기능 요구사항(30초 내 완료)과 트레이드오프.
  - **Q2 → (a) 익명 세션** — PoC 단계엔 회원가입 없이 흐름을 닫는 것이 학습/검증 측면에서 가장 빠르다.
  - **Q3 → (a) Flyway** — 단순/관용적이고 Spring Boot 통합 풍부.
  - **Q4 → (a) 최신 1건** — 분석 가치보다 스키마 단순성이 더 중요한 PoC 단계.
- 2026-05-21: 첫 구현 PR 진입, status=implementing. 출처: #15
  - **API 경로 변경**: spec §5-2의 `/me` → `/{sessionId}` (PoC 인증/세션 미들웨어 미도입으로 명시적 경로 파라미터). spec §5-2 표 갱신.
  - **Q3 Flyway 후순위**: PoC 한정 `ddl-auto=update` 활용. Flyway 도입은 첫 실배포 직전 ADR로.
