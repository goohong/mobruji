---
feature: 추천 API p95 응답시간 회귀 가드 (k6 + Micrometer 이원화)
slug: recommendation-p95-regression-guard
status: draft
owner: "@goohong"
scope: recommendation
related_issues: [62, 253, 242, 273, 274]
related_prs: [451]
last_reviewed: 2026-05-23
---

# 추천 API p95 응답시간 회귀 가드 (k6 + Micrometer 이원화)

## 1) 개요 (What / Why)

- `recommendation-algorithm-v1.md` §3 비기능 "p95 200ms" 가 코드에 자동 검증된 적이 한 차례 회귀를 놓친 이력이 있다 (rev 사이클 6 — p95 80.9ms 증가 미감지). 이를 계기로 `scripts/load/recommendation.k6.js` + `.github/workflows/load-test.yml` (k6 + PR 코멘트 + 임계 fail) 가 도입되어 **이미 운영 중**이다.
- 그러나 임계값 **단일 진실 정합**이 깨져 있다:
  - `recommendation-algorithm-v1.md` §3 비기능: **p95 200ms** (k6 thresholds 와 일치).
  - `recommendation-algorithm-v2.md` §3 비기능: **p95 200ms 유지**.
  - `observability-baseline.md` §5-4 p95 매트릭스: 추천 POST **목표 300ms**, 알림 임계 600ms.
  - 200ms/300ms 의 어느 쪽이 단일 진실인지 spec 간에 명시되지 않아, v3+ PR 이 어느 기준으로 회귀 판정해야 할지 모호.
- 또한 v0.3 P2 진행 동안 (a) Audio Features (tempoMatch) 가산으로 입력 다양성 의미가 바뀌었고, (b) `observability-baseline.md` 가 Micrometer `mobruji.recommendation.request.duration` p95 를 운영 측 1차 신호로 정의했으며, (c) 다른 5개 endpoint (like/bookmark/voice-range-history 등) p95 매트릭스가 신설됐다. **k6 회귀 가드와 Micrometer 운영 모니터링의 역할 구분 + 시드/임계/베이스라인 갱신 절차** 를 본 spec 에서 단일 진실로 확정한다.
- v0.3 P2 안에 본 spec + 후속 PR (시나리오 진화 + 임계 정합 + baseline 갱신 룰) 을 끝내, v0.4 v3 알고리즘 PR 이 본 spec 을 단일 기준으로 회귀 판정한다.

## 2) 사용자 시나리오 (개발자/운영자 액터)

