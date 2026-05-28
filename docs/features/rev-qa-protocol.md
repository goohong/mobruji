---
feature: rev 세션 QA 실행 검증 프로토콜
slug: rev-qa-protocol
status: implementing
owner: @goohong
scope: infra
related_issues: []
related_prs: [316, 336, 349, 376, 377]
last_reviewed: 2026-05-24
---

# rev 세션 QA 실행 검증 프로토콜

## 1) 개요 (What / Why)
- rev 세션은 머지된 PR(또는 머지 직전 PR)에 대해 **사후 코드 감사**와 **실 QA 실행 검증**을 모두 수행한다.
- "에러를 막는 것이 1순위"라는 사용자 결정(2026-05-22)에 따라, 코드 리뷰만으로 잡히지 않는 다음 부류의 사고를 차단한다:
  - 런타임 에러 (NPE, 직렬화 실패, lazy init 등)
  - 환경 의존 실패 (Flyway baseline, 포트 충돌, env 변수 누락)
  - API 통합 회귀 (FE→BE 계약 불일치, CORS, 인증 미들웨어)
  - 결정성/p95 회귀 (spec 명시된 비기능 조건 위반)
- 정형 룰이 없으면 사이클마다 위임 프롬프트가 흔들리고 QA 누락이 생긴다 → 이 문서가 단일 참조원이다.

## 2) 사용자 시나리오
- **시나리오 1 (PR 머지 후)**: rev 세션이 머지 직후 트리거되어 develop tip에서 QA를 수행 → 회귀 발견 시 maestro에 핫라인 보고.
- **시나리오 2 (release gate)**: `develop → main` 머지 전 rev 세션이 release-candidate QA를 수행 → 모든 reviewed:claude PR이 QA pass여야 사용자에게 release 컨펌 요청.
- **시나리오 3 (idle 사이클)**: 머지된 PR이 없을 때 rev가 develop 전체 회귀 QA를 수행 → 누적 회귀를 발굴.

## 3) 요구사항
### 기능 요구사항
- [ ] PR 범주별 QA 강제 매트릭스를 명시한다 (코드 변경 / auth&security / spec&docs).
- [ ] QA 단계는 결정 트리로 표현해 rev sub-agent가 prompt 없이 자체 판단 가능.
- [ ] smoke 시나리오 라이브러리를 도메인별로 정의 (음역 측정 / 추천 / 좋아요 / 이력).
- [ ] QA 결과는 PR 코멘트로 남기고 형식은 `🟢/🟡/🔴 + QA 결과 1줄`로 고정.
- [ ] rev는 워크트리에서 파일을 수정하지 않는다 (pre-push hook 차단됨). 임시 스크립트는 `/tmp/*` 또는 stdin heredoc만 허용.
- [ ] release gate 차단: 🔴 QA 결과는 release PR을 막고 maestro이 fix 사이클을 launch한다.
- [ ] 환경 선택 트리 (local 3-tier / dev 서버 / staging) 명시.

### 비기능 요구사항
- **재현성**: 모든 smoke 시나리오는 명령 단위로 기록되어 사람이 그대로 복사해 재현 가능해야 한다.
- **자율성**: rev sub-agent는 prompt 외 추가 질문 없이 본 문서만 보고 QA를 수행할 수 있어야 한다.
- **wall-clock**: 단일 PR QA는 10분 이내 (smoke 시나리오 1~3개 한정). release-candidate QA는 30분 이내.
- **결과 추적성**: QA pass/fail은 PR 코멘트 검색으로 추적 가능 (`gh pr view <N> --comments | grep "QA:"`).

## 4) 범위 / 비범위
### 포함
- rev sub-agent가 따라야 할 QA 결정 트리, 시나리오, 결과 형식.
- 환경 선택 가이드 (local 3-tier 기본, 외부 의존은 dev 서버).
- QA 도구 선택 (curl / httpie / Playwright / RestAssured 부재 시 임시 스크립트).
- maestro이 rev sub-agent를 launch할 때 prompt에 박을 핵심 룰.

### 제외 (Out of Scope)
- **CI 워크플로우 자동화**: rev QA는 사람(sub-agent) 트리거가 기본. 자동화는 추후 별도 spec.
- **부하 테스트**: k6 부하 테스트는 별도 워크플로우(`load-test.yml`)가 담당. rev QA는 smoke 수준만.
- **사람 QA**: 사용자가 직접 클릭하는 manual QA는 본 spec 범위 밖.
- **외부 staging 환경 셋업**: 별도 인프라 spec(`deployment-infrastructure-spec`)에서 다룸.

