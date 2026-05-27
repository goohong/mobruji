# 관측성 (Observability)

> Spring Boot Actuator + Micrometer 기반. **현재 Phase 1** — v0.3 P2 운영 관측성 베이스라인 합의 완료 (#242, plan 28).
>
> **단일 진실은 spec/ADR**:
> - 베이스라인 정의 (메트릭 표 / p95 매트릭스 / 라벨 화이트리스트 / 알림 규칙): `docs/features/observability-baseline.md`
> - 수집 스택 결정: `docs/decisions/0012-observability-stack.md`
> 본 문서는 두 곳의 룰만 요약한다. 신규 카운터/라벨/알림 추가 시 spec 표를 먼저 갱신한다.

## 1) 관리 포트와 노출 endpoint

- **포트**: `MANAGEMENT_PORT` 환경변수(기본 `8081`). 메인 8080과 분리.
- **공개 endpoint** (v0.3 베이스라인): `health`, `info`, `metrics`, `prometheus`
- **외부 노출 정책**:
  - management 포트는 운영에서 외부 미노출 (보안 경계 — SSH 또는 같은 docker network 내에서만 호출).
  - `/actuator/health/details` 는 `management.endpoint.health.show-details=when-authorized` 로 인증 게이트.
  - Grafana Cloud Agent 는 같은 호스트에서 push 하므로 외부 노출 없이 동작 (ADR-0012).
- Security 설정상 `/actuator/**`는 인증 우회(permitAll). 외부 미노출이 1차 보안 경계.

## 2) 메트릭 네이밍 컨벤션 (요약 — 상세는 spec §5-2)

```
mobruji.<domain>.<action>[.<state>]    # counter / gauge
mobruji.<domain>.<action>.duration     # timer
mobruji.external.<vendor>.<action>     # 외부 API 호출
mobruji.job.<jobName>.<state>          # 스케줄 잡
```

- `domain` = `06-domain-model.md` 컨텍스트 이름 (`recommendation`, `voice`, `song`, ...)
- 소문자 + dot 구분. snake_case/camelCase 금지.
- `mobruji.` prefix 필수 (Micrometer 시스템 메트릭과 분리).

**필수 카운터 목록 / 라벨 / 신설·기존 여부는 spec `§5-3` 표가 단일 진실**. 신설/변경 시 spec PR 을 먼저 머지하고 코드 PR 에서 참조한다.

### 자동 노출 메트릭 (Micrometer)
- `http.server.requests` (히스토그램 활성 — spec §5-8 의 `application.yml` 설정)
- `jvm.memory.used`, `jvm.gc.*`
- `hikaricp.connections.*`
- `system.cpu.usage`

전체 목록은 `/actuator/metrics`.

## 3) p95 측정 endpoint 매트릭스

spec `§5-4` 표 참조. 목표 p95 값은 spec 의 단일 진실, 변경은 spec PR 로. #62 회귀 가드가 같은 키로 검증한다.

## 4) 라벨 화이트리스트 (PII 마스킹 룰 — 메트릭 측면)

**허용 라벨**: `gender`, `mood`, `inputSource`, `cache`, `outcome`, `vendor`, `jobName`, `reason`

**금지 라벨**: `sessionId`, `userId`, `voiceRangeId`, `recommendationId`, `email`, `phone`, `token`, 음역대 원문 값, 곡 `title`/`artist`/`id` (카디널리티 폭발 + PII)

위반 시 코드 리뷰에서 차단. 상세 + enum 값 정의는 spec `§5-7`. 로그 측면 마스킹 룰은 본 문서 §8 참조.

## 5) 로컬 조회 방법

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/actuator/metrics
curl -s http://localhost:8081/actuator/prometheus | grep mobruji
```

## 6) 수집 스택 (Phase 1)

**Grafana Cloud Free + Prometheus remote_write (ADR-0012)**

- Grafana Agent 가 같은 호스트에서 `/actuator/prometheus` 를 scrape → Grafana Cloud Prometheus 로 remote_write push.
- 대시보드: 단일 대시보드 1개 (recommendation/voice/song + p95 + 외부 API 에러율 + JVM heap). JSON provisioning.
- 무료 한도 (active series 10k, log 50GB, 14-day retention) 안에서 운영. §4 라벨 화이트리스트가 카디널리티 폭발 1차 방어선.

## 7) 알림 (Phase 1)

v0.3 베이스라인 알림 4 규칙 (spec `§5-6`):

| 규칙 | 트리거 | 우선순위 |
|---|---|---|
| 외부 API 에러율 | `mobruji.external.*{outcome="error"}` 1분 sum ≥ 5 | P1 |
| 추천 p95 임계 초과 | `mobruji.recommendation.request.duration` p95 5분 ≥ 600ms | P1 |
| audio backfill 연속 실패 | `mobruji.song.audio.backfill.failed` 1시간 sum ≥ 10 | P2 |
| JVM heap 압박 | `jvm.memory.used / jvm.memory.max` > 0.85 5분 연속 | P2 |

- 송신 채널: Discord webhook (`MOBRUJI_ALERT_WEBHOOK_URL` — 환경변수, 운영 fail-fast / dev/local noop).
- 알림 본문에 PII 금지 (§4 화이트리스트만).
- 1차는 backend 자체 임계 평가 + webhook. Grafana Cloud Alert 이관은 v0.3 후반/v0.4 별도 PR.

## 7-A) Phase 2 — self-host 이관 (조건부)

Grafana Cloud Free 무료 한도 초과 또는 latency 민감해진 시점에 self-host (Prometheus + Grafana docker-compose) 로 이관. 새 ADR 신설 + ADR-0012 superseded 처리.

## 7-B) Phase 3 (예정/옵션 — v0.4 이후)

- 분산 트레이싱 (OpenTelemetry — web ↔ be ↔ Python audio runner)
- 로그 집계 (Loki) — 다중 인스턴스 운영 결정 후
- SLO/SLA 정의
- 프론트엔드 RUM

## 8) 로그 정책 — PII 마스킹 (PR #129, closes #123 #128)

> 메트릭 라벨 마스킹은 §4. 본 절은 stdout/console/logger 측면.

### 8-1) 배경
- rev 사이클 9 회고: fe에서 `console.error(error)` 패턴이 응답 객체를 그대로 dump해 `sessionId`/`voiceRangeId` 같은 식별자를 콘솔/외부 로거에 누출할 수 있다는 지적.
- CLAUDE.md §4 보안: 사용자 음역대·기호 데이터는 로그/코멘트/스크린샷에 원문 노출 금지.

### 8-2) 프론트엔드 (web/) 룰
- **`console.*` 직접 사용 금지.** 모든 로깅은 `@/lib/logging` 의 `safeLog.*` 래퍼를 경유한다.
- ESLint(`no-console: error`)로 빌드 시 강제. 예외는 `web/lib/logging.ts` 자체뿐(라인 단위 `eslint-disable-next-line no-console`).
- 래퍼 동작:
  - `safeLog.error(message, ...args)` / `.warn` / `.info` / `.debug` 시그니처.
  - `message`는 운영자가 의도해 작성한 문자열이라 마스킹하지 않음 — 자유 텍스트에 PII를 직접 끼우지 말 것.
  - 추가 인자는 `maskPII(value)` 로 깊은 복제 후 마스킹.
- 마스킹 대상 키(`SENSITIVE_KEYS` 화이트리스트):
  - `sessionId`, `voiceRangeId`, `userId`, `email`, `phone`,
    `token`, `accessToken`, `refreshToken`, `requestId`
  - 문자열은 마지막 4자리만 노출(`****abcd`), 4자 이하/숫자는 `****`.
- **마스킹하지 않는 메타데이터**(민감 X로 합의):
  - `lowMidi`, `highMidi`, `voiceRangeLow`, `voiceRangeHigh` (반음 단위 MIDI 값)
  - `bpm`, `score`, 곡 `title`/`artist`/`id` 등
- Error 객체는 `{ name, message, stack, ...extra }` 로 평탄화. 운영 환경(`NODE_ENV === "production"`)에서 stack은 상위 3줄만 남기고 truncate.
- 순환 참조는 `[Circular]` 로 끊는다.

### 8-3) 사용 예
```ts
import { safeLog } from "@/lib/logging";

try {
  await createRecommendation(request);
} catch (error) {
  safeLog.error("[recommend] mutation failed", error);
  // console 출력 예:
  //   [recommend] mutation failed { name: 'ApiError', message: '...', body: { sessionId: '****beef', ... } }
}
```

### 8-4) 백엔드 (backend/, 추후 적용)
- 현 시점에는 백엔드가 운영에 배포되지 않아 정책만 선언.
- 예정: SLF4J MDC + 로그 필터로 `sessionId`/`voiceRangeId` 등 식별자 자동 마스킹. 별도 PR에서 다룬다.
- 그때까지 `logger.error(..., dto)` 형태로 DTO/엔티티 전체를 dump하는 호출은 회피하고, 메시지 + 필요한 비식별 필드만 명시 로깅.
