# 부하 테스트 (k6)

추천 API p99/TPS 자동 회귀 가드. spec `docs/features/recommendation-algorithm-v1.md` §3 비기능 "p95 200ms" 임계를 통계적으로 감시한다.

## 1) 왜 k6인가
- QA 단발 측정(n=30~50)은 분산이 커서 회귀 감지에 실패한 이력이 있다 (rev 사이클 6, p95 80.9ms 회귀 미감지).
- k6는 동시 VU + p95/p99 + 임계 fail-fast가 표준이라 CI에 그대로 꽂힌다.
- 외부 SaaS 의존 없음. 로컬·CI 동일 스크립트.

## 2) 측정 항목 / 임계

| 지표 | 임계 | 출처 |
|------|------|------|
| `http_req_duration{endpoint:recommendation}` p95 | < 200 ms | spec §3 비기능 |
| `http_req_duration{endpoint:recommendation}` p99 | < 400 ms | 본 PR 기본값 (p95 임계의 2배 휴리스틱) |
| `http_req_failed` rate | < 1 % | 일반 서비스 가용성 휴리스틱 |
| `checks` rate | > 99 % | 응답 구조 보존 |

워크로드:
- VU 10, ramp-up 10s + steady 60s + ramp-down 5s (총 ~75s)
- VU별 voice-range 사전 등록(setup) 후 추천 호출 loop
- voiceRangeLow/High, mood, excludeSongIds 변주 — Audio Features 미도입 상태이므로 음역대 폭/오프셋 다양화로 일반화

## 3) 로컬 실행

### 사전 조건
- k6 설치 (`brew install k6` 또는 https://k6.io/docs/get-started/installation/)
- 백엔드 8080 기동, MySQL 13306 기동
  ```bash
  docker compose up -d
  cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
  ```

### 실행
```bash
# 기본 (VU 10, duration 60s)
k6 run scripts/load/recommendation.k6.js

# VU/duration 튜닝
VUS=20 DURATION=120s k6 run scripts/load/recommendation.k6.js

# 다른 호스트
BASE_URL=http://192.168.0.10:8080 k6 run scripts/load/recommendation.k6.js
```

### 결과 해석
- 종료 코드 `0` → 모든 임계 통과
- 종료 코드 `99` → 임계 위반 (k6 표준)
- `summary.json` 에 raw metrics가 떨어진다 (워크플로우는 이걸 파싱해 PR 코멘트에 첨부)

## 4) CI 통합

`.github/workflows/load-test.yml`이 다음을 수행한다.

1. MySQL service container 기동
2. `./gradlew bootJar` 후 `java -jar` 백그라운드 실행
3. `http://localhost:8081/actuator/health` 폴링 (최대 120s)
4. k6 실행 → summary.json 생성
5. p50/p95/p99/TPS/에러율을 PR 코멘트로 자동 첨부 (기존 코멘트가 있으면 업데이트)
6. 임계 fail 시 workflow fail

트리거:
- `backend/**`, `scripts/load/**`, `.github/workflows/load-test.yml` 경로가 바뀐 PR
- `workflow_dispatch` (VU/duration 입력 가능)

## 5) 임계 변경 절차

임계는 spec과 1:1로 묶여 있으므로 코드만 바꾸지 않는다.

1. `docs/features/recommendation-algorithm-v1.md` §3 비기능 항목을 먼저 수정 (또는 ADR 추가).
2. `scripts/load/recommendation.k6.js` 의 `options.thresholds` 갱신.
3. 본 README §2 표 동기화.
4. PR 본문에 변경 사유 + 측정 데이터(이전 p95/p99 vs 신규 목표) 첨부.

## 6) 회귀 시 디버깅
- `Actions` 탭 → `k6-load-results` artifact 다운로드 → `summary.json` 비교
- `backend.log` 확인 — slow query, GC pause, 예외 로그
- 로컬에서 `VUS=10 DURATION=60s k6 run --out json=local.json scripts/load/recommendation.k6.js` 로 재현
- 회귀 의심 시 `git bisect` + 본 스크립트 조합

## 7) 알려진 한계
- Audio Features (BPM/key 매칭) 미도입 → 입력 다양성이 voiceRange/mood에 한정. v2에서 변주 확대.
- VU 10은 PoC 수준. 운영 트래픽 곡선 입수 후 stage profile 재조정 필요.
- 단일 ubuntu-22.04 runner — 절대 latency는 호스트 환경에 좌우. **회귀 감지가 목적이지 SLA 측정이 아니다.**