## 5) 설계

### 5-1) PR 범주별 QA 강제 매트릭스

| 범주 | 판별 기준 | QA 단계 | 환경 |
|---|---|---|---|
| **A. 코드 변경 (BE)** | `backend/**` diff 존재, `backend/src/main` 변경 | (1) `./gradlew test` 회귀, (2) bootRun + 변경 endpoint smoke (curl), (3) FE 연동 endpoint면 FE 화면 smoke | local 3-tier |
| **B. 코드 변경 (FE)** | `web/**` diff 존재, `web/src` 또는 `web/app` 변경 | (1) `npm run lint && typecheck && test && build`, (2) `npm run dev` + Playwright/curl로 SSR 응답 확인, (3) BE 의존이면 통합 흐름 확인 | local 3-tier |
| **C. 코드 변경 (FE+BE 통합)** | A와 B 동시 또는 spec 상 contract 변경 | A + B + 통합 시나리오 (해당 도메인 smoke 시나리오 §5-3) | local 3-tier |
| **D. auth & security** | `SessionAuthGuard`, `*AuthFilter`, `application*.yml` security 섹션, ADR 0011/0013 관련 | A 또는 B + 인증 우회 시도 (헤더 누락/위조 토큰) + 401/403 응답 검증 | local 3-tier |
| **E. DB 마이그레이션** | `backend/src/main/resources/db/migration/**` 추가 | A + Flyway clean→migrate 재현 (`./gradlew flywayMigrate` 또는 docker compose 재기동) | local 3-tier (DB 재기동) |
| **F. spec & docs only** | `docs/**`, `README.md`, `.md` 파일만 변경 | **QA 생략**. 코드 감사만 수행. | — |
| **G. CI/infra only** | `.github/workflows/**`, `docker-compose*.yml` 변경 | dry-run으로 워크플로우 트리거(가능한 경우) 또는 변경 라인 사람 리뷰만 | — |
| **H. 비기능 spec 위반 위험** | spec에 결정성/p95/관측성 요구가 있는 도메인(`recommendation`, `voice`) | A + spec §3 비기능 요구사항 한 줄씩 검증 | local 3-tier |

**우선순위 룰**: 한 PR이 여러 범주에 걸치면 **상위 알파벳 우선**. A+B+D 합쳐진 PR은 모두 수행.

### 5-2) QA 결정 트리

```
1. PR diff 확인 (gh pr diff <N> --name-only)
   ├─ docs/**만 → 범주 F → QA 생략, 감사만
   ├─ .github/workflows/** 또는 docker-compose → 범주 G → dry-run 또는 라인 리뷰
   └─ 코드 파일 포함 → 다음 단계
2. 변경 영역 분류
   ├─ backend/src/main + web/src → 범주 C (통합)
   ├─ backend/src/main만 → 범주 A
   ├─ web/src만 → 범주 B
   └─ db/migration 포함 → 범주 E 추가
3. 보강 조건
   ├─ auth/security 키워드 매칭 → 범주 D 추가
   ├─ 결정성/p95 spec이 있는 도메인 → 범주 H 추가
   └─ (위 모든 범주의 QA를 순차 수행)
4. 결과 보고
   ├─ 모두 pass → 🟢 + 시나리오 요약 1줄
   ├─ minor (nit/drift) → 🟡 + 무엇이 걸렸는지 1줄
   └─ blocking (런타임 에러/spec 위반) → 🔴 + 재현 명령 + 영향 spec
```

### 5-3) Smoke 시나리오 라이브러리

각 시나리오는 **로컬 3-tier 가동 후** 실행. local 3-tier 가동은 `docs/runbooks/local-3tier-setup.md` 참조.

**시나리오 작성 룰**: 필드명/payload는 §5-8 contract 표를 그대로 인용한다. 본 §5-3은 흐름 + Pass 조건 중심.

