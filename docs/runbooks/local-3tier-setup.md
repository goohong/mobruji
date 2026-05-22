# 로컬 3-tier 가동 가이드

> mobruji 로컬 개발 환경(MySQL + Spring Boot + Next.js)을 한 번에 띄우는 절차.
> rev 세션 QA 실행 검증(`docs/features/rev-qa-protocol.md`)의 기본 환경이기도 하다.
>
> CLAUDE.md §6의 치트시트가 단축 버전이고, 본 문서는 **자주 발생하는 에러 + 회피 절차**를 포함한 풀 버전이다.

## 1) 사전 요구사항
- Docker (Docker Desktop 또는 colima)
- JDK 21 (LTS) — 백엔드용
- Node.js 20+ + npm — 프론트엔드용
- (선택) `jq`, `httpie` — smoke 시나리오 가독성

## 2) 가동 순서 (3단계)

### 2-1) Tier 1: MySQL (docker compose)
```bash
cd <repo-root>
docker compose up -d
docker compose ps   # mobruji-mysql-local Up 확인
```

- 포트: **호스트 13306 → 컨테이너 3306** (기본 3306 충돌 회피 목적)
- 볼륨: `mobruji-mysql-data` (재기동해도 데이터 유지)
- 기본 자격: `mobruji / mobruji / DB=mobruji` (`docker-compose.yml` 환경변수 기본값)

**healthcheck 대기**:
```bash
docker inspect --format='{{.State.Health.Status}}' mobruji-mysql-local
# "healthy" 나올 때까지 약 10~30초
```

### 2-2) Tier 2: Backend (Spring Boot)
```bash
cd backend
export MOBRUJI_ADMIN_TOKEN="local-dev-token-do-not-use-in-prod"
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 서비스 포트: **8080** (`SERVER_PORT` env로 override 가능)
- 관리 포트: **8081** (`MANAGEMENT_PORT` env로 override 가능, Actuator endpoint 분리)
- profile `local` = docker MySQL(13306) 사용. Flyway 자동 마이그레이션.
- **`MOBRUJI_ADMIN_TOKEN` 필수**: blank/null이면 부팅 실패 (admin endpoint 무인증 노출 방지). 평문 yml 금지.

기동 확인:
```bash
curl -s http://localhost:8080/actuator/health    # management 포트 분리 시 8081/actuator
curl -s http://localhost:8081/actuator/health    # 분리된 경우
```

### 2-3) Tier 3: Frontend (Next.js)
```bash
cd web
npm install        # 최초 1회 또는 package-lock.json 변경 시
npm run dev
```

- 포트: **3000** (Next.js 기본)
- BE 연동: `web/lib/api/`가 `http://localhost:8080`을 가리킴 (env로 override 가능)

기동 확인:
```bash
curl -s -i http://localhost:3000/ | head -20
```

## 3) 자주 발생하는 에러 / 회피

### 3-1) Flyway baseline + V1 'song already exists'
**증상**:
```
Found non-empty schema(s) ... without schema history table!
Use baseline() or set baselineOnMigrate=true
```
또는 V1 마이그레이션 중 `Table 'song' already exists`.

**원인**: 볼륨에 이전 데이터가 남아있는데 새 워크트리/세션이 fresh Flyway 가정으로 부팅.

**회피 (개발용, 데이터 폐기)**:
```bash
docker compose down -v        # 볼륨 함께 삭제
docker compose up -d
# healthcheck 대기 후 bootRun 재시도
```

**회피 (데이터 보존)**:
```bash
# application-local.yml에 flyway.baseline-on-migrate=true 한시적 설정 후 1회 부팅
# 부팅 성공 후 원복
```

### 3-2) 포트 충돌: 8080 / 8081 / 3000 / 13306
**증상**: `Address already in use` 또는 `bind: address already in use`.

**확인**:
```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:8081 -sTCP:LISTEN
lsof -nP -iTCP:3000 -sTCP:LISTEN
lsof -nP -iTCP:13306 -sTCP:LISTEN
```

**회피**:
- 점유 프로세스 종료: `kill <pid>`
- 또는 포트 override:
  ```bash
  SERVER_PORT=18080 MANAGEMENT_PORT=18081 ./gradlew bootRun --args='--spring.profiles.active=local'
  PORT=13000 npm run dev   # next.js
  ```
- docker MySQL 포트는 `docker-compose.yml`에서 `13306:3306` → 다른 호스트 포트로 변경 후 `docker compose up -d --force-recreate`.

### 3-3) `@tanstack/react-query` 등 web deps 미설치
**증상**: `Module not found: Can't resolve '@tanstack/react-query'`.

**원인**: develop에서 `package.json`/`package-lock.json`이 갱신됐는데 워크트리의 `node_modules`가 이전 버전.

