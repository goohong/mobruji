---
feature: dev 서버 PR 브랜치별 격리 deploy 인프라 — 단계 1 rev direct QA 동시 race 해소
slug: dev-pr-branch-deploy-isolation
status: draft
owner: @goohong
scope: infra
related_issues: [1283]
related_prs: [1305]
last_reviewed: 2026-05-29
---

# dev 서버 PR 브랜치별 격리 deploy 인프라 — 단계 1 rev direct QA 동시 race 해소

> **status: draft.** `docs/features/rev-direct-qa-extension.md` §8 Q2 후보 (a) "별 spec 분리" 트리거. ncp-dev-deployment.md (Phase 4 dev 환경, shipped) 의 dev URL (`http://101.79.20.94/`) 은 develop tip 단일 배포 — PR 브랜치별 격리 deploy 인프라 부재. rev sub-agent 가 단계 1 (PR 머지 전) direct curl QA 를 PR 단위로 수행하려면 격리된 dev URL 필요 (rev-direct-qa-extension.md §3 후보 A 한계). 본 spec 은 인프라 후보 / 비용 / TTL / secret 주입 결정만 박는다 — 실제 workflow / Dockerfile / nginx config 는 후속 PR (§6) 분할.

## 1) 개요 (What / Why)

- **What**: PR 브랜치마다 격리된 dev URL (예: `pr-1234.dev.mobruji.app` 또는 `https://mobruji-pr-1234.fly.dev` 또는 ngrok tunnel) 을 자동 prov / teardown 하는 인프라. PR push / synchronize 시 deploy → PR close 시 자동 cleanup.
- **Why**: rev-direct-qa-extension.md §3 후보 A (dev 서버 direct curl QA) 가 단일 dev URL 공유로 동시 PR 다수 deploy race 발생 → 단계 1 (PR 머지 전) 적용 불가 (현재 fallback = 단계 2 develop 머지 후만 적용). 격리 deploy = rev 가 단계 1 부터 PR 단위 결정성 / DTO 정합 / p95 검증 가능 → 회귀 catch rate ↑.
- **대상 actor**: rev sub-agent (PR 단위 direct curl QA) + fe / be sub-agent (PR push 후 자동 dev URL 받음) + nmae (deploy 실패 시 alert 처리).

## 2) 사용자 시나리오

- **시나리오 1 (단계 1 PR 머지 전 rev QA)**: be sub-agent 가 `POST /api/v1/recommendations` schema 변경 PR push → CI workflow 자동 trigger → `pr-1234.dev.mobruji.app` 격리 deploy → rev sub-agent 가 `curl https://pr-1234.dev.mobruji.app/api/v1/recommendations` 호출 + JSON schema / 결정성 / p95 검증 → `reviewed:claude` 라벨.
- **시나리오 2 (PR close 자동 cleanup)**: PR #1234 머지 또는 close → workflow trigger → 격리 환경 자동 teardown (container 중지 + 도메인 reclaim). 비용 / 리소스 누수 방지.
- **시나리오 3 (동시 PR 다수)**: PR #1234 / #1235 / #1236 동시 open → 각각 독립된 URL prov → rev sub-agent 가 PR 별 병렬 QA 가능. dev 서버 race 사고 (rev-direct-qa-extension.md §3 후보 A 한계) 해소.

## 3) 요구사항

### 기능 요구사항

#### 3-1) PR 브랜치별 격리 URL prov

- PR push / synchronize 시 격리 URL 자동 생성 (예: `pr-<N>.dev.mobruji.app` 또는 platform-specific URL).
- URL 은 backend (`/api/...`) + web (`/`) 둘 다 노출. nginx reverse proxy 또는 platform 의 자동 routing 활용.
- PR close (머지 / 닫힘) 시 자동 teardown (workflow `on: pull_request: types: [closed]`).

#### 3-2) 인프라 후보 (proposed 비교 표)