#### S1. 음역 측정 (voice)
```bash
# 1. 익명 세션 생성 (sessionId는 익명 세션 발급 endpoint가 미구현이면 임의 UUID 사용)
SID=$(uuidgen)

# 2. 음역 입력 (§5-7-1 payload)
curl -s -X POST http://localhost:8080/api/v1/voice-ranges \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"lowestNoteMidi\":48,\"highestNoteMidi\":76,\"sourceMethod\":\"SELF_REPORT\"}" | jq

# 3. 음역 조회 (§5-7-2)
curl -s http://localhost:8080/api/v1/voice-ranges/$SID | jq

# 4. 측정 이력 조회 (§5-7-6, X-Session-Id 헤더 필수)
curl -s -H "X-Session-Id: $SID" \
  http://localhost:8080/api/v1/sessions/$SID/voice-range-history | jq

# Pass 조건: 2번 201 + 3번 200(같은 lowestNoteMidi/highestNoteMidi) + 4번 200(snapshot 1건 이상)
# 헤더 누락 시 4번이 401 반환하는지 함께 확인 (§S6 통합 가능)
```

#### S2. 추천 (recommendation)
```bash
# 음역 입력된 세션으로 추천 호출 (§5-7-4 payload)
curl -s -X POST http://localhost:8080/api/v1/recommendations \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"voiceRangeLow\":48,\"voiceRangeHigh\":76,\"mood\":\"UPBEAT\"}" | jq

# Pass 조건:
# - HTTP 201, requestId 발급, recommendations 배열 non-empty
# - 결정성: 같은 요청 2회 호출 시 동일 순서 (recommendation-algorithm-v1.md §3)
# - p95 < spec 정의된 임계 (p95-regression-guard.md)
```

#### S3. 좋아요 / 북마크 (recommendation feedback)
```bash
# 추천에서 받은 songId 사용 (§5-7-4 응답의 recommendations[*].songId)
SONG_ID=1   # S2 응답에서 추출

# 좋아요 토글 (§5-7-10)
curl -s -X POST http://localhost:8080/api/v1/likes \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"songId\":$SONG_ID}" | jq

# 좋아요 리스트 조회 (§5-7-8, X-Session-Id 헤더 필수)
curl -s -H "X-Session-Id: $SID" \
  "http://localhost:8080/api/v1/sessions/$SID/likes?page=0&size=20" | jq

# 북마크도 동일 패턴 (§5-7-11 / §5-7-9)

# Pass 조건: like 토글 후 list에 노출(`responses` 배열), page/size/totalCount/hasNext 필드 존재
```

#### S4. 이력 조회 (recommendation history)
```bash
# §5-7-7, X-Session-Id 헤더 필수
curl -s -H "X-Session-Id: $SID" \
  http://localhost:8080/api/v1/sessions/$SID/recommendation-history | jq

# Pass 조건: 200 + recommendationHistoryResponses 배열에 S2 호출이 최신순으로 노출
```

#### S5. FE 통합 흐름 (web)
```bash
# /home → /voice → /recommendations → /history 동선
# Playwright 또는 curl + grep으로 SSR 응답 확인

# 익명 세션 cookie 자동 발급 확인
curl -s -i http://localhost:3000/ | grep -i "set-cookie"

# 음역 페이지 로드
curl -s http://localhost:3000/voice | grep -E "음역|voice-range"

# Pass 조건: 각 라우트 200 + 핵심 키워드 포함 + 콘솔 에러 0건 (Playwright 사용 시)
```

#### S6. auth 우회 시도 (D 범주 강제)
```bash
# 헤더 누락 → 401
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/api/v1/sessions/$SID/likes
# 기대: 401

# 위조 sessionId → 401 (path와 header 불일치)
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-Session-Id: 00000000-0000-0000-0000-000000000000" \
  http://localhost:8080/api/v1/sessions/$SID/likes
# 기대: 401

# voice-range-history / recommendation-history 도 같은 가드 적용 — 같은 패턴으로 확인
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/api/v1/sessions/$SID/voice-range-history
# 기대: 401

# admin endpoint 인증 (예: song stats)
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:8080/api/v1/songs/stats
# 기대: 401 (admin token 없음)
```

### 5-4) 환경 선택 가이드

| 환경 | URL | 언제 쓰나 | 셋업 / 운영 |
|---|---|---|---|
| **local 3-tier (기본)** | `http://localhost:3000` + `http://localhost:8080` | 모든 PR QA의 1차 검증 (격리된 컨텍스트, 즉시 가동) | `docs/runbooks/local-3tier-setup.md` |
| **dev 서버 (NCP)** | `http://101.79.20.94/` (web) + `http://101.79.20.94/api/...` (backend) | 외부 의존(Spotify/MusicBrainz/YouTube) 통합 검증, 사용자 dogfooding, develop tip 회귀 | `docs/runbooks/ncp-maestro-setup.md` §H (Phase 4) |
| **staging** | (미구축) | release-candidate 최종 검증 | (별도 인프라 spec — `deployment-infrastructure.md` Hetzner CX22 머지 후) |