- (S1) **PR 회귀 차단**: be 가 추천 v3 임베딩을 머지 시도. PR CI 의 `k6-load` job 이 `http_req_duration{endpoint:recommendation}` p95 = 320ms 측정 → 임계(250ms) 초과 → workflow fail → PR 머지 차단. 본 spec 의 `§5-3` 임계가 단일 진실.
- (S2) **의도된 성능 변화**: be 가 다양성 후처리 알고리즘 개선으로 p95 가 180→220ms 로 상승 (의도). PR 본문에 §6 baseline 갱신 절차에 따라 (1) 본 spec 의 임계를 250→280ms 로 수정, (2) v1/v2 spec §3 비기능 갱신, (3) `observability-baseline.md` §5-4 동기화, (4) k6 thresholds + README 갱신을 한 PR 에 묶는다.
- (S3) **운영 회귀 감지**: 머지 직후 Grafana 대시보드에서 `mobruji.recommendation.request.duration` p95 가 280ms → 480ms 로 튀는 것을 운영자가 본다 → CI k6 는 통과했지만 실제 트래픽 패턴이 다른 케이스 → 본 spec §5-5 "k6 ↔ Micrometer 차이 디버깅 절차" 가이드를 따라 시드/입력 분포/캐시 적중률을 비교.
- (S4) **k6 admin 토큰 미주입 (#253)**: k6 가 admin endpoint(`/songs/{id}/stats`) 를 호출하지 않으므로 본 spec 의 측정 대상이 아님 — 회귀 가드는 `/api/v1/recommendations` POST 에 한정. §5-7 의존성에서 명시.

## 3) 요구사항

### 기능 요구사항

- [ ] **단일 진실 임계값 표**: 본 spec §5-3 표가 추천 endpoint p95/p99 임계의 단일 진실. 다른 spec/코드/README 는 본 표를 참조 (역참조 금지).
- [ ] **k6 시나리오 동기화**: `scripts/load/recommendation.k6.js` 의 `options.thresholds` 가 §5-3 표와 일치. PR diff 가 §5-3 표를 건드리면 같은 PR 에서 k6 스크립트도 갱신.
- [ ] **CI 회귀 게이트**: `.github/workflows/load-test.yml` 가 `backend/**` 변경 PR 에 트리거되어 k6 임계 fail 시 workflow fail (현행 유지). 본 spec 은 이 동작을 단일 진실로 고정.
- [ ] **결정성 시드**: k6 워크로드 (`VOICE_RANGE_VARIANTS`, `randomExcludeSongIds`, mood 추첨) 는 `Math.random()` 기반이라 호출별 분산이 있다. 본 spec 은 "회귀 감지 목적상 분산 허용, 단 VU 수/duration/입력 분포는 §5-4 표를 통해 고정" 으로 단일 진실 정의 — Math.random 시드 고정까지는 v0.4 후보 (오픈 질문 Q3).
- [ ] **베이스라인 갱신 절차**: §6 의 절차(spec → k6 → README → PR 본문 측정 데이터) 가 의도된 성능 변화 시 단일 경로. PR description 템플릿에 체크리스트 1줄 추가 (후속 PR).
- [ ] **k6 ↔ Micrometer 역할 분리**: k6 = PR 회귀 가드 (사전 차단), Micrometer p95 = 운영 회귀 감지 (사후 감지 + Discord 알림). 본 spec §5-5 에서 두 신호의 차이 디버깅 절차 명시.
- [ ] **알림 임계와의 정합**: `observability-baseline.md` §5-6 의 추천 p95 알림 임계 = "본 spec §5-3 임계 × 2" 휴리스틱. spec 갱신 시 알림 임계도 자동 비례 (운영 환경 알림 정의는 PR 4 범위, 본 spec 은 비례 룰만 선언).

### 비기능 요구사항

- **결정성**: 본 spec §5-3 표가 단일 숫자 소스. 코드 grep 으로 `p(95)<200` 같은 magic number 검출 시 본 spec 참조 주석 강제.
- **응답시간 영향**: 본 spec 은 측정 인프라 spec. 추가 측정 비용은 측정 환경(GH Actions runner) 에 한정, 운영 응답시간 영향 0.
- **설정 외부화**: k6 VU/duration 은 `workflow_dispatch` input + env 로 외부화. 임계값은 코드 상수 (사양값이라 외부화 의미 없음).
- **관측성**: 본 spec 자체가 관측성 보조. 추가 메타 카운터는 도입하지 않음 (`observability-baseline.md` §5-3 의 `mobruji.recommendation.request.duration` 재활용).
- **보안**: k6 워크로드는 sessionId 를 `k6-load-<ts>-<i>` prefix 로 생성 (운영 사용자 데이터와 격리). 음역대 값은 spec 상 합리적 범위 내 합성치 → PII 비해당. admin endpoint 호출 없음 → 토큰 미요구 (#253 영향 없음).

## 4) 범위 / 비범위

### 포함

- 추천 POST `/api/v1/recommendations` endpoint 한정 회귀 가드 (단일 진실 임계 표).
- k6 시나리오 + GH Actions workflow 운영 룰 (현행 자산 정합화).
- baseline 갱신 절차 (의도된 성능 변화 처리 방법).
- k6 (사전 차단) ↔ Micrometer (사후 감지) 역할 분리 + 차이 디버깅 가이드.
- v1/v2 spec §3 비기능 + observability-baseline §5-4 와의 단일 진실 참조 정합.

### 제외 (Out of Scope)

- **like/bookmark/voice-range-history 등 다른 endpoint 의 회귀 가드** — `observability-baseline.md` §5-4 매트릭스에 있으나 본 spec 은 추천 POST 1개 한정. 추가는 별 spec (`other-endpoints-p95-regression-guard.md` 후보, v0.4).
- **admin endpoint k6 측정** — #253 의 `MOBRUJI_ADMIN_TOKEN` 주입 이슈 미해결 + admin 은 트래픽 미미 → 본 spec 측정 범위에서 명시적 제외.
- **JMH/마이크로벤치** — score() 산식 단위 성능은 의미 있을 수 있으나 본 spec 은 endpoint 응답시간 한정. 별 spec 후보.
- **Micrometer p95 운영 알림 규칙 자체** — `observability-baseline.md` §5-6 PR 4 범위. 본 spec 은 임계 비례 룰만 선언.
- **k6 시나리오 시드 고정 (결정적 재현)** — 오픈 질문 Q3. v0.4 후보.
- **다중 시나리오 (smoke/baseline/stress/soak)** — k6 표준 패턴. 현재 단일 시나리오(60s steady) 충분, 다중 분리는 트래픽 패턴 입수 후.
- **PR description 템플릿 자동 체크리스트 강제** — v0.4 ArchUnit/PR lint 후보. 본 spec 은 절차 선언만.

## 5) 설계

### 5-1) 도메인 모델

- 본 spec 은 도메인 엔티티 추가 없음. 기존 자산만 사용:
  - 측정 신호: Micrometer `mobruji.recommendation.request.duration` (observability-baseline §5-3 표 정의).
  - 측정 도구: k6 (`scripts/load/recommendation.k6.js`).
  - CI 게이트: GH Actions `.github/workflows/load-test.yml`.

### 5-2) 아키텍처 — k6 (CI 사전 차단) ↔ Micrometer (운영 사후 감지) 이원화

```
[ 개발자 PR ] ──> GH Actions ──> k6 (10 VU, 60s) ──> http_req_duration p95
                                                            │
                                                            ├─ 임계 통과 → CI green → 머지
                                                            └─ 임계 fail → CI red → 머지 차단

[ 운영 트래픽 ] ──> Spring Boot ──> Micrometer ──> /actuator/prometheus
                                                            │
                                                            ├─ Grafana Cloud scrape (1min)
                                                            ├─ 대시보드 시각화 (mobruji.recommendation.request.duration p95)
                                                            └─ 알림 임계 초과 → Discord webhook (#모부르지)
```

- **k6 = 사전 차단**. 합성 워크로드 (n=600~1200 req/run) 통계. 의도된 변화는 baseline 갱신 절차로 흡수.
- **Micrometer = 사후 감지**. 실제 트래픽 분포 위에서 운영 회귀 감지. 입력 분포가 k6 와 다른 경우 (예: 특정 mood 비율 급증) 운영만 회귀가 잡힐 수 있다 — §5-5 디버깅 절차.
- 둘은 **같은 endpoint, 다른 데이터 분포** 위에서 같은 신호(p95)를 본다. 임계는 본 spec §5-3 단일 진실. 알림은 §5-3 × 2 (휴리스틱).

### 5-3) 단일 진실 임계값 표

| 지표 | 임계 (CI fail) | 알림 임계 (운영) | 비고 |
|---|---|---|---|
| `http_req_duration{endpoint:recommendation}` p95 | **< 200 ms** | (k6 한정) | k6 측정. v1/v2 spec 합치값 유지. |
| `http_req_duration{endpoint:recommendation}` p99 | **< 400 ms** | (k6 한정) | p95 의 2배 휴리스틱. |
| `http_req_failed` rate | **< 1 %** | (k6 한정) | k6 워크로드 가용성. |
| `checks` rate | **> 99 %** | (k6 한정) | k6 응답 구조 검증 (status 201, requestId 존재, recommendations array). |
| `mobruji.recommendation.request.duration` p95 (운영) | (운영 모니터링 한정) | **>= 400 ms 5분 연속** | k6 임계 200ms × 2 = 400ms. observability-baseline §5-6 동기화 완료 (2026-05-23, §9 결정 로그 참조). |

> **임계값 결정 근거**:
> - **200ms**: rev 사이클 6 회귀 미감지 시점의 baseline 측정값 ~80ms + 안전 마진 2.5배. v1/v2 spec 합치.
> - **observability-baseline §5-4 단일 진실 박제 (2026-05-23, closes #273)**: observability-baseline §5-4 가 본 spec §5-3 (200ms) 을 단일 진실로 cross-ref. 역참조 금지 — 추천 POST p95 변경은 본 spec 갱신 1곳에서만. v3 알고리즘 + DB 카탈로그 1000곡 확장 시 베이스라인 갱신 절차(§6)로 200→상향 가능 — 의도된 변화 명시 필수.
> - **운영 알림 임계 = CI 임계 × 2**: CI 임계는 합성 분포(분산 좁음), 운영은 실제 분포(분산 넓음). 알림이 너무 자주 울리면 무시되므로 ×2 마진. observability-baseline §5-6 의 알림 임계 = 200ms × 2 = **400ms** 박제 완료 (2026-05-23).

### 5-4) k6 워크로드 표

| 항목 | 값 | 출처/근거 |
|---|---|---|
| 동시 VU | 10 | rev 사이클 6 회귀 재현 가능 + GH runner 단일 코어 부담 적정 |
| 시나리오 | ramping-vus (warmup 10s + steady 60s + ramp-down 5s) | 총 ~75s, GH runner 15분 timeout 안 |
| 입력 분포 - voiceRange | low 48~64 (2 step), width 12~24 (4 step) = 27 variants | 가창 음역 일반화 (C3~C5 부근) |
| 입력 분포 - mood | 7 종 (UPBEAT/CALM/EMOTIONAL/POWERFUL/GROOVY/NOSTALGIC/null) 균등 추첨 | observability-baseline §5-3 mood 라벨 매트릭스 |
| 입력 분포 - excludeSongIds | 0~3개, 1..30 범위 임의 | 시드 100곡 가정, 정상 다양성 후처리 트리거 |
| 입력 분포 - sessionId | VU별 1개 사전 등록 (`k6-load-<ts>-<i>`) | 같은 VU 가 같은 session 재사용 → 운영 패턴 유사화 |
| pacing | VU 당 sleep 0.5~1.0s | 무한 burst 방지, 실제 사용자 페이스 근사 (1~2 req/s/user) |
| BPM 입력 (`preferredBpm`) | **50% 확률로 [60, 200] 임의 정수 주입, 나머지 50% 미주입** | v2 tempoMatch 두 분기 모두 회귀 가드 — 직접 주입 (사용자 BPM) + mood default BPM 표. `BPM_INJECTION_RATE` / `BPM_MIN` / `BPM_MAX` env 로 비율·범위 튜닝 (PR #451, 2026-05-23). |

- 본 표는 §5-3 임계 검증 시 **함께** 갱신해야 한다. VU/duration 변경은 임계의 의미를 바꾼다 (예: VU 100 으로 늘리면 p95 가 다른 분포).
- ~~v0.4 후보: `preferredBpm` 입력 변주 추가~~ → **resolved (2026-05-23, PR #451)**: 50% 변주 박제. v2 tempoMatch 직접 주입 경로 회귀 가드 활성화. 임계(§5-3) 무변경 — 워크로드만 다양화. (Q4 (a) 채택)

### 5-5) k6 ↔ Micrometer 차이 디버깅 절차

- CI 통과 (k6 p95 < 200ms) 인데 운영 알림 (Micrometer p95 >= 400ms) 발생 시 다음 순서로 비교:
  1. **입력 분포 차이**: Grafana `mobruji.recommendation.requested{mood=*}` counter 분포 vs §5-4 표 추첨 분포. 특정 mood 가 운영에서 비대 → k6 워크로드를 갱신.
  2. **카탈로그 크기 차이**: 운영 카탈로그 곡 수 vs k6 시드 (현재 ~30). 카탈로그 확장이 in-memory 정렬 비용을 키우면 §6 baseline 갱신 절차 필요.
  3. **세션 패턴 차이**: 운영 사용자가 같은 sessionId 로 N회 호출 → SeedDeriver 분포 변화 → 다양성 후처리 비용. `mobruji.recommendationhistory.*` counter 와 비교.
  4. **외부 의존 추가**: v3 에서 MusicBrainz/Spotify 호출 도입 시 k6 가 mock 경로를 타고 운영이 real path 를 타면 측정 의미가 어긋남. 본 spec 갱신 + k6 시나리오 분기 추가.
  5. **인프라 차이**: GH runner ubuntu-22.04 vs 운영 인스턴스 사양 (ADR 인프라 spec). 절대 latency 차이는 본 spec 측정 의도 (회귀 감지) 와 별개.

### 5-6) baseline 갱신 절차 (의도된 성능 변화 처리)

의도된 알고리즘 변경으로 p95 가 임계를 초과할 것이 예상되면 **한 PR 에 다음을 모두 묶는다**:

1. **본 spec §5-3 표 갱신**: 새 임계 + 근거 (이전 baseline 측정값 / 이론적 추정 / hand-eval 결과).
2. **v1/v2 spec §3 비기능 갱신**: 본 spec 으로 참조 일원화 (이전엔 직접 숫자 기재).
3. **observability-baseline §5-4 매트릭스 갱신**: 본 spec 으로 참조 일원화.
4. **observability-baseline §5-6 알림 임계 갱신**: 새 임계 × 2.
5. **`scripts/load/recommendation.k6.js` `options.thresholds` 갱신**: §5-3 표 그대로.
6. **`scripts/load/README.md` §2 표 갱신**: §5-3 표 그대로.
7. **PR 본문**: AS-IS p95 측정값 (직전 main 의 k6 결과) vs TO-BE 새 임계 + 근거. measurement artifact 링크 첨부 (Actions 탭).

> 이를 어기면 후속 PR 이 어느 spec 을 따라야 할지 모호해진다. v0.4 ArchUnit/lint 로 자동화 후보 (오픈 질문 Q2).

### 5-7) 의존성 / 보호 영역

- **#253 (k6-load admin token)**: 본 spec 의 측정 endpoint 는 `/api/v1/recommendations` POST 1개 한정 (admin endpoint 비사용). 따라서 #253 미해결이 본 spec 회귀 가드에 영향 없음. 단 #253 해결 후 admin endpoint 회귀 가드를 추가하려면 별 spec 필요.
- **보호 영역 (`needs-human-review` 라벨 강제)**:
  - `.github/workflows/load-test.yml` 갱신 PR
  - `application.yml` percentiles 설정 갱신 PR (observability-baseline §5-8 범위)
- **외부 의존**: GH Actions `ubuntu-22.04` runner, MySQL service container `mysql:8.4.6`. 본 spec 측정 환경 의존성 변경 시 baseline 갱신 절차(§6) 트리거.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (docs, 본 PR)**: 본 spec 신설 + v1/v2/observability-baseline 단일 진실 참조 정합 (각 spec §3 비기능 / §5-4 매트릭스에 "임계는 `recommendation-p95-regression-guard.md` §5-3 단일 진실 참조" 한 줄 추가).
- [ ] **PR 2 (chore:infra)**: `observability-baseline.md` §5-6 알림 임계 600ms → 400ms 동기화 + `scripts/load/README.md` §2 표 본 spec 참조 갱신. 보호 영역 미해당 (docs only).
- [x] **PR 3 (chore:recommendation, 선택)**: k6 시나리오에 `preferredBpm` 입력 변주 추가 — v2 tempoMatch 직접 주입 경로 회귀 가드. `scripts/load/recommendation.k6.js` + README 갱신. 임계는 §5-3 표 그대로 (워크로드 변경이 임계 의미를 바꾸지 않는지 baseline 측정 PR 본문에 첨부). **완료: PR #451 (2026-05-23, closes #274)** — 50% 변주 + env 튜닝 (`BPM_INJECTION_RATE` / `BPM_MIN` / `BPM_MAX`). Q4 (a) 채택.
- [ ] **PR 4 (chore:infra, 선택, 오픈 질문 Q3)**: k6 시드 고정 (`Math.random` seed) — 결정적 재현. 부수효과 분석 필요 (분산이 0 이 되면 통계적 임계 의미 변화).
- [ ] **PR 5 (chore:ci, 오픈 질문 Q2)**: ArchUnit 또는 PR lint 로 magic number `p(95)<` 검출 시 본 spec 참조 주석 강제. v0.4 후보.

## 7) 테스트 전략

- **단위/통합**: 본 spec 자체 테스트 없음 (측정 인프라 spec).
- **E2E (k6 회귀 가드)**: 현행 `scripts/load/recommendation.k6.js` 가 그대로 E2E 역할. CI 트리거 `backend/**` 변경 PR.
- **검증 시나리오**:
  1. **임계 초과 재현**: k6 스크립트에 의도적 `sleep(1.0)` 주입 → CI fail 확인 (수동 dispatch).
  2. **임계 통과 정상 케이스**: 본 PR 머지 시 CI green 확인.
  3. **baseline 갱신 절차 실제 사용**: v3 알고리즘 PR (별 이슈) 에서 §6 절차 적용 실측.
- **회귀 가드의 회귀**: 본 spec 표(§5-3) 와 k6 스크립트(`options.thresholds`) 가 어긋나는 경우 — PR review 책임. ArchUnit 자동화는 PR 5 후보.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| ~~Q1~~ | ~~observability-baseline §5-4 의 추천 endpoint 목표 p95 = 300ms 와 본 spec §5-3 의 200ms 중 어느 쪽이 단일 진실인가?~~ | **resolved (2026-05-23, closes #273)**: (a) 200ms 확정. observability-baseline §5-4 가 본 spec §5-3 을 단일 진실로 cross-ref. §5-6 알림 임계도 400ms 동기화. | — |
| Q2 | 본 spec §5-3 표와 k6 스크립트 임계 동기화를 ArchUnit/PR lint 로 자동 강제할 것인가? | (a) v0.4 후보 (현재는 PR review) / (b) v0.3 P3 안에 도입 | @goohong / v0.3 P3 회고 |
| Q3 | k6 워크로드 `Math.random` 시드 고정으로 결정적 재현 가능하게 할 것인가? | (a) 부수효과 분석 후 v0.4 / (b) 분산 허용 유지 (현행) / (c) 옵션 env 로 둘 다 지원 | @goohong / v0.4 P1 |
| ~~Q4~~ | ~~k6 워크로드에 `preferredBpm` 직접 주입 변주 추가 (v2 tempoMatch 회귀 가드 강화) 우선순위?~~ | **resolved (2026-05-23, closes #274)**: (a) PR 3 채택. PR #451 로 50% 변주 박제 + env 튜닝. §5-4 표 갱신, 임계(§5-3) 무변경. | — |
| Q5 | 운영 알림 임계 비례 룰 (k6 임계 × 2) 의 ×2 휴리스틱이 적정한가? | 운영 데이터 누적 후 측정 → ADR 추가 | @goohong / v0.4 운영 데이터 입수 후 |

## 9) 결정 로그

- **2026-05-22 (plan 31)**: 초안 작성 (status=draft). 이미 운영 중인 k6 + load-test workflow 자산 정합화. 핵심 결정:
  - **본 spec 신설 사유**: #62 는 "회귀 가드 도입" 명목이지만 인프라는 이미 완비 (rev 사이클 6 → k6 + workflow). 이제 필요한 것은 단일 진실 임계표 + baseline 갱신 절차 + 다른 spec 과의 참조 정합화.
  - **k6 ↔ Micrometer 이원화 명시**: k6 = CI 사전 차단, Micrometer = 운영 사후 감지. 같은 신호(p95), 다른 데이터 분포.
  - **임계 단일 진실 위치**: 본 spec §5-3 표. v1/v2/observability-baseline 은 참조만 (역참조 금지).
  - **임계 보수 우선 (Q1)**: 200ms 유지 잠정 결정 — v1/v2 spec 합치 + k6 운영 자산이 200ms 로 동작 중. observability-baseline §5-4 의 300ms 목표값은 본 spec 머지 후 200ms 로 보수적 동기화.
  - **알림 임계 = CI 임계 × 2 비례 룰**: observability-baseline §5-6 의 600ms 는 300ms × 2 휴리스틱. 본 spec 200ms 기준으로는 400ms 로 조정 필요 (PR 2 범위).
  - **#253 의존성 없음**: 본 spec 측정 endpoint 는 추천 POST 1개. admin endpoint 비사용.
  - **baseline 갱신 절차 6단계 (§6)** 를 단일 진실 경로로 확정. 어기면 spec 간 불일치 재발.
- **2026-05-23 (plan)**: 단일 진실 박제 (closes #273). 잠정 결정이었던 200ms p95 / 400ms p99 를 단일 진실로 확정하고 observability-baseline §5-4 (목표 p95) / §5-6 (알림 임계 = 400ms) 를 한 PR 에 동기화. §5-3 노트의 "오픈 질문 Q1" / "동기화 필요" 잔재 문구 정리, §8 Q1 resolved 표시. rev audit 누차 발견 (200ms ↔ 300ms / 400ms ↔ 600ms drift) 종결. 후속: 의도된 변화 시 §6 baseline 갱신 절차로만 변경 가능 — 다른 spec 의 직접 갱신 금지.
- **2026-05-23 (plan, PR #451 후속)**: Q4 resolved (closes #274). k6 워크로드에 `preferredBpm` 50% 변주 박제 — v2 tempoMatch 직접 주입 경로 회귀 가드 활성화. `BPM_INJECTION_RATE` / `BPM_MIN` / `BPM_MAX` env 로 비율·범위 튜닝. §5-4 표 BPM 행 갱신, §6 PR 3 체크박스 완료 표시. 임계(§5-3 200ms p95 / 400ms p99) 무변경 — 워크로드 다양화는 임계 의미 무관 (baseline 갱신 절차 §6 미트리거). 후속: Q2 (ArchUnit 자동화) / Q3 (시드 고정) / Q5 (×2 휴리스틱) 는 v0.4 운영 데이터 누적 후.