| 후보 | 메커니즘 | 비용 (월) | secret 주입 | TTL | 장점 | 단점 |
|---|---|---|---|---|---|---|
| **(a) ngrok PR tunnel** | NCP dev VM 안 PR 별 docker compose project + ngrok tunnel 신설 | $0 (free tier — 1 동시 tunnel) / $8 (basic — 무제한) | NCP VM 의 env 파일 inline | PR close 시 즉시 teardown (workflow trigger) | NCP 비용 0 / 기존 Phase 4 인프라 재사용 / SSH-based deploy 동일 패턴 | free tier 동시 tunnel 1 한계 — 다수 PR race 미해소 / basic tier 비용 |
| **(b) fly.io PR preview app** | fly.io `flyctl deploy --app mobruji-pr-<N>` per PR | $0 (free tier — 3 shared CPU VM 256MB) / $5+ (scale up) | fly.io secrets API | PR close 시 `flyctl apps destroy` | 격리 강 (per-app) / 자동 cleanup CLI / public 도메인 자동 | free tier 3 VM 한계 (be + web = 2 VM/PR → max 1 동시 PR) / 외부 SaaS 의존 (보안 정책 점검 필요) |
| **(c) vercel preview (web 만)** | vercel git integration — PR push 시 web 만 preview 자동 deploy | $0 (free tier — 무제한 preview, hobby) | vercel env var | PR close 시 자동 (vercel) | web 격리 격리 강 + 자동 / 비용 0 | backend 분리 필요 — backend 격리 deploy 는 (a) 또는 (b) 와 조합 / web-only PR 만 적용 가능 |
| **(d) NCP VM 내 docker compose project + port range** | NCP 1 VM 안 PR 별 docker compose project name (`-p pr-<N>`) + port range allocation (예: backend 8080+N, web 3000+N) + nginx 동적 routing | $0 (기존 NCP VM 재사용) | NCP VM env 파일 inline | PR close 시 `docker compose -p pr-<N> down` | 비용 0 / 기존 인프라 재사용 / 보안 정책 위반 X | NCP VM 4GB RAM 한계 (ncp-dev-deployment.md §3 비기능 메모리 예산) — 동시 PR 다수 (2+) 시 OOM risk / port range 관리 |

**proposed**: 본 spec 작성 시점에서는 후보 선정 X (§8 Q1 사용자 결정 대기). 1차 도입은 단순 + 비용 0 의 **(d) NCP VM 내 docker compose project + port range** 권고 검토 — 단, NCP VM 메모리 한계로 동시 PR max 1-2 → 단계 1 부분 적용 + fallback (race 발생 시 큐). 동시 PR 5+ 운영 필요 시 (b) fly.io 또는 (a) ngrok basic tier 로 격상.

#### 3-3) TTL / cleanup 정책

- PR close (머지 / 닫힘) trigger 시 즉시 teardown (workflow `on: pull_request: types: [closed]`).
- **stale PR 가드**: PR open 14 일 + 마지막 push 7 일 경과 PR 은 nightly cron 으로 격리 환경 teardown (PR 재open 시 다시 deploy). 비용 / 리소스 누수 방지.
- PR re-open / push 시 자동 redeploy.
- 격리 환경 healthcheck fail 5 분 지속 시 자동 teardown + Discord rev DIGEST push (`rev-skip-env-down:dev-pr-isolation` 라벨, `rev-qa-protocol §5-9` 룰 reference).

#### 3-4) secret 주입

- backend application.yml / web NEXT_PUBLIC_* / DB 비밀번호 / 외부 API key (Spotify / MusicBrainz / YouTube) 주입 메커니즘.
- 후보별 매핑:
  - (a) ngrok / (d) NCP VM: 기존 `~/mobruji/secrets/` 디렉토리 (Phase 4 인프라) 의 env 파일을 docker compose `env_file` 로 inline mount. PR 별 신규 secret 불요.
  - (b) fly.io: `flyctl secrets set` API. GitHub Actions secret 에서 sync (workflow `secrets.FLY_API_TOKEN` 의존).
  - (c) vercel: vercel env var (project / preview environment 분리). backend 격리는 (a)/(d) 와 조합.
- **금지**: PR body / commit / workflow yaml 에 secret 평문 박지 않음. `04-security-policy.md` 준수.

#### 3-5) Discord 가시화