### 5-4-1) dev 서버 활용 가이드 (Phase 4 머지 후)

dev 서버는 develop 머지마다 GitHub Actions `cd-dev.yml` 가 자동 배포한다 (`docs/features/ncp-dev-deployment.md` §5-3). 사용 시점:

- **외부 의존 PR**: Spotify Audio Features / MusicBrainz / YouTube API 등 실제 응답을 봐야 하는 회귀. local 3-tier mock 으로는 부족할 때.
- **사용자 dogfooding**: 모바일 브라우저로 직접 사용해보기 (`http://101.79.20.94/`).
- **develop tip 통합 회귀**: 머지된 PR 묶음을 통합 환경에서 한 번 더 확인 — rev idle 사이클 (§0-5) 의 자체 백로그.

QA 절차 (rev sub-agent 또는 사용자):
```bash
# 1) backend liveness
curl -sS http://101.79.20.94/actuator/health/liveness

# 2) 추천 API smoke (관리 토큰 불요)
curl -sS -X POST http://101.79.20.94/api/v1/recommendations \
  -H 'Content-Type: application/json' \
  -d '{"voiceRange":{"low":"C3","high":"E4"},"gender":"male"}' | jq

# 3) web 응답 (SSR 200)
curl -sS -o /dev/null -w "%{http_code}\n" http://101.79.20.94/

# 4) container 상태 (NCP SSH 후)
docker compose -f docker-compose.dev.yml --env-file .env.dev ps
```

dev 환경 사용 시 보고서 헤더:
```
- 환경: dev (NCP, commit <sha>)
- container ps: 4/4 healthy
- BE p95 (smoke): ...ms
- 결과: 🟢 / 🟡 / 🔴
```

### 5-4-2) 환경 fallback
- dev 서버가 down 이면 (`/actuator/health/liveness` 5xx 또는 timeout) → local 3-tier 로 fallback + PR 코멘트에 명시 (`dev 환경 down — local 3-tier 로 검증`).
- maestro 가 dev CD 자동 롤백 (Phase 4 spec §5-3) 발동 시 GitHub Actions workflow run 결과를 봐서 rev sub-agent 가 dev URL 활용 가능 여부 판단.

### 5-5) QA 도구

| 도구 | 용도 | 비고 |
|---|---|---|
| `curl` | HTTP smoke (기본) | jq 조합 권장 |
| `httpie` | 가독성 필요한 경우 | optional |
| `./gradlew test` | BE 회귀 | 범주 A 필수 |
| `./gradlew bootRun` | BE 기동 | local 3-tier 셋업 시 |
| `npm run dev` | FE 기동 | local 3-tier 셋업 시 |
| `npm run build` | FE 빌드 회귀 | 범주 B 필수 |
| `Playwright` | E2E 시나리오 | 추후 도입. 현재는 curl + grep 우선 |
| **임시 스크립트** | 복잡한 QA 시나리오 | `/tmp/*.sh`만 허용. 워크트리 안 파일 생성 금지 (pre-push hook 차단). |

**rev 워크트리 파일 수정 금지 룰** (`scripts/git-hooks/pre-push`로 강제):
- 워크트리 안에는 어떤 파일도 신규 생성·수정하지 않는다.
- 임시 스크립트는 `/tmp/rev-qa-*.sh` 또는 stdin heredoc(`bash <<'EOF' ... EOF`)으로 실행.
- 결과는 PR 코멘트로만 남긴다.

### 5-6) 결과 리포트 형식

PR 코멘트로 남길 때 다음 형식을 고정한다 (검색 가능성):

```
QA: 🟢 PASS — [범주 A] gradlew test 회귀 OK + S2 추천 endpoint 결정성 2회 OK

세부:
- 범주: A (backend code 변경)
- 환경: local 3-tier (commit <sha>)
- 시나리오: S2 추천
- 명령: `curl POST /api/v1/recommendations` 2회 → 응답 ID 순서 동일
- 측정: p95 ~120ms (spec 임계 500ms 이내)
```

또는:

