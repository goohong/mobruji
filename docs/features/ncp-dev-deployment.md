---
feature: Phase 4 — NCP maestro VM에 mobruji dev 환경 docker 격리 배포
slug: ncp-dev-deployment
status: shipped
owner: @mobruji-maestro
scope: infra
related_issues: [#356]
related_prs: []
last_reviewed: 2026-05-23
---

# Phase 4 — NCP maestro VM에 mobruji dev 환경 격리 배포 (A안)

## 1) 개요 (What / Why)
**현재 상태**: mobruji 코드는 develop 머지가 되어도 어디에도 배포되지 않는다. rev sub-agent QA는 local 3-tier (사용자 mac)에서만 가능. 외부 의존(Spotify/MusicBrainz 등) 통합 검증, 사용자 dogfooding, NCP maestro의 자율 사이클 검증을 위해 **dev 환경**이 필요하다.

**Phase 4의 선택**: 운영(prod) 배포 인프라(`docs/features/deployment-infrastructure.md`, Hetzner CX22 1차 스택)는 v0.4 진입 후 구축한다. 그 전 단계로 — **NCP maestro VM(101.79.20.94, 4GB RAM, Ubuntu 24.04)에 docker container로 dev 환경을 격리해서 같이 올린다.** 추가 호스트 비용 0원.

**왜 같은 VM인가**: 4GB RAM 안에 maestro(500MB) + bridge(50MB) + backend(512MB) + web(200MB) + MySQL(400MB) + 시스템(800MB) = 약 2.5GB가 들어가고, container 메모리 limit + swap으로 안전마진 확보 가능. dev는 24/7 SLA가 아니므로 maestro 사이클 부하 spike와 겹쳐도 OK.

**대상 액터**:
- **rev sub-agent**: dev URL로 통합 시나리오 QA 수행 (local 3-tier 셋업 시간 절약).
- **사용자**: 모바일에서 dev 인스턴스를 직접 brouse → 베타 dogfooding.
- **외부 의존 검증**: Spotify/MusicBrainz/YouTube API의 진짜 응답으로 결정성 회귀 가능.

## 2) 사용자 시나리오
- **(S1) rev 통합 QA**: PR #N 머지 직후 maestro가 develop으로 dev 환경 자동 재배포 → rev sub-agent가 `https://dev-api.mobruji.app/api/v1/recommendations/...` 호출 → 회귀 발견 시 maestro에 보고.
- **(S2) 사용자 dogfooding**: 외출 중 모바일에서 `https://dev.mobruji.app` 접속 → 음역 입력 → 추천 결과 받아봄 → Discord에 피드백 한 줄 → maestro가 후속 이슈 등록.
- **(S3) 외부 의존 회귀**: Spotify API rate limit 변경 → dev 환경 backend가 500 에러 → grafana/journalctl에서 잡힘 → maestro가 type:bug 이슈 등록.
- **(S4) 빠른 롤백**: dev 배포가 깨졌을 때 maestro가 `docker compose -f docker-compose.dev.yml up -d --no-deps backend` 로 이전 이미지 tag 재기동 (30s 안).

## 3) 요구사항
### 기능 요구사항
- [x] **Dockerfile (backend)**: `backend/Dockerfile` — Spring Boot bootJar multi-stage 빌드, JRE 21 slim 베이스. AMD64 (NCP VM은 x86_64).
- [x] **Dockerfile (web)**: `web/Dockerfile` — Next.js production build, standalone output. Node 22 alpine.
- [x] **`docker-compose.dev.yml`**: 4 서비스 (mysql + backend + web + nginx). 메모리 limit 강제. MySQL volume mount.
- [ ] **`docker-compose.dev.env.example`**: 필수 env 키 (DB 비밀번호, NEXT_PUBLIC_API_URL 등) + 채우는 가이드.
- [x] **nginx reverse proxy**: `dev.mobruji.app` → web:3000, `dev-api.mobruji.app` → backend:8080. dev 단계는 plain HTTP 또는 self-signed (운영 도메인 미발급).
- [x] **GitHub Actions CD** (`.github/workflows/cd-dev.yml`): `develop` 머지 trigger → SSH로 NCP에 `cd ~/mobruji && git pull && docker compose -f docker-compose.dev.yml up -d --build` → healthcheck (`curl http://localhost:8080/actuator/health`) → Discord 알림 (기존 `discord-notify.yml`이 release 이벤트 잡지만, 별도 webhook은 X — `discord-status-push.md` 룰).
- [x] **swap 추가** (NCP 사전 작업): `sudo fallocate -l 1G /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile` + `/etc/fstab` 영속화 (PR D, NCP 부트스트랩에 포함).
- [x] **docker 설치** (NCP 사전 작업): `apt install docker.io docker-compose-v2` + `usermod -aG docker mobruji` (PR D).
- [x] **`docs/runbooks/ncp-maestro-setup.md` 갱신**: docker 설치 + swap + dev 배포 가동 절차 추가.
- [ ] **`docs/features/rev-qa-protocol.md` §5-4 갱신**: dev 환경 row 추가, URL + 사용 가이드.

### 비기능 요구사항
- **메모리 예산** (4GB RAM 분배):
  | 컴포넌트 | limit | 비고 |
  |---|---:|---|
  | maestro (claude TUI) | (제한 없음, ~500MB) | tmux 안 ProcessLimit 없음 |
  | mobruji-discord-bridge | ~50MB | systemd MemoryLimit 추후 |
  | container: backend (Spring Boot) | **600MB** | -Xmx512m, JVM overhead 100MB |
  | container: web (Next.js) | **256MB** | next start --turbopack |
  | container: mysql 8.4 | **512MB** | innodb_buffer_pool_size=128M, performance_schema=OFF |
  | container: nginx | **64MB** | |
  | 시스템 + buffer | ~800MB | OS, journald, sshd, snapd |
  | **합계** | **~2.8GB** | swap 1GB 보강 권장 |
- **결정성**: 이미지 tag = git SHA 7자. drift 방지. SSH 들어가 수동 변경 시 다음 배포에 덮어쓰임.
- **graceful restart**: backend는 SIGTERM 30s → bootJar exit. web은 Next.js standalone server.close().
- **secret 관리**: `~/mobruji/.env.dev` (chmod 600). gitignored. GitHub Actions Secrets로 NCP SSH 키만 보유 (NCP_SSH_KEY).
- **관측성**: 1차는 journalctl + docker logs. `observability-baseline.md` Phase 1 metrics는 prod 한정. dev 환경 알람 X.
- **롤백 RTO**: `docker compose ... up -d --no-deps backend` 로 ≤ 60s.
- **응답시간**: dev는 SLO 없음. local 3-tier보다 느려도 OK.
- **보안**:
  - dev container들은 외부 expose 안 함 (nginx만 80/443). MySQL은 container internal network.
  - dev 환경에 실 사용자 PII 들어가지 않음 (rev/사용자 본인 dogfooding 한정).
  - secret 하드코딩 금지 (CLAUDE.md §4).

## 4) 범위 / 비범위
### 포함
- NCP maestro VM에 docker 격리로 backend + web + MySQL + nginx 4 container 가동
- GitHub Actions CD (develop merge trigger)
- 사전 인프라 (swap, docker 설치)
- 운영 런북 갱신 + rev-qa-protocol §환경 갱신
- 메모리 limit + healthcheck + 롤백 절차

### 제외 (Out of Scope)
- **prod 환경**: `deployment-infrastructure.md` (Hetzner CX22) 가 담당. v0.4 진입 후.
- **multi-AZ / HA / blue-green**: dev는 1 VM, downtime 허용.
- **공식 도메인 (dev.mobruji.app)**: Cloudflare DNS는 prod 와 묶음. dev는 1차로 `http://101.79.20.94/` 직접 또는 hosts 파일 매핑.
- **SSL 인증서**: dev는 plain HTTP. Cloudflare Origin CA 발급은 prod와 묶음.
- **MySQL replica / 백업 cron**: dev DB는 휘발 가능 (gitignored volume). 백업 prod 머지 후.
- **Spotify/MusicBrainz API keys**: 사용자가 dev env에 직접 채움. 자동 발급 X.
- **rev sub-agent 자동 dev 호출**: rev-qa-protocol §5-4 가이드에 dev URL 추가만. 실제 호출 라우팅은 rev-qa-protocol 후속 PR.

## 5) 설계
### 5-1) 토폴로지
```
                          ┌────────────────────────────────────────────────┐
                          │  NCP VM 101.79.20.94 (Ubuntu 24.04, 4GB RAM)   │
                          │                                                 │
  사용자 → port 80/443 ─→ │  nginx (container:64M) ─→ web (container:256M) │
                          │                          ─→ backend (cont:600M)│
                          │                                     ↓          │
                          │                          mysql (container:512M)│
                          │                                                 │
                          │  [별 프로세스: mobruji-maestro tmux Claude]    │
                          │  [별 프로세스: mobruji-discord-bridge systemd] │
                          │                                                 │
                          │  swap 1GB + 시스템 OS                          │
                          └────────────────────────────────────────────────┘
```

### 5-2) 서비스 매트릭스
| service | image | memory limit | volumes | ports (host:cont) | restart |
|---|---|---:|---|---|---|
| mysql | mysql:8.4.6 | 512m | `mobruji-mysql-dev:/var/lib/mysql` | 13307:3306 (옵션) | unless-stopped |
| backend | mobruji/backend:`<gitSha>` | 600m | — | (internal only) | unless-stopped |
| web | mobruji/web:`<gitSha>` | 256m | — | (internal only) | unless-stopped |
| nginx | nginx:alpine | 64m | `./nginx/conf.d:/etc/nginx/conf.d:ro` | 80:80, 443:443 | unless-stopped |

local docker-compose (`docker-compose.yml`) 는 mysql 만 (테스트용). dev 환경은 별 파일 `docker-compose.dev.yml` 에 격리.

### 5-3) CD trigger / 흐름
```
PR merge to develop
  ↓
GitHub Actions: cd-dev.yml
  ├─ ssh into NCP (NCP_SSH_KEY secret)
  ├─ cd ~/mobruji && git fetch origin develop && git reset --hard origin/develop
  ├─ export GIT_SHA=$(git rev-parse --short HEAD)
  ├─ docker compose -f docker-compose.dev.yml build --build-arg GIT_SHA=$GIT_SHA
  ├─ docker compose -f docker-compose.dev.yml up -d
  ├─ wait healthcheck (curl /actuator/health, max 90s)
  ├─ on fail → docker compose ... up -d (이전 이미지로 fallback)
  └─ exit code → discord-notify.yml은 PR merged 이벤트만 잡음. 별도 dev 알림 미설치 (Phase 4 OOS).
```

healthcheck 통과 시 Actions log에 SHA + duration 기록. 실패 시 GitHub event = workflow run failure → 자동 webhook 미적용 (workflow_run 이벤트 → discord-notify.yml은 잡지 않음). 이건 후속 PR로 enhancement.

### 5-4) 외부 연동
- **GitHub Actions → NCP SSH**: `NCP_SSH_HOST`, `NCP_SSH_USER=mobruji`, `NCP_SSH_KEY` (GitHub Secrets).
- **MySQL initial schema**: dev 첫 부팅 시 Flyway가 baseline + 마이그레이션. 운영과 동일.

### 5-5) DB 마이그레이션
- `backend/src/main/resources/db/migration/V*.sql` 그대로 적용 (Flyway).
- dev DB는 schema drift 허용 (필요 시 volume drop + 재부팅).

### 5-6) 프론트엔드 화면
- `NEXT_PUBLIC_API_BASE_URL=https://dev-api.mobruji.app` (또는 1차는 `http://101.79.20.94:8080`).
- 별도 화면 변경 없음. dev/prod 분기는 env로만.

## 6) 작업 분할 (PR 리스트)
순서가 중요. 앞 PR 머지 후 다음 PR.

- [ ] **PR A (본 spec)**: `docs/features/ncp-dev-deployment.md` 신설 + 본 spec 머지 (이 PR).
- [ ] **PR B**: `backend/Dockerfile` + `web/Dockerfile` + `docker-compose.dev.yml` + `nginx/conf.d/dev.conf` + `.dockerignore` + `docker-compose.dev.env.example`. 메모리 limit / healthcheck 포함. **NCP 사전 셋업 (docker 설치 + swap)도 같은 PR에서 `tools/deploy/ncp-bootstrap-dev.sh` 으로 IaC.**
- [ ] **PR C**: `.github/workflows/cd-dev.yml` — develop merge → SSH → docker compose up. healthcheck + 자동 fallback. **GitHub Secret `NCP_SSH_KEY` 등록은 사용자 액션** (PR body에 명시).
- [ ] **PR D**: `docs/runbooks/ncp-maestro-setup.md` 갱신 — docker 설치 + swap + dev 배포 가동 절차. (PR B에 묶을지 검토.)
- [ ] **PR E**: `docs/features/rev-qa-protocol.md` §5-4 환경 가이드 갱신 — dev 환경 row 추가, URL.
- [ ] **PR F (옵션)**: `discord-notify.yml` 또는 신규 workflow → dev CD 성공/실패 알림 push.

## 7) 테스트 전략
- **PR A**: 문서 spec만. 테스트 없음.
- **PR B**:
  - `docker build` + `docker compose -f docker-compose.dev.yml up -d` 로컬에서 1회 검증 (NCP 또는 사용자 mac).
  - container 메모리 limit 적용 검증: `docker stats`.
  - healthcheck wire 확인: `curl http://localhost:8080/actuator/health` 200.
- **PR C**:
  - 첫 머지 후 GitHub Actions run 성공 확인.
  - NCP에서 `docker compose ps` 4 container 모두 healthy.
  - 사용자 모바일에서 `http://101.79.20.94/` 응답 확인.
- **PR E**: rev sub-agent가 다음 사이클에서 dev URL을 실제 사용하는지 (1주 운영 데이터).

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | dev 도메인 발급 시점 | (a) prod와 묶어 v0.4 / (b) Phase 4 안에 별도 발급 | @user / PR B 머지 후 |
| Q2 | dev DB seed | (a) 빈 DB + Flyway baseline / (b) `db/seed/` 디렉토리 신설 후 seed SQL | @user / PR B |
| Q3 | dev CD 실패 시 알림 채널 | (a) 같은 #모부르지 / (b) 별도 #dev-ops | @user / PR F |
| Q4 | maestro 사이클이 dev 부하와 충돌하면 | (a) maestro 일시 중단 후 재배포 / (b) container CPU/memory limit으로 격리 충분 | maestro 운영 데이터 / 1주 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 (status=draft). 사용자 위임 — Phase 4 NCP dev 배포. A안 채택 (같은 VM docker 격리). 운영 환경(`deployment-infrastructure.md` Hetzner CX22)와 분리. 4GB RAM 분배 매트릭스 정의. 분할 PR (A spec / B Dockerfile+compose / C CD / D 런북 / E rev-qa / F 알림).