- 격리 deploy 성공 시 PR cycle forum thread 본문 PATCH (sub-agent.md §1-11 룰 reference) — 진행 체크리스트에 "🌐 PR 격리 URL: <url>" line 추가.
- 격리 deploy 실패 시 fe / be sub-agent 가 nmae 보고 (`AskUserQuestion` 금지, PR 본문 `## 사용자 확인 필요` 섹션 명시).
- teardown / 재배포는 silent (cycle forum thread 본문 PATCH 시 line 갱신 / 제거).

### 비기능 요구사항

- **deploy wall-clock**: PR push → 격리 URL 응답 가능까지 < 5 분 ((d) docker compose project 기준 backend 빌드 60-120s + web 빌드 30-60s + healthcheck 30s).
- **동시 PR 한도**: 후보 (d) 기준 NCP VM 4GB RAM 한계 → 동시 격리 환경 max 1-2 (각 backend 512MB + web 200MB + MySQL 공유). 동시 PR 3+ 시 큐 메커니즘 또는 후보 (b) fly.io 격상.
- **PR close → teardown 지연**: < 1 분 (workflow trigger + container down).
- **비용**: 후보 선정 결과 (§8 Q1) 에 따라 $0 (1차 권고) ~ $20/월 (fly.io scale up). 사용자 confirm 의무 — high-stakes 결정 (sub-agent.md §1-2).
- **보안**: secret raw 출력 금지 (`04-security-policy.md`) + 격리 URL 은 public exposure (PR 단위 인증 가드 X — dev 환경 한정). production 데이터 / 사용자 데이터 noaccess.
- **graceful 실패**: NCP VM 메모리 한계 hit 시 / 외부 platform (fly.io / vercel) API down 시 workflow skip + DIGEST push + 단계 2 (develop 머지 후) fallback.

## 4) 범위 / 비범위

### 포함

- PR 브랜치별 격리 dev URL prov / teardown 인프라 후보 비교 + 1 후보 선정 트리거 (§8 Q1).
- TTL / stale PR 가드 / 자동 cleanup 룰.
- secret 주입 메커니즘 후보별 매핑.
- Discord cycle forum thread 본문 PATCH 가시화 룰.
- rev sub-agent 의 단계 1 direct curl QA 인프라 의존성 해소 (rev-direct-qa-extension.md §3 후보 A 한계).

### 제외 (Out of Scope)

- **production 환경 PR 격리 deploy**: production 환경 (deployment-infrastructure.md Hetzner CX22) 머지 의존 — 본 spec 은 dev 한정.
- **visual regression baseline**: visual-regression-ci.md §6 PR 3 (Playwright workflow) 머지 의존 — 본 spec 의 격리 URL 은 visual regression 의 단계 1 캡처 대상 후보로만.
- **단계 2 dev nightly (develop tip)**: ncp-dev-deployment.md §3 shipped — 본 spec 은 PR 브랜치 격리만.
- **PR 단위 인증 가드 / RBAC**: 격리 URL 은 public exposure (사용자 데이터 / production secret 미주입). PR 단위 인증 가드는 별 spec.
- **외부 SaaS (percy / Chromatic) 통합**: ADR-0026 Alternatives A 와 동일 보안 정책 위반 risk 채택 X.
- **DB 격리 (PR 별 독립 DB)**: 1차 도입은 MySQL 공유 (read-only). PR 단위 schema 변경 / migration race 는 별 spec (`dev-pr-db-isolation` 후보).
- **fe / be 분리 PR**: 본 spec 은 fe + be 동시 격리 (full-stack PR 도 동작) 가정. fe-only / be-only PR 의 partial deploy 최적화는 §8 Q3.

## 5) 설계

### 5-1) 도메인 모델

- 새 도메인 용어: `dev_pr_isolated_url` (PR 브랜치별 격리 dev URL). `06-domain-model.md §4` 등재 검토 — 본 spec 의 핵심 컨셉, 다른 spec 에서 cross-reference 발생 시 (예: rev-direct-qa-extension.md §3 후보 A 머지 후 갱신) 등재 권고.

### 5-2) API 엔드포인트

- 신규 endpoint 없음. 격리 URL 은 기존 endpoint (backend `/api/...` + web `/`) 의 alternative 호스트.