```
QA: 🔴 BLOCK — [범주 D] /api/v1/sessions 인증 우회 가능, ADR-0013 위반

세부:
- 범주: D (auth & security)
- 재현: `curl -X POST /api/v1/sessions` 헤더 없이 200 반환 (기대 401)
- 영향 spec: docs/features/anonymous-session-lifecycle.md §3 비기능
- 권장 fix: SessionAuthGuard에 /sessions POST 제외 룰 검토
```

마커:
- 🟢 PASS — 모든 시나리오 통과
- 🟡 NOTE — minor drift/nit. release gate 통과 가능 but 다음 사이클 fix 권장
- 🔴 BLOCK — 런타임 에러, spec 위반, 회귀. release gate 차단.

### 5-7) Endpoint payload / 응답 contract (rev 첫 try 가이드)

rev 22 첫 적용 피드백 — smoke 시나리오 §5-3은 **개념적 흐름** 중심이라 매 사이클마다 필드명 오류(`lowMidi` vs `lowestNoteMidi`)로 400을 만들고 backend 코드를 grep하게 됨. 본 절은 그 1차 grep을 대체한다.

**중요**: 본 절의 필드명은 backend DTO를 단일 진실 소스로 한다. 코드가 바뀌면 본 절도 같은 PR에서 갱신해야 한다 (CLAUDE.md §4 도메인/DDD 룰 적용).

#### 5-7-1) `POST /api/v1/voice-ranges` (음역 등록/교체)
- DTO: `VoiceRangeCreateRequest`
- 인증: 없음 (body sessionId)
- Request:
  ```json
  {"sessionId":"<SID>","lowestNoteMidi":48,"highestNoteMidi":76,"sourceMethod":"SELF_REPORT"}
  ```
- `sourceMethod` 허용 enum: `SELF_REPORT` | `OCTAVE_PICK` | `MIC_MEASURE`
- MIDI 범위: 12~119
- Response 201: `{id, sessionId, lowestNoteMidi, highestNoteMidi, sourceMethod, createdAt, updatedAt}`

#### 5-7-2) `GET /api/v1/voice-ranges/{sessionId}` (음역 조회)
- 인증: 없음
- Response 200: `VoiceRangeResponse` (위와 동일 shape)

#### 5-7-3) `PUT /api/v1/voice-ranges/{sessionId}` (음역 수정)
- DTO: `VoiceRangeUpdateRequest`
- 인증: 없음 (현재 — auth 적용 후속)
- Request: `{"lowestNoteMidi":48,"highestNoteMidi":78,"sourceMethod":"OCTAVE_PICK"}` (필드명 PR별 확인)

#### 5-7-4) `POST /api/v1/recommendations` (추천 생성)
- DTO: `RecommendationCreateRequest`
- 인증: 없음 (body sessionId)
- Request (최소):
  ```json
  {"sessionId":"<SID>","voiceRangeLow":48,"voiceRangeHigh":76,"mood":"UPBEAT"}
  ```
- Request (전체):
  ```json
  {"sessionId":"<SID>","voiceRangeLow":48,"voiceRangeHigh":76,"mood":"UPBEAT","preferredBpm":120,"excludeSongIds":[1,2]}
  ```
- `mood` 허용 enum: `UPBEAT` | `CALM` | `EMOTIONAL` | `POWERFUL` | `GROOVY` | `NOSTALGIC` (Mood.java 단일 소스)
- `preferredBpm` 범위: 30~300 (optional)
- Response 201: `{requestId, recommendations: [RecommendedSongResponse...]}`

#### 5-7-5) `GET /api/v1/recommendations/{requestId}` (추천 단건 조회)
- 인증: 없음 (현재)
- Response 200: `RecommendationResponse` (위와 동일 shape)

#### 5-7-6) `GET /api/v1/sessions/{sessionId}/voice-range-history`
- 인증: **`X-Session-Id` 헤더 필수** (path와 일치). 누락/blank/불일치 → 401
- Response 200: `{voiceRangeSnapshotResponses: [...]}` (snapshot 시계열)

#### 5-7-7) `GET /api/v1/sessions/{sessionId}/recommendation-history`
- 인증: **`X-Session-Id` 헤더 필수**
- Response 200: `{recommendationHistoryResponses: [...]}` (createdAt DESC)

#### 5-7-8) `GET /api/v1/sessions/{sessionId}/likes?page=0&size=20`
- 인증: **`X-Session-Id` 헤더 필수**
- `page >= 0`, `1 <= size <= 100` (위반 시 400)
- Response 200: `{responses: [...], page, size, totalCount, hasNext}`

