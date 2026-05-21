---
feature: 운영 관측성 베이스라인 (핵심 카운터 + p95 + 수집 스택)
slug: observability-baseline
status: draft
owner: @goohong
scope: infra
related_issues: [242, 62, 68, 69, 71, 209]
related_prs: []
last_reviewed: 2026-05-22
---

# 운영 관측성 베이스라인 (핵심 카운터 + p95 + 수집 스택)

## 1) 개요 (What / Why)

- v0.2~v0.3 진행 동안 `like.created`, `recommendation.requested`, `song.audio.backfill.*`, `voice.range.snapshot.inserted` 등 핵심 카운터가 PR 별로 산발적으로 추가되고 있다. 응답시간 p95 회귀 가드(#62), MusicBrainz/Spotify 외부 호출(#68/#69) 도 곧 카운터를 추가한다.
- 도구/네이밍 컨벤션이 결정되지 않으면 PR마다 metric 이름/라벨이 어긋나 대시보드/알림이 망가진다. **v0.3 P2 안에 합의를 끝내 P2 후속 PR(#62/#68/#69)이 그 컨벤션을 따르도록 강제**한다.
- v0.3 베이스라인 = "메트릭 수집 + 핵심 카운터 + p95 매트릭스 + 1차 수집기/알림". 분산 트레이싱·SaaS 통합·SLO 대시보드는 **v0.4 이후로 분리**.

## 2) 사용자 시나리오 (운영자/개발자 액터)

- (S1) **회귀 감지**: be 가 추천 알고리즘 v3 를 머지한 후 운영자가 `mobruji.recommendation.request.duration` p95 가 200ms → 480ms 로 튀는 것을 대시보드에서 본다 → 해당 PR 롤백 결정.
- (S2) **외부 API 장애 감지**: MusicBrainz 호출이 5xx 를 연속 반환할 때 `mobruji.external.musicbrainz.request{outcome="error"}` 카운터가 1분에 N회 이상이면 Discord webhook 으로 알림.
- (S3) **신호 검증**: 큐레이션 100곡 시드(#71) 안정화 후 `mobruji.recommendation.result.size` 분포로 "0건 응답" 비율 추적.
- (S4) **PII 누출 방지**: 모든 메트릭 라벨에 `sessionId`/`userId`/원문 음역대 값을 절대 넣지 않는다 (CLAUDE.md §4 보안).

## 3) 요구사항

### 기능 요구사항

- [ ] **메트릭 수집기**: Spring Boot Actuator + Micrometer 노출은 이미 골격이 있다 (`10-observability.md §1`). 본 spec 은 그 위에 도메인 카운터 + 수집기 endpoint scrape 정책을 결정한다.
- [ ] **필수 카운터** (§5-3 표) 모두 코드에 신설/통일. PR 별 산발 도입을 막기 위해 본 spec 머지 후 후속 PR 들이 표를 보고 작성.
- [ ] **p95 매트릭스** (§5-4) 에 정의된 endpoint 는 `http.server.requests` 히스토그램을 활성화하고 응답시간 p95 회귀 가드(#62) 가 같은 키로 검증한다.
- [ ] **수집기 운영**: §5-5 에 결정된 스택(ADR-0012) 으로 메트릭 scrape + 단순 대시보드 1개 (recommendation/voice/song 도메인 카운터 + p95).
- [ ] **알림**: §5-6 에 정의된 임계치 초과 시 Discord webhook(#모부르지 채널) 으로 통지. webhook URL 은 환경변수 (`MOBRUJI_ALERT_WEBHOOK_URL`) 외부화.
- [ ] **PII 마스킹 룰**: §5-7 에 정의된 라벨 화이트리스트 외 키를 metric tag 로 쓰지 않는다. 코드 리뷰 가드.
- [ ] **헬스체크 노출 룰**: `/actuator/health` 는 외부 미노출, `/actuator/health/liveness` 와 `/readiness` 만 별도 인증 후 노출 가능 (운영 배포 시점 결정 — v0.3 안에 정책만 선언).

### 비기능 요구사항

- **결정성**: 카운터 이름은 본 spec 표가 단일 진실. 코드/대시보드/알림이 같은 이름을 참조.
- **응답시간 영향**: Micrometer counter/timer 추가 오버헤드는 endpoint p95 에 +5ms 이내. 측정은 #62 회귀 가드가 동시에 검증.
- **설정 외부화**: 수집기 endpoint, 알림 webhook, 인증 토큰 모두 환경변수. `application.yml` 에 default 두지 않음 (운영 fail-fast).
- **관측성**: 본 베이스라인 자체가 관측성. 별도 메타 카운터는 만들지 않는다.
- **보안**: §5-7 PII 마스킹 룰을 어긴 라벨이 발견되면 즉시 PR 차단 (코드 리뷰 책임 — 자동화는 v0.4 후보).

## 4) 범위 / 비범위

### 포함

- Micrometer 카운터/타이머/게이지 네이밍 컨벤션 + 필수 카운터 목록 + 라벨 화이트리스트
- p95 측정 endpoint 매트릭스 (#62 회귀 가드와 동기화)
- 수집기 스택 선택 (ADR-0012) + 단순 대시보드 1개
- Discord webhook 기반 임계치 알림 1~2 규칙 (외부 API 에러율 등)
- `10-observability.md` Phase 1 룰 갱신 (베이스라인 반영)

### 제외 (Out of Scope)

- **분산 트레이싱 (OpenTelemetry agent)**: 백엔드 단일 서비스라 baggage/span 분산이 필요 없다. v0.4 web ↔ be ↔ Python audio runner 연결 시점에 재검토.
- **SaaS 유료 플랜 (Datadog/New Relic 유료)**: 무료 티어 한도 안에서 결정. 유료 전환은 사용량 증가 후 별도 ADR.
- **SLO/SLA 정의**: 운영 사용자 트래픽이 의미 있는 수준이 되기 전에는 무의미. v0.4 이후.
- **로그 집계 (ELK/Loki)**: v0.3 까지는 단일 인스턴스 stdout + journalctl. 다중 인스턴스 운영 결정 후 별도 spec.
- **프론트엔드(web) RUM**: 별 도메인. v0.4 계정 시스템(#243) 머지 후 같이 결정.
- **PII 마스킹 자동화 (lint rule)**: 본 spec 은 룰만 선언. enforcement 자동화는 후속 이슈.

## 5) 설계

### 5-1) 도메인 모델

- 본 spec 은 도메인 엔티티를 추가하지 않는다. **Micrometer MeterRegistry 만 주입**하여 application service / controller / scheduled job 에서 counter/timer 갱신.
- `06-domain-model.md` §4 유비쿼터스 랭귀지에 신규 용어 등재 없음 (모두 기존 도메인 액션 이름의 metric 변환).

### 5-2) 메트릭 네이밍 컨벤션

```
mobruji.<domain>.<action>[.<state>]      # counter / gauge
mobruji.<domain>.<action>.duration       # timer (자동으로 .seconds 단위)
mobruji.external.<vendor>.<action>       # 외부 API 호출 (vendor=musicbrainz, spotify, youtube, ...)
mobruji.job.<jobName>.<state>            # 스케줄 잡 (state=started|completed|failed)
```

규칙:

1. **prefix `mobruji.`** 모든 도메인 메트릭. Micrometer 가 노출하는 시스템 메트릭(`http.server.requests`, `jvm.*`, `hikaricp.*`) 과 충돌 방지.
2. **소문자 + dot 구분**. snake_case/camelCase 금지.
3. **domain** = `06-domain-model.md` 컨텍스트 이름: `recommendation`, `voice`, `song`, `user`, `recommendationhistory`, `voiceranges`, `like`, `bookmark`.
4. **action** = 도메인 동사: `requested`, `created`, `updated`, `deleted`, `analyzed`, `snapshotted`, `backfilled`.
5. **state** (선택) = 결과 분기: `success`, `error`, `empty`, `cache_hit`, `cache_miss`. 결과 분기가 라벨에 들어가는 경우 state 접미사 생략하고 라벨로 표현.
6. **duration** suffix = timer 만 사용. counter 에는 붙이지 않는다.
7. **`mobruji.audit.*`** = §5-7 PII 마스킹 위반 같은 메타 가드용 예약 prefix (v0.4 lint 자동화 시 사용).

### 5-3) 필수 카운터 목록 (v0.3 베이스라인)

| Metric | Type | 라벨 | 의미 | 신설/기존 |
|---|---|---|---|---|
| `mobruji.recommendation.requested` | counter | `gender`, `mood` | 추천 API 호출 횟수 | 기존 (#218 등) — 표 따라 네이밍 통일 |
| `mobruji.recommendation.request.duration` | timer | `gender`, `mood`, `outcome` | 추천 API 응답시간 (p50/p95/p99) | 신설 (#62 트리거) |
| `mobruji.recommendation.result.size` | distribution summary | — | 추천 결과 곡 수 분포 (0건 비율 추적) | 신설 |
| `mobruji.recommendationhistory.like.created` | counter | — | 좋아요 등록 | 기존 (#237) |
| `mobruji.recommendationhistory.like.deleted` | counter | — | 좋아요 취소 | 기존 (#237) |
| `mobruji.recommendationhistory.bookmark.created` | counter | — | 북마크 등록 | 기존 (#237) |
| `mobruji.recommendationhistory.bookmark.deleted` | counter | — | 북마크 취소 | 기존 (#237) |
| `mobruji.voice.range.input.created` | counter | `inputSource` (manual/auto) | 음역 입력 1회 | 기존 |
| `mobruji.voice.range.snapshot.inserted` | counter | — | snapshot 누적 1건 | 기존 (#231) |
| `mobruji.voice.range.history.requested` | counter | `outcome` (success/empty/unauthorized) | history endpoint 호출 | 신설 |
| `mobruji.song.audio.backfill.requested` | counter | — | 스케줄 backfill 1회 trigger | 기존 (#235) |
| `mobruji.song.audio.backfill.success` | counter | — | backfill 성공 1건 | 기존 |
| `mobruji.song.audio.backfill.failed` | counter | `reason` (python/io/parse/timeout) | backfill 실패 1건 | 기존 — `reason` 라벨 신설 |
| `mobruji.song.audio.analysis.duration` | timer | — | librosa 분석 1건 처리 시간 | 신설 (CI 모니터링 #209 와 연계) |
| `mobruji.external.musicbrainz.request` | counter | `outcome` (success/error/ratelimited) | MB 호출 1회 | 신설 (#68 트리거) |
| `mobruji.external.musicbrainz.request.duration` | timer | `outcome` | MB 호출 응답시간 | 신설 (#68) |
| `mobruji.external.spotify.request` | counter | `outcome` | Spotify 호출 1회 | 신설 (#69 트리거) |
| `mobruji.external.spotify.request.duration` | timer | `outcome` | Spotify 호출 응답시간 | 신설 (#69) |
| `mobruji.job.audio_backfill.scheduled` | counter | — | 스케줄러 시작 시점 | 기존 |
| `mobruji.cache.hits` | counter | `cache` (song_meta 등) | 캐시 적중 | 기존 (현 룰 문서) |
| `mobruji.cache.misses` | counter | `cache` | 캐시 미스 | 기존 |

자동 노출(Micrometer 기본): `http.server.requests`, `jvm.*`, `hikaricp.*`, `system.*` — 본 표 외.

### 5-4) p95 측정 endpoint 매트릭스 (#62 동기화)

| Endpoint | Method | 목표 p95 | 비고 |
|---|---|---|---|
| `/api/v1/recommendations` | POST | **300ms** | 추천 v1/v2 산정 + DB 조회. #62 회귀 가드 대상 |
| `/api/v1/recommendations/{id}/like` | POST/DELETE | 150ms | 단순 INSERT/DELETE |
| `/api/v1/recommendations/{id}/bookmark` | POST/DELETE | 150ms | 단순 INSERT/DELETE |
| `/api/v1/sessions/{id}/voice-range-history` | GET | 200ms | snapshot 조회 + 정렬 |
| `/api/v1/sessions/{id}/recommendation-history` | GET | 250ms | history 페이지네이션 |
| `/api/v1/voice-range/auto-measure` | POST | (외부 의존 — 별도 책정) | librosa 호출 — v0.3 후반 결정 |
| `/api/v1/songs/{id}/stats` | GET | 100ms | admin only, 단건 |

- p95 위반 시 #62 회귀 가드가 빌드 fail. 목표값은 본 spec 의 단일 진실, 변경은 PR 로 본 spec 갱신.
- `http.server.requests` 의 `uri` 태그가 endpoint 매칭 키. Spring `@RequestMapping` 패턴 그대로 (path variable 마스킹).

### 5-5) 수집기 스택 — ADR-0012 로 분리

- 후보:
  - **(A) Prometheus + Grafana self-hosted** (단일 인스턴스 docker-compose)
  - **(B) Grafana Cloud Free** (10k metrics 무료, 14일 retention)
  - **(C) Sentry Performance Free** (5k tx/month — 너무 적음)
  - **(D) 로그 기반 (loki/stdout)** — 카운터를 로그로 찍어 grep
- **결정: (B) Grafana Cloud Free 1차 채택**. ADR-0012 참조. 운영 인스턴스 1개 + 트래픽 미미한 상황에 self-host 운영 부담이 더 크고, 무료 한도 안.
- 후속 ADR 옵션 명시: 무료 한도 초과 또는 latency 민감해지면 (A) self-host 로 이관 검토.

### 5-6) 알림 규칙 (1차)

| 규칙 | 트리거 | 채널 | 우선순위 |
|---|---|---|---|
| 외부 API 에러율 | `mobruji.external.*{outcome="error"}` 1분 sum >= 5 | Discord webhook (#모부르지) | P1 |
| 추천 p95 임계 초과 | `mobruji.recommendation.request.duration` p95 5분 >= 600ms (목표 300ms 의 2배) | Discord webhook | P1 |
| audio backfill 연속 실패 | `mobruji.song.audio.backfill.failed` 1시간 sum >= 10 | Discord webhook | P2 |
| JVM heap 압박 | `jvm.memory.used / jvm.memory.max` > 0.85 5분 연속 | Discord webhook | P2 |

- webhook URL: 환경변수 `MOBRUJI_ALERT_WEBHOOK_URL`. dev/local 은 미설정 시 noop. 운영에서 미설정이면 부트 fail-fast.
- 알림 본문: rule name + 현재 값 + 직전 5분 추이 + Grafana 대시보드 링크 (자동 생성).
- 알림 자체에 PII 금지 — sessionId/userId 등 §5-7 화이트리스트 라벨만.

### 5-7) PII 마스킹 룰 (라벨 화이트리스트)

**허용 라벨** (이 외 키를 metric tag 로 쓰면 PR 차단):

- 도메인 식별/분류: `gender`, `mood`, `inputSource`, `cache`
- 결과 분류: `outcome` (`success`/`error`/`empty`/`ratelimited`/`unauthorized`)
- 외부 vendor: `vendor` (`musicbrainz`/`spotify`/`youtube`)
- 잡 이름: `jobName`
- 실패 사유: `reason` (사전 정의 enum — `python`/`io`/`parse`/`timeout`)

**금지 라벨**:

- `sessionId`, `userId`, `voiceRangeId`, `recommendationId`, `email`, `phone`, `token`
- 음역대 원문 값(`lowMidi`/`highMidi` 등 — 메타로는 OK 지만 metric **라벨**로 부적합. 카디널리티 폭발).
- 곡 `title`/`artist`/`id` — 카디널리티 사유 라벨 금지. counter 본문에 곡 단위 분기 필요하면 별도 도메인 엔티티 카운트로 구현.

> CLAUDE.md §4 보안 룰의 metric 변환. 위반 시 코드 리뷰에서 차단, v0.4 lint 자동화 후보.

### 5-8) `application.yml` 변경

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        mobruji.recommendation.request.duration: true
        mobruji.external.musicbrainz.request.duration: true
        mobruji.external.spotify.request.duration: true
        mobruji.song.audio.analysis.duration: true
      percentiles:
        http.server.requests: 0.5,0.95,0.99
        mobruji.recommendation.request.duration: 0.5,0.95,0.99
      slo:
        http.server.requests: 100ms,300ms,600ms

mobruji:
  alert:
    webhook-url: ${MOBRUJI_ALERT_WEBHOOK_URL:}   # 운영 fail-fast 는 별도 Validator 로
```

- `management.endpoint.health.show-details=when-authorized` + 인증 게이트로 운영 노출 한정.
- percentiles-histogram 활성화는 메모리 비용 있음 — 5개 timer 한정.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (현 PR, plan 28)**: spec(`docs/features/observability-baseline.md`) + ADR(`docs/decisions/0012-observability-stack.md`) + 룰 문서(`docs/ai-harness/10-observability.md`) Phase 1 갱신. **본 PR**.
- [ ] **PR 2 (be)**: `application.yml` percentiles 설정 + `MeterRegistry` 도메인 카운터 통일. 표 §5-3 의 기존 카운터들을 본 spec 네이밍으로 정리, 신설(`recommendation.request.duration`, `recommendation.result.size`, `voice.range.history.requested`) 추가. 보호 영역(`application.yml`) 변경이므로 `needs-human-review` 라벨.
- [ ] **PR 3 (infra)**: Grafana Cloud 계정 셋업 (수동) + Prometheus remote_write 설정 + 단순 대시보드 1개 (recommendation/voice/song + p95). ADR-0012 의 step-by-step 런북 참조.
- [ ] **PR 4 (infra)**: Discord webhook 알림 4개 규칙 등록 (§5-6). dev/local noop, 운영 fail-fast.
- [ ] **PR 5 (be, #62 동기화)**: p95 회귀 가드 테스트 — `recommendation.request.duration` 키 검증. PR 2 머지 후.

## 7) 테스트 전략

- **단위**: MeterRegistry 주입받는 서비스에 `SimpleMeterRegistry` 로 카운터 증가 검증.
- **통합**: `/actuator/prometheus` 응답 본문에 §5-3 표 모든 metric 이름이 노출되는지 smoke test.
- **E2E (RestAssured)**: 추천 endpoint 호출 후 `mobruji.recommendation.requested` 가 +1 되는지 검증 1건.
- **알림 규칙**: dev 환경에 mock webhook 으로 임계 초과 시 호출 검증 (PR 4 범위).
- **PII 마스킹**: 라벨 화이트리스트 위반을 검출하는 ArchUnit 테스트 1건 (v0.4 후보, 본 spec 범위 아님).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | Grafana Cloud Free 무료 한도(10k active series) 가 초기 트래픽에 충분한가? | (a) 충분 — §5-3 표 ~30개 metric × 라벨 카디널리티 ≤ 100 / (b) 부족 — self-host 필요 | @goohong / PR 3 셋업 시 측정 |
| Q2 | percentiles-histogram 5개 활성화의 메모리 비용은? | be 측정 — JVM heap +50MB 이내면 OK | @goohong (be) / PR 2 |
| Q3 | `mobruji.audit.*` prefix 의 lint 자동화는 v0.4 로 미루나? | (a) v0.3 후반에 ArchUnit 추가 / (b) v0.4 별도 spec | @goohong / v0.3 P3 |
| Q4 | 운영 인증 게이트는 admin token (#229 패턴) 재활용? | (a) `/actuator/health/details` 도 같은 토큰 / (b) 별도 토큰 | @goohong / PR 3 |

## 9) 결정 로그

- **2026-05-22 (plan 28)**: 초안 작성 (status=draft). v0.3 P2 베이스라인 범위 확정 — 메트릭 + p95 + Grafana Cloud Free + Discord webhook 알림. 분산 트레이싱/SaaS 유료/SLO/로그 집계/web RUM 모두 v0.4 이후로 분리. 수집 스택은 ADR-0012 분리.