**회피**:
```bash
cd web && npm install
```

**언제 발생하나**:
- fe 사이클이 처음 launch될 때 워크트리에 `node_modules`가 아예 없음.
- web deps를 추가한 PR이 머지된 직후 (예: PR #150 `pitchy`, PR #194 PWA service-worker).
- `post-merge-cleanup.sh`는 `node_modules`를 건드리지 않으므로 stale 상태가 누적될 수 있음.

본진이 fe sub-agent를 launch할 때 "직전 사이클에서 web deps 변경 PR이 머지됐다면 `npm install` 1회" 명시.

### 3-4) `MOBRUJI_ADMIN_TOKEN` blank → 부팅 실패
**증상**:
```
Failed to bind properties under 'app.admin.token' ... NullPointerException
```
또는 fail-fast 메시지.

**회피**:
```bash
export MOBRUJI_ADMIN_TOKEN="local-dev-token-do-not-use-in-prod"
# 또는 .envrc 등에 영구 등록
```

운영 정책상 평문 yml 금지 (시크릿). 로컬도 env로 주입.

### 3-5) docker mysql healthcheck 실패
**증상**: `docker compose ps`에 `(unhealthy)` 또는 `(starting)` 1분+.

**원인**: M1/M2 Mac에서 mysql:8.4 이미지 첫 부팅에 30~60초 걸림. 또는 호스트 포트 13306이 다른 mysql과 충돌.

**확인**:
```bash
docker compose logs mysql --tail 50
```

**회피**:
- 첫 부팅이면 30초 대기.
- 권한 에러면 볼륨 삭제: `docker compose down -v && docker compose up -d`.

### 3-6) Next.js dev 서버가 BE 응답을 못 받음 (CORS / 502)
**증상**: 브라우저 콘솔 `Failed to fetch` 또는 502.

**확인**:
- BE 기동 여부: `curl http://localhost:8080/actuator/health`
- CORS 설정: `backend/src/main/.../config/WebMvcConfig.java` 또는 동등 위치에서 `http://localhost:3000` allow 확인.
- BE 콘솔에서 요청 도착 여부 확인 (도착 안 함 → FE의 base URL env 확인).

### 3-7) flyway migration이 새 V_N에 막힘
**증상**: `Migration failed for change set V<N>__*.sql`.

**회피**:
1. 에러 메시지의 SQL 줄 확인.
2. 로컬은 `docker compose down -v` 후 재시도 (데이터 폐기).
3. 마이그레이션 자체 버그면 PR 코멘트 + 본진에 보고 (rev 사이클의 범주 E 위반).

## 4) 3-tier 일괄 셧다운
```bash
# FE
pkill -f "next dev" || true

# BE
pkill -f "GradleDaemon\|bootRun" || true
# 또는 bootRun 띄운 터미널에서 Ctrl+C

# DB (데이터 보존)
docker compose stop

# DB (데이터 폐기 + 컨테이너 정리)
docker compose down -v
```

## 5) rev QA에서의 사용

rev sub-agent가 QA 수행 시 이 가이드를 그대로 따른다 (`docs/features/rev-qa-protocol.md` §5-4 환경 선택 가이드). 다음 순서:

1. `docker compose up -d` (이미 떠있으면 skip)
2. BE bootRun (별 터미널 또는 `&`로 background)
3. FE `npm run dev` (별 터미널 또는 `&`로 background)
4. smoke 시나리오 실행 (`rev-qa-protocol.md` §5-3)
5. 결과 PR 코멘트 (`rev-qa-protocol.md` §5-6 형식)
6. background 프로세스 정리 (위 §4)

**rev 워크트리에서 파일 신규 생성·수정 금지**. 임시 스크립트가 필요하면 `/tmp/rev-qa-*.sh` 사용.

## 6) Troubleshooting 추가

| 현상 | 1순위 점검 | 2순위 점검 |
|---|---|---|
| 부팅 실패 (BE) | `MOBRUJI_ADMIN_TOKEN` env | Flyway 마이그레이션 로그 |
| 502 / Failed to fetch (FE) | BE 8080 reachable? | CORS / base URL env |
| `Module not found` (FE) | `npm install` | Node 버전 (20+ 필요) |
| `unhealthy` mysql | 30초 대기 | `down -v` 후 재시도 |
| 포트 충돌 | `lsof -nP -iTCP:<port>` | env로 포트 override |

## 7) 관련 문서
- `CLAUDE.md` §6 — 단축 치트시트
- `docs/features/rev-qa-protocol.md` — rev QA 실행 검증 spec
- `docs/ai-harness/11-multi-session-runbook.md` §2 — 세션별 역할 (rev QA 범위)
- `docs/ai-harness/03-quality-gates.md` §8 — rev QA gate (CI 게이트 보강)
