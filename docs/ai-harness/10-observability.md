# 관측성 (Observability)

> Spring Boot Actuator + Micrometer 기반. **현재 Phase 0** — 백엔드 셋업 단계, 운영 환경 없음. 본 문서는 골격만 둠.

## 1) 관리 포트와 노출 endpoint (계획)

- **포트**: `MANAGEMENT_PORT` 환경변수(기본 `8081`). 메인 8080과 분리.
- **공개 endpoint**: `health`, `info`, `metrics`, `prometheus`
- **외부 노출 정책**: 운영 배포 시 management 포트는 외부 미노출(보안 경계). SSH 접속자 또는 같은 docker network 내에서만 호출 가능.
- Security 설정상 `/actuator/**`는 인증 우회(permitAll). 외부 미노출이 보안 경계.

## 2) 주요 메트릭 (예정)

도메인 추천 알고리즘이 붙기 시작하면 아래와 같이 도메인 메트릭을 추가한다.

| Counter (예시) | 태그 | 의미 |
|---|---|---|
| `mobruji.recommendation.requests` | `gender`, `mood` | 추천 요청 수 |
| `mobruji.recommendation.results.size` | — | 추천 결과 크기 분포 |
| `mobruji.cache.hits` | `cache=song_meta` | 곡 메타데이터 캐시 적중 |
| `mobruji.cache.misses` | `cache=song_meta` | 곡 메타데이터 캐시 미스 |

적중률 = `hits / (hits + misses)` per cache tag.

### 자동 노출 메트릭 (Micrometer)
- `http.server.requests` (히스토그램 활성)
- `jvm.memory.used`, `jvm.gc.*`
- `hikaricp.connections.*`
- `system.cpu.usage`

전체 목록은 `/actuator/metrics`.

## 3) 로컬 조회 방법

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/actuator/metrics
curl -s http://localhost:8081/actuator/prometheus | grep mobruji
```

## 4) Phase 1 — endpoint 노출 (도입 시점에 작성)
백엔드 운영 배포 시작 후 작성.

## 5) Phase 2 — Prometheus + Grafana (계획)
사용량이 의미 있어진 시점에 도입. 이때 본 문서를 갱신한다.

## 6) Phase 3 (예정/옵션)
- 알림 (Alertmanager + Discord/Slack webhook)
- 백업 (Prometheus snapshot)
- 대시보드 .json provisioning

## 7) 로그 정책 — PII 마스킹 (PR #129, closes #123 #128)

### 7-1) 배경
- rev 사이클 9 회고: fe에서 `console.error(error)` 패턴이 응답 객체를 그대로 dump해 `sessionId`/`voiceRangeId` 같은 식별자를 콘솔/외부 로거에 누출할 수 있다는 지적.
- CLAUDE.md §4 보안: 사용자 음역대·기호 데이터는 로그/코멘트/스크린샷에 원문 노출 금지.

### 7-2) 프론트엔드 (web/) 룰
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

### 7-3) 사용 예
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

### 7-4) 백엔드 (backend/, 추후 적용)
- 현 시점(Phase 0)에는 백엔드가 운영에 배포되지 않아 정책만 선언.
- 예정: SLF4J MDC + 로그 필터로 `sessionId`/`voiceRangeId` 등 식별자 자동 마스킹. 별도 PR에서 다룬다.
- 그때까지 `logger.error(..., dto)` 형태로 DTO/엔티티 전체를 dump하는 호출은 회피하고, 메시지 + 필요한 비식별 필드만 명시 로깅.