### 5-3) 외부 연동

- **NCP VM (후보 d)**: `101.79.20.94` 의 docker daemon — Phase 4 머지 완료, SSH-based deploy 동일 패턴.
- **ngrok API (후보 a, 옵션)**: `https://api.ngrok.com/tunnels` — basic tier ($8/월) 필요 시. free tier 동시 tunnel 1 한계.
- **fly.io API (후보 b, 옵션)**: `flyctl deploy --app mobruji-pr-<N>`. GitHub Actions secret `FLY_API_TOKEN`.
- **vercel API (후보 c, 옵션, web 만)**: vercel git integration — 자동 trigger / 추가 secret 불요.

### 5-4) 데이터 흐름 / 시퀀스

후보 (d) NCP VM docker compose project + port range 기준:

```
1. fe / be sub-agent PR push (예: PR #1234)
2. GitHub Actions: cd-dev-pr.yml trigger (`on: pull_request: types: [opened, synchronize]`)
3. SSH NCP VM: docker compose -p pr-1234 -f docker-compose.dev-pr.yml up -d --build
   - backend container: 8080+N port (예: 8134)
   - web container: 3000+N port (예: 3034)
4. nginx config 동적 add: pr-1234.dev.mobruji.app → web:3034, pr-1234-api.dev.mobruji.app → backend:8134
5. healthcheck (`curl http://localhost:8134/actuator/health`) → wait until ready
6. Discord cycle forum thread 본문 PATCH (sub-agent.md §1-11 룰) — "🌐 PR 격리 URL: https://pr-1234.dev.mobruji.app"
7. rev sub-agent: curl https://pr-1234-api.dev.mobruji.app/api/v1/recommendations 직접 호출 + QA
8. PR close 시 cd-dev-pr-cleanup.yml trigger → docker compose -p pr-1234 down + nginx config remove
```

### 5-5) DB 마이그레이션

- 1차 도입은 MySQL 공유 (read-only 데이터). PR 단위 schema 변경 / migration 격리는 별 spec (Out of Scope §4).
- backend application.yml 의 `spring.flyway.locations` 가 PR 별 schema 다를 시 race 발생 → §8 Q4.

### 5-6) 프론트엔드 화면 (해당 시)

- 본 spec 자체는 화면 변경 X. web container 의 `NEXT_PUBLIC_API_URL` 만 PR 별 격리 backend URL 로 주입.

## 6) 작업 분할 (예상 PR 리스트)

본 spec 머지 후 후속 PR 분할:

- [ ] **PR 1 (이번 사이클, plan)**: 본 spec 머지 (`docs/features/dev-pr-branch-deploy-isolation.md` 신설, status=draft).
- [ ] **PR 2 (별 사이클, plan)**: §8 Q1 사용자 후보 선정 (a/b/c/d) 직후 spec status `draft` → `approved` + `## 9 결정 로그` 갱신.
- [ ] **PR 3 (후보 (d) 채택 시, infra)**: `docker-compose.dev-pr.yml` + `.github/workflows/cd-dev-pr.yml` (deploy trigger) + `.github/workflows/cd-dev-pr-cleanup.yml` (close trigger). NCP VM nginx 동적 config 갱신 스크립트.
- [ ] **PR 4 (후보 (b) 채택 시, infra)**: `fly.toml` (mobruji-pr-* template) + `.github/workflows/cd-dev-pr-fly.yml` + GitHub Actions secret 설정 (사용자 의무).
- [ ] **PR 5 (rev-direct-qa-extension.md §3 후보 A 업데이트, plan)**: 격리 URL 머지 후 단계 1 적용 룰 명시 갱신.
- [ ] **PR 6 (스테일 PR cleanup nightly cron, infra)**: `.github/workflows/dev-pr-stale-cleanup.yml` 신설 (14 일 + 7 일 룰).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 있음 — 후속 PR 3 / 4 / 6 가 `.github/workflows/` 신설 + (후보 (d)) `docker-compose.dev-pr.yml` 신설 + (후보 (a)/(d)) NCP VM 인프라 env 파일 변경 가능.
  - 본 spec PR (PR 1) 자체는 docs 만 — 보호 영역 변경 없음.
  - **정보성 분류** (CLAUDE.md §4 2026-05-28: `needs-human-review` 라벨 의무 폐지, rev 사이클이 review 대행). 후속 PR 3 / 4 / 6 은 rev 단계 1 가중도 정보로 활용.