#### 5-7-9) `GET /api/v1/sessions/{sessionId}/bookmarks?page=0&size=20`
- 인증: **`X-Session-Id` 헤더 필수**
- 위와 동일 shape

#### 5-7-10) `POST /api/v1/likes` (좋아요 토글)
- DTO: `LikeToggleRequest`
- 인증: 현재 body sessionId (header guard는 후속 이슈)
- Request: `{"sessionId":"<SID>","songId":1}` (`songId` Positive Long)
- Response 200: `LikeToggleResponse` (`liked: true/false`)

#### 5-7-11) `POST /api/v1/bookmarks` (북마크 토글)
- DTO: `BookmarkToggleRequest`
- 인증: 현재 body sessionId
- Request: `{"sessionId":"<SID>","songId":1}`
- Response 200: `BookmarkToggleResponse` (`bookmarked: true/false`)

#### 5-7-12) 인증 헤더 한 줄 정리
- `X-Session-Id: <SID>` — `/api/v1/sessions/{sessionId}/**` 패턴은 모두 필수 (SessionAuthGuard).
- `Authorization: Bearer <MOBRUJI_ADMIN_TOKEN>` — `/api/v1/songs/stats` 등 admin endpoint. 토큰은 env로 주입 (`docs/runbooks/local-3tier-setup.md` §2-2).

> **새 endpoint 추가 시**: backend PR에서 본 §5-7 을 같은 PR로 갱신한다. 갱신 없는 endpoint는 rev sub-agent가 grep을 다시 하게 되어 wall-clock 손실 + 미QA 위험.

### 5-8) release gate 연계

`develop → main` release 머지 전 rev 세션이 다음을 수행:
1. `gh pr list --base develop --state merged --search "merged:>=<이전 release 이후> -label:reviewed:claude"` → 미QA PR 색출
2. 미QA PR마다 본 spec §5-1 매트릭스 따라 QA 수행
3. 모든 PR이 🟢 또는 🟡일 때만 maestro에 release 컨펌 보고
4. 🔴가 1건이라도 있으면 → maestro에 fix 사이클 launch 요청, release 차단

QA pass PR에는 `reviewed:claude` 라벨 부여 (라벨 없으면 release gate가 차단).

## 6) 작업 분할
이 spec 자체는 단일 PR. 후속 운영 변경이 필요하면 별 PR로:
- [x] PR 1: 본 spec + runbook §2 갱신 + 03-quality-gates §8 추가 + local-3tier 가이드 (이 PR)
- [ ] PR 2 (선택): rev sub-agent prompt 템플릿(`actors/sub-agent.md`)에 본 spec 참조 박기
- [ ] PR 3 (선택): release gate workflow에 `reviewed:claude` 라벨 강제 (현재는 사회적 룰)

## 7) 테스트 전략
- 본 spec은 문서이므로 테스트 대상 아님.
- 실효성 검증: 머지 후 첫 rev 사이클이 본 spec만 보고 prompt 추가 질문 없이 QA를 끝낼 수 있는지로 측정. 못 끝내면 spec 보강.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | rev sub-agent prompt에 본 spec 경로를 박을 때 한 줄 vs §5-1 매트릭스 inline? | (a) 경로만 / (b) 매트릭스 inline / (c) 둘 다 | @goohong / 첫 rev 사이클 후 |
| Q2 | release gate에 `reviewed:claude` 라벨 강제를 workflow로 자동화? | (a) 사회적 룰 유지 / (b) workflow 추가 | @goohong / release 2회 후 |
| Q3 | dev 서버 셋업 spec 머지 전까지 외부 API 의존 PR QA를 어떻게? | (a) 🟡 보류 / (b) mock 강제 / (c) 케이스별 사용자 결정 | @goohong / 외부 API PR 발생 시 |

## 9) 결정 로그
- 2026-05-22: 초안 작성 (status=active). 사용자 보강 결정 ("에러를 막는 것이 1순위, rev가 실 QA 실행 검증도 담당")을 정형화. plan 사이클 37.
- 2026-05-22: rev 22 첫 적용 피드백 반영. §5-3 smoke 시나리오의 endpoint/필드명을 실 DTO와 일치시키고, §5-7 Endpoint payload contract 절을 신설(endpoint별 최소 payload + enum/헤더). 기존 release gate 절은 §5-8로 이동. plan 사이클 39.