## 7) 테스트 전략

- 본 spec 자체는 docs 만 — 테스트 X.
- 후속 PR 별 테스트 전략:
  - PR 3 (후보 d): workflow first run = PR 더미 (no-op) 로 격리 URL prov / healthcheck / teardown 흐름 검증. 동시 PR 2 (수동 trigger) 로 race 가드 확인.
  - PR 4 (후보 b): fly.io free tier 한계 (3 VM) 검증 — 4 번째 PR push 시 graceful skip + DIGEST push 동작.
  - PR 6 (stale cleanup): 14 일 + 7 일 룰 - 가짜 timestamp injection 으로 cleanup trigger 검증.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | 인프라 후보 선정 — 1차 도입 | (a) ngrok / (b) fly.io / (c) vercel web only + (a)/(d) backend 조합 / (d) NCP VM docker compose project + port range (1차 권고) | @goohong / PR 2 머지 전 |
| Q2 | NCP VM 메모리 한계 (4GB) — 동시 PR 한도 | (a) max 1 (race 발생 시 큐) / (b) max 2 (각 컨테이너 메모리 limit 강제) / (c) (b) fly.io 격상 | @goohong / PR 3 머지 전 |
| Q3 | fe-only / be-only PR 의 partial deploy 최적화 | (a) full-stack deploy (단순) / (b) PR diff scope detection + partial deploy (복잡) | @goohong / PR 3 머지 후 |
| Q4 | DB schema migration race (PR 별 다른 Flyway location) | (a) MySQL 공유 (1차) / (b) PR 별 독립 DB (별 spec dev-pr-db-isolation) / (c) Flyway dry-run 만 | @goohong / PR 3 머지 전 |
| Q5 | TTL 정책 — stale PR cleanup 임계 | (a) open 14 일 + 마지막 push 7 일 (proposed) / (b) open 30 일 + push 14 일 / (c) cleanup 안 함 (사용자 명시 close 만) | @goohong / PR 6 머지 전 |
| Q6 | 격리 URL public exposure 보안 — production 데이터 noaccess 가드 | (a) dev DB seed 데이터만 (proposed) / (b) production DB read-only mirror / (c) PR 단위 인증 가드 (별 spec) | @goohong / PR 3 머지 전 |
| Q7 | rev-direct-qa-extension.md §8 Q2 와 본 spec 의 매핑 — 후보 (a) "별 spec 분리" 트리거 완료 시점 | (a) 본 spec status `approved` 시점 / (b) 본 spec PR 3 머지 시점 / (c) 본 spec PR 6 머지 시점 | @goohong / PR 2 머지 전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-05-29**: 초안 작성 (status=draft). 트리거 — rev-direct-qa-extension.md §8 Q2 후보 (a) "별 spec 분리" + plan round 5 작업 지시 C 항. 본 spec 은 인프라 후보 / 비용 / TTL / secret 주입 결정만 박는다 — 실제 workflow / Dockerfile / nginx config 는 후속 PR 분할.
- **2026-05-29**: §3-2 후보 비교 — 1차 도입 권고 (d) NCP VM docker compose project + port range (비용 0 / 기존 인프라 재사용 / 보안 정책 준수). 동시 PR 5+ 운영 필요 시 (b) fly.io 격상 후보. 사용자 확정은 §8 Q1.
- **2026-05-29**: §3-3 TTL 정책 proposed — PR close 즉시 teardown + open 14 일 / push 7 일 stale 가드. §8 Q5 사용자 결정.
- **2026-05-29**: §3-4 secret 주입 — 후보별 매핑 + PR body / commit / workflow yaml 평문 박지 않음 (`04-security-policy.md` 준수).
- **2026-05-29**: §4 제외 — production 환경 격리 / visual regression baseline / DB 격리 / fe-be 분리 PR 최적화 / 외부 SaaS (percy/Chromatic) / PR 단위 인증 가드 별 spec 분리.
