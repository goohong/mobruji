---
feature: 운영 배포 인프라 (Phase 5 prod — NCP 별 VM + Cloudflare, ADR-0015 재결정 반영)
slug: deployment-infrastructure
owner: @goohong
scope: infra
status: draft
related_issues: [242, 243]
related_prs: []
last_reviewed: 2026-05-23
---

# 운영 배포 인프라 (Phase 5 prod — NCP 별 VM + Cloudflare)

> **2026-05-23 재정렬 메모**: 본 spec 은 2026-05-22 1차안 (Hetzner CX22 + Vercel + Cloudflare) 으로 작성됐으나, 같은 날 [ADR-0015 재결정](../decisions/0015-hosting-stack.md) 으로 **NCP** 가 maestro / 백 / 프론트 1차 스택으로 채택됐다. 본 spec §10 가 ADR-0015 재결정 후 prod 진행 plan + Phase 4 NCP dev 운영 경험을 반영해 갱신한다. 본문 §1~§9 의 "Hetzner CX22" / "Vercel" 표현 중 일부는 historical context 로 유지되며, 운영 결정은 §10 단일 진실. 본 spec 의 ADR-0015 재결정 반영 후속 PR (NCP prod 토폴로지 / 백·프론트 VM 사양 / 도메인) 은 §6 PR 표 의 PR A1~A3 로 추가됐다.

## 1) 개요 (What / Why)

- mobruji 는 v0.3 P2 까지 로컬 docker-compose(MySQL) + `./gradlew bootRun` + `npm run dev` 만 가능했다. 외부 접근/사용자 가시 운영 환경이 없다.
- v0.4 (`#243` 사용자 계정 시스템) 진입 전에 **운영 배포 인프라 1차 셋업**을 끝내, 베타 테스터 수 명 + 운영자 본인이 실 서비스 환경에서 검증할 수 있게 한다.
- 호스팅/배포 결정은 **ADR-0015 (호스팅 스택)** 으로 분리. 본 spec 은 ADR-0015 결정을 **실제 셋업 절차 + PR 분할 + 운영 런북** 으로 구체화한 living 명세서.
- 대상 액터: 운영자 1인. 목표 = SSH 한 번으로 점검/배포/롤백, 1인 운영 부담 0.

## 2) 사용자 시나리오

- (S1) **베타 테스터 시나리오**: 사용자가 `https://mobruji.app` 접속 → Vercel 이 Next.js SSR 응답 → 음역 입력 → web 이 `https://api.mobruji.app/api/v1/sessions/.../voice-range-history` 호출 → Cloudflare proxy → CX22 의 Spring Boot → MySQL → 응답.
- (S2) **운영자 PR 머지 시나리오**: develop 머지 → GitHub Actions 가 `bootJar` 빌드 + GHCR push + CX22 SSH `systemctl restart mobruji-backend` → Discord webhook 으로 "배포 완료 + 빌드 SHA" 알림.
- (S3) **장애 응답 시나리오**: 운영자 iPhone 에 Discord 알림 "추천 p95 600ms 초과" 도착 → Grafana Cloud 대시보드 링크로 확인 → SSH 들어가 `systemctl restart mobruji-backend` 또는 직전 SHA 로 롤백 (`docker pull ghcr.io/.../mobruji-backend:<prevSha> && systemctl restart`).
- (S4) **백업 복구 시나리오**: VM 디스크 손상 → R2 에서 직전 24h mysqldump 다운로드 → 새 VM 프로비저닝 후 `mysql < dump.sql` → DNS A 레코드 갱신 (Cloudflare 1분 TTL).

## 3) 요구사항

### 기능 요구사항

- [ ] **호스팅 결정 머지** (ADR-0015): Hetzner CX22 + Vercel Hobby + Cloudflare 1사 채택 합의 (PR A).
- [ ] **운영용 Dockerfile**: Spring Boot bootJar 를 multi-stage 빌드, ARM64 호환 (Hetzner CX22 = ARM), 환경변수 주입 가능, healthcheck 정의 (PR B).
- [ ] **`docker-compose.prod.yml`**: backend + mysql + grafana-agent 3 서비스. `.env` (gitignored) 외부 주입. 운영 1차는 systemd 가 단일 진실, compose 는 backup/검증 경로 (PR B).
- [ ] **systemd unit**: `mobruji-backend.service` 작성 — `EnvironmentFile=/etc/mobruji/backend.env`, `Restart=on-failure`, `StartLimitInterval=300s/StartLimitBurst=5` (audio backfill systemd 가드와 동일 패턴), graceful shutdown timeout 30s (PR B).
- [ ] **GitHub Actions CD workflow** (`.github/workflows/deploy-backend.yml`): develop / main 머지 trigger, `bootJar` 빌드 → Docker 이미지 build → GHCR push → SSH 로 CX22 에 `docker pull && systemctl restart` (PR C).
- [ ] **Web 배포는 Vercel GitHub 연동**: `web/` 폴더 변경 시 자동 빌드/배포. Preview = PR, Production = main. (PR C 의 셋업 가이드 안).
- [ ] **도메인 + DNS + SSL**: Cloudflare Registrar 로 `mobruji.app` 등록 (또는 차순위 `mobruji.kr`), DNS A 레코드 (`@` = Vercel, `api` = CX22 IP), Cloudflare Full(strict) + Origin CA 15년 인증서, HSTS preload (PR D).
- [ ] **Reverse proxy / TLS termination 셋업**: CX22 위에 **Caddy** (Let's Encrypt 자동 + Cloudflare Origin CA 모두 지원) — `api.mobruji.app` → `127.0.0.1:8080` (Spring), `metrics.mobruji.app` → `127.0.0.1:8081` (Actuator, admin token 게이트) (PR E).
- [ ] **무중단 배포 결정**: 1차는 단순 `systemctl restart` (30~60s 다운). blue-green / rolling 으로 업그레이드할지 ADR + 구현 (PR F).
- [ ] **백업/복구 spec**: mysqldump cron (매일 02:00 KST) + Cloudflare R2 업로드 + retention 14일 + 복구 절차 런북 (PR G).
- [ ] **운영 환경변수 가이드**: 필수 env 목록, 발급 절차, `.env.example` 배포본 (PR B 안).
- [ ] **첫 부팅 부트스트랩 스크립트**: 새 VM 에 SSH 후 한 줄 실행으로 systemd + Caddy + Grafana Agent + MySQL 까지 셋업 (`tools/deploy/bootstrap-cx22.sh`, PR B).

### 비기능 요구사항

- **예산**: 월 합계 ≤ €5 (CX22 €3.79 + 도메인 원가 분할). Vercel/Cloudflare/Let's Encrypt 모두 무료.
- **가용성 목표 (v0.3 한정)**: best-effort. SLO 없음 (observability-baseline §4 제외 일관). 단, `systemctl restart` 외 다운타임은 분기당 ≤ 60분 목표 (운영자 본인 점검).
- **보안**:
  - 평문 secret/yml/Dockerfile 커밋 금지 (CLAUDE.md §4). 모든 시크릿은 GitHub Actions secrets + `/etc/mobruji/*.env` (0640).
  - SSH = 키 인증만 (`PasswordAuthentication no`), GHA 전용 deploy 키 별도 (`mobruji-deploy@cx22`).
  - 방화벽: ufw `allow 22/tcp from <운영자 IP>`, `allow 80/443 from any` (Cloudflare 만 통과시키도록 Cloudflare IP allowlist 적용, 별 PR 후보).
  - MySQL 외부 노출 금지 (`bind-address=127.0.0.1`).
  - Actuator `/prometheus` 는 management port (8081) + admin token + Cloudflare Access 또는 IP allowlist.
- **관측성**: ADR-0012 그대로. Grafana Agent 가 CX22 의 `/actuator/prometheus` (admin token) scrape → remote_write. 추가 메트릭 신설 없음 (observability-baseline §5-3 표가 단일 진실).
- **배포 fail-fast**: GHA workflow 가 healthcheck (`curl https://api.mobruji.app/actuator/health/liveness`) 통과 후 완료 표시. 5분 안에 200 이 안 오면 직전 SHA 로 자동 롤백 (PR F 의 무중단 ADR 결정 후 활성화).
- **결정성**: 모든 배포 파라미터 (이미지 tag, env, systemd unit) IaC. SSH 들어가 수동 수정한 변경은 다음 배포에 덮어쓰임 — drift 방지.
- **응답시간 영향**: Cloudflare proxy 추가로 first byte +20~40ms (KR PoP). API 호출 p95 (observability-baseline §5-4 표) 목표는 origin 기준 — proxy 포함은 별 KPI 로 추적 (v0.4 후보).

## 4) 범위 / 비범위

### 포함

- 1차 운영 스택 (CX22 + Vercel + Cloudflare) 셋업 절차 + PR 분할
- Dockerfile + docker-compose.prod.yml + systemd unit + GitHub Actions CD
- 도메인 + SSL + reverse proxy (Caddy)
- 백업/복구 spec + 매일 cron
- 첫 부팅 부트스트랩 스크립트
- 환경변수 가이드 + secret 관리 책임 매트릭스

### 제외 (Out of Scope)

- **multi-AZ / multi-region HA**: SLO 미정의 + 트래픽 미미. v0.4 사용자 ≥ 100 활성 후 별도 ADR.
- **CDN edge logic (Cloudflare Workers)**: 정적 자원 캐시만 Cloudflare 기본. edge transform/AB test 는 별 spec.
- **WAF rule 튜닝**: Cloudflare 기본 룰셋만 사용. 1차 머지 후 1주 운영 데이터 보고 결정.
- **Managed DB 전환**: ADR-0015 §Alternatives N/O. 데이터 ≥ 10GB 또는 다중 인스턴스 필요해지면 별도 ADR.
- **Container orchestration (k8s/k3s/nomad)**: 단일 VM 에 systemd 면 충분. 인스턴스 ≥ 3 시 별도 spec.
- **CDN 이미지 최적화 (Cloudflare Polish / Vercel Image Optimization 유료)**: 무료 기본만.
- **Cloudflare Access / Zero Trust SSO**: 운영자 1인 단계에서 과대 — v0.4 다중 운영자 진입 시 결정.
- **Discord daemon 동거**: discord-daemon-hosting spec §7 의 별 호스트(macOS LaunchAgent + Cloudflare Workers) 정책 그대로 유지. 본 spec 의 CX22 에 같이 올리지 않는다.
- **CI 빌드 시간 모니터링**: `docs/features/librosa-ci-build-monitoring.md` 가 단일 진실 (#209-B). 본 spec 은 운영 배포만.
- **Frontend RUM (실사용자 모니터링)**: observability-baseline §4 제외와 일관 — v0.4 후보.
- **DB 마이그레이션 자동 trigger 가드**: Flyway 가 Spring Boot 부팅 시 자동 적용 (현 정책 그대로). pre-deploy hook 별도 추가는 v0.4 트래픽 ≥ 10 RPS 시.

## 5) 설계

### 5-1) 도메인 모델

- 본 spec 은 도메인 엔티티를 추가하지 않는다. 인프라 계층.
- `06-domain-model.md` §4 유비쿼터스 랭귀지 갱신 없음.

### 5-2) 토폴로지

```
                                ┌────────────────────────────┐
                                │   Cloudflare (KR PoP)     │
                                │   DNS + Proxy + WAF +     │
                                │   Origin TLS              │
                                └─────┬───────────┬─────────┘
                                      │           │
                              mobruji.app    api.mobruji.app
                                      │           │
                          ┌───────────▼─┐       ┌─▼──────────────────────┐
                          │  Vercel     │       │  Hetzner CX22 (FSN1 DE)│
                          │  (Next.js   │       │  Ubuntu 22.04 ARM64    │
                          │   App Rtr,  │       │  ─ Caddy (TLS term)    │
                          │   SSR+CDN)  │       │  ─ Spring Boot (8080)  │
                          └─────────────┘       │  ─ MySQL 8.4 (127.0..) │
                                                │  ─ Grafana Agent       │
                                                │  ─ Python audio tool   │
                                                │    (on-demand)         │
                                                └────────┬───────────────┘
                                                         │ remote_write
                                                ┌────────▼───────────────┐
                                                │  Grafana Cloud Free    │
                                                │  (Prom + Grafana +     │
                                                │   Alert)               │
                                                └────────────────────────┘

  운영자 iPhone ─ Discord 알림 ◀── webhook (외부 API 에러율 / p95 초과)
  운영자 Mac ───── Discord daemon (별 호스트, discord-daemon-hosting spec)
                   └── repository_dispatch ──▶ GitHub Actions
```

### 5-3) 배포 파이프라인 (GitHub Actions)

```
PR open ──▶ backend-ci (build + test, 현 워크플로우 그대로)
            web-ci (lint + typecheck + test)
            Vercel preview 빌드 (자동)

develop merge ──▶ deploy-backend.yml (신규):
                     ├─ checkout
                     ├─ setup JDK 21
                     ├─ ./gradlew bootJar -x test
                     ├─ docker buildx build --platform linux/arm64 -t ghcr.io/.../mobruji-backend:<sha>
                     ├─ docker push GHCR
                     ├─ SSH CX22:
                     │    sudo /usr/local/bin/mobruji-deploy.sh <sha>
                     │    # 1. docker pull
                     │    # 2. /etc/mobruji/backend.env 갱신 (선택)
                     │    # 3. systemctl restart mobruji-backend
                     │    # 4. healthcheck poll (5분 timeout)
                     ├─ 알림: Discord webhook "deploy <sha> ok"
                     └─ (실패 시) 자동 롤백 = 직전 SHA 로 systemctl restart

main merge (release) ──▶ deploy-backend.yml + git tag + GitHub Release

web 폴더 변경 ──▶ Vercel GitHub 연동이 자동 처리 (별 workflow 불요)
```

### 5-4) Dockerfile / docker-compose.prod.yml 골격

**`backend/Dockerfile`** (신규, PR B):

```dockerfile
# syntax=docker/dockerfile:1.7
# Multi-stage. ARM64 + amd64 모두 빌드 (buildx).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/gradle /src/gradle
COPY backend/gradlew backend/build.gradle backend/settings.gradle /src/
COPY backend/src /src/src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 mobruji && chown mobruji:mobruji /app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER mobruji
EXPOSE 8080 8081
ENV JAVA_OPTS="-Xms512m -Xmx1536m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

**`docker-compose.prod.yml`** (신규, PR B):

```yaml
services:
  mysql:
    image: mysql:8.4.6
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: mobruji
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      TZ: Asia/Seoul
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+09:00
      - --innodb-buffer-pool-size=512M
      - --bind-address=127.0.0.1
    volumes:
      - /var/lib/mobruji/mysql:/var/lib/mysql
    ports:
      - "127.0.0.1:3306:3306"

  backend:
    image: ghcr.io/goohong/mobruji-backend:${MOBRUJI_IMAGE_TAG:-latest}
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
    env_file:
      - /etc/mobruji/backend.env
    ports:
      - "127.0.0.1:8080:8080"
      - "127.0.0.1:8081:8081"

  grafana-agent:
    image: grafana/agent:latest
    restart: unless-stopped
    volumes:
      - /etc/grafana-agent/agent.yaml:/etc/agent.yaml:ro
      - /var/lib/grafana-agent:/var/lib/agent
    command: ["-config.file=/etc/agent.yaml"]
```

> 1차 운영은 **systemd 가 단일 진실** (위 compose 는 검증/롤백 경로). systemd 가 위 backend container 또는 JAR 직접 중 1개를 띄움. PR B 에서 양 옵션 동등 지원, 운영자 환경변수 `MOBRUJI_DEPLOY_MODE=systemd-jar | docker-compose` 로 선택.

### 5-5) systemd unit 골격 (PR B)

```ini
# /etc/systemd/system/mobruji-backend.service
[Unit]
Description=mobruji backend (Spring Boot)
After=network.target mysql.service
Wants=mysql.service

[Service]
Type=simple
User=mobruji
Group=mobruji
EnvironmentFile=/etc/mobruji/backend.env
ExecStart=/usr/bin/java -Xms512m -Xmx1536m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
          -jar /opt/mobruji/app.jar
Restart=on-failure
RestartSec=10
StartLimitInterval=300
StartLimitBurst=5
TimeoutStopSec=30   # graceful shutdown
KillSignal=SIGTERM
StandardOutput=append:/var/log/mobruji/backend.out.log
StandardError=append:/var/log/mobruji/backend.err.log

[Install]
WantedBy=multi-user.target
```

> `StartLimitInterval/Burst` 는 discord-daemon-hosting spec + #226 selective backfill 후속 PR (4e1d05d, feee6c9) 의 systemd 가드 패턴과 동일.

### 5-6) 무중단 배포 옵션 매트릭스 (PR F)

| 옵션 | 다운타임 | 운영 복잡도 | 비용 | 채택 시점 |
|---|---|---|---|---|
| (A) 단순 `systemctl restart` | 30~60s | 0 | 0 | **v0.3 1차** |
| (B) Blue-Green (포트 2개 + Caddy upstream swap) | 0~5s | 중 | 메모리 2배 (CX22 4GB 한계 검토) | v0.4 후보 |
| (C) Rolling (2 인스턴스 + sticky session 불요) | 0 | 중 | 인스턴스 2배 (€7.58/월) | v0.4 트래픽 ≥ 10 RPS |
| (D) Cloudflare Tunnel + ECS Fargate | 0 | 고 | 유료 | 미고려 |

- 1차 (A). PR F 에서 (B) 구현 가능성 + 메모리 측정 후 ADR 추가 (별 ADR 후보 `0016-zero-downtime-deploy.md`).
- (A) 사용 중에도 Cloudflare proxy 의 origin retry (default 3회) + Caddy graceful reload 로 사용자 체감 최소화.

### 5-7) 백업/복구 (PR G)

**백업 (매일 02:00 KST, cron)**:

```bash
# /etc/cron.d/mobruji-backup
0 17 * * * mobruji /usr/local/bin/mobruji-backup.sh   # 17:00 UTC = 02:00 KST

# /usr/local/bin/mobruji-backup.sh
#!/usr/bin/env bash
set -euo pipefail
TS=$(date -u +%Y%m%dT%H%M%SZ)
DUMP=/var/backups/mobruji/mobruji-${TS}.sql.gz
mysqldump --single-transaction --routines --triggers \
  -u mobruji -p"${MYSQL_PASSWORD}" mobruji \
  | gzip > "$DUMP"
# R2 업로드 (rclone)
rclone copy "$DUMP" r2:mobruji-backups/db/
# retention: 로컬 7일, R2 14일
find /var/backups/mobruji -name "mobruji-*.sql.gz" -mtime +7 -delete
rclone delete --min-age 14d r2:mobruji-backups/db/
# 알림: 실패 시만 Discord (성공은 무음)
```

**복구 (런북)**:

1. 새 VM 프로비저닝 (`bash tools/deploy/bootstrap-cx22.sh`).
2. `rclone copy r2:mobruji-backups/db/<latest>.sql.gz /tmp/`.
3. `gunzip -c /tmp/*.sql.gz | mysql -u mobruji -p mobruji`.
4. `systemctl start mobruji-backend`.
5. Cloudflare DNS A 레코드 갱신 (`api.mobruji.app` → 새 VM IP). TTL 1분.
6. 베리피케이션: `curl https://api.mobruji.app/actuator/health/liveness` + 추천 API smoke.

### 5-8) 환경변수 매트릭스

| 변수 | 위치 | 발급/관리 | 비고 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `/etc/mobruji/backend.env` | systemd | `prod` 고정 |
| `MOBRUJI_ADMIN_TOKEN` | `/etc/mobruji/backend.env` | 운영자 (32 char 랜덤) | observability-baseline §5-8, 부팅 fail-fast |
| `MOBRUJI_ALERT_WEBHOOK_URL` | `/etc/mobruji/backend.env` | Discord (#모부르지 채널 webhook) | observability-baseline §5-6 |
| `SPRING_DATASOURCE_URL` | `/etc/mobruji/backend.env` | `jdbc:mysql://127.0.0.1:3306/mobruji?...` | 고정 |
| `SPRING_DATASOURCE_USERNAME` | `/etc/mobruji/backend.env` | `mobruji` | 고정 |
| `SPRING_DATASOURCE_PASSWORD` | `/etc/mobruji/backend.env` | 운영자 (32 char 랜덤) | MySQL 초기 셋업 시 같은 값 |
| `MYSQL_ROOT_PASSWORD` | `/etc/mobruji/mysql.env` | 운영자 (32 char 랜덤) | systemd MySQL service |
| `MYSQL_PASSWORD` | `/etc/mobruji/mysql.env` | `SPRING_DATASOURCE_PASSWORD` 와 동기 | |
| `AUDIO_ANALYSIS_PYTHON_CMD` | `/etc/mobruji/backend.env` | `/opt/mobruji/audio-venv/bin/python` | 운영 venv |
| `AUDIO_ANALYSIS_TOOL_DIR` | `/etc/mobruji/backend.env` | `/opt/mobruji/audio-analysis` | git clone 위치 |
| `GRAFANA_CLOUD_PROM_URL` | `/etc/grafana-agent/agent.yaml` | Grafana Cloud Free 발급 | ADR-0012 |
| `GRAFANA_CLOUD_PROM_USER` | 〃 | 〃 | |
| `GRAFANA_CLOUD_PROM_PASSWORD` | 〃 | 〃 | API key |
| `NEXT_PUBLIC_API_BASE_URL` | Vercel project env | `https://api.mobruji.app` | Production / Preview 분리 |
| `CX22_SSH_HOST` | GitHub Actions secret | 운영자 | deploy workflow |
| `CX22_SSH_USER` | GitHub Actions secret | `mobruji-deploy` | deploy 전용 user |
| `CX22_SSH_KEY` | GitHub Actions secret | ed25519 키 (배포 전용) | repo scope 제한 |
| `GHCR_TOKEN` | GitHub Actions secret | GH 자동 (`GITHUB_TOKEN` 으로 충분) | |

> 평문 yml/Dockerfile 커밋 금지 (CLAUDE.md §4). `.env.example` 만 커밋, 실 `.env` 는 `.gitignore`.

### 5-9) DB 마이그레이션

- Flyway 가 Spring Boot 부팅 시 자동 적용 (현 정책 그대로, ADR-0009). 별도 pre-deploy hook 없음.
- 운영 DB 변경 PR 은 보호 영역 (`backend/src/main/resources/db/migration/**`) — `needs-human-review` 라벨 강제.
- 데이터 손실 가능 마이그레이션은 `DROP/RENAME` 전 백업 검증 별도 런북 (PR G 의 후속 챕터, v0.4 후보).

### 5-10) 프론트엔드 화면

- 본 spec 은 화면 없음.
- Vercel 셋업은 PR D 의 운영 가이드 안에 `web/.vercelignore`, `vercel.json` (필요 시), Preview/Production env 분리만 명시.

### 5-11) 보안 체크리스트 (PR 각각 회고)

- [ ] SSH 키 인증만 (`PasswordAuthentication no`)
- [ ] ufw 활성화 + Cloudflare IP allowlist
- [ ] MySQL `bind-address=127.0.0.1`
- [ ] Actuator `/prometheus` admin token + Cloudflare Access 또는 IP allowlist
- [ ] `.env` 0640, `chown root:mobruji`
- [ ] GHA secret rotation 절차 (90일, 별 PR 후보)
- [ ] HSTS preload (Cloudflare 자동)
- [ ] SCA: `actions/checkout@v4` 등 pinned major + dependabot

## 6) 작업 분할 (예상 PR 리스트)

> PR 분할은 ADR-0015 결정 머지 후 순차 진행. 각 PR 은 spec 1 section + 코드 변경 + 운영 런북 갱신을 1세트로.

- [ ] **PR A** (`docs`, 본 PR): ADR-0015 호스팅 스택 결정 + `docs/features/deployment-infrastructure.md` spec 신설 + `v03-roadmap.md` 후속 PR 슬롯 등록. 보호 영역 변경 없음.
- [ ] **PR B** (`infra`): `backend/Dockerfile` + `docker-compose.prod.yml` + `mobruji-backend.service` systemd unit + `.env.example` + `tools/deploy/bootstrap-cx22.sh` 첫 부팅 스크립트. `needs-human-review` 라벨 (Dockerfile/compose 보호 영역).
- [ ] **PR C** (`infra`): `.github/workflows/deploy-backend.yml` GitHub Actions CD + GHA secrets 가이드 + Vercel GitHub 연동 셋업 가이드 (`docs/runbooks/deploy-web-vercel.md` 신설). `needs-human-review` (workflow 보호 영역).
- [ ] **PR D** (`docs`): 도메인/DNS/SSL 셋업 런북 (`docs/runbooks/domain-and-ssl.md`) — Cloudflare Registrar 등록 절차, DNS 레코드, Cloudflare Full(strict) + Origin CA, HSTS preload. 코드 변경 없음.
- [ ] **PR E** (`infra`): Caddy reverse proxy `Caddyfile` (`tools/deploy/Caddyfile`) — `api.mobruji.app` → `127.0.0.1:8080`, `metrics.mobruji.app` → `127.0.0.1:8081` + admin token 게이트 + Cloudflare Origin CA 설치 안내.
- [ ] **PR F** (`docs` + 후속 `infra`): 무중단 배포 ADR (`docs/decisions/0016-zero-downtime-deploy.md`) — 옵션 매트릭스 (§5-6) 평가 + 1차 결정 (A) 유지 또는 (B) 채택. 채택 시 후속 구현 PR 분리.
- [ ] **PR G** (`docs` + `infra`): 백업/복구 spec 부속 런북 (`docs/runbooks/backup-and-restore.md`) + `mobruji-backup.sh` 스크립트 + `cron.d` 파일 + R2 셋업 가이드.

### PR 라벨 매트릭스

| PR | type | scope | 보호 영역 | 라벨 |
|---|---|---|---|---|
| A | docs | infra | X | `type:docs`, `scope:infra`, `ai-generated`, `ai:claude` |
| B | infra | infra | O (Dockerfile/compose) | `type:chore`, `scope:infra`, `ai-generated`, `needs-human-review` |
| C | infra | infra | O (workflow) | `type:chore`, `scope:infra`, `ai-generated`, `needs-human-review` |
| D | docs | infra | X | `type:docs`, `scope:infra`, `ai-generated` |
| E | infra | infra | X (tools/ 하위) | `type:chore`, `scope:infra`, `ai-generated` |
| F | docs | infra | X | `type:docs`, `scope:infra`, `ai-generated` |
| G | docs + infra | infra | X | `type:docs`, `scope:infra`, `ai-generated` |

## 7) 테스트 전략

- **PR A**: spec lint (`docs/features/README.md` 가드) + ADR template 준수.
- **PR B**:
  - `docker buildx build --platform linux/arm64 .` 로컬 검증.
  - `docker compose -f docker-compose.prod.yml config` syntax 검증.
  - systemd unit `systemd-analyze verify` (CI 단계 추가 검토).
  - Dockerfile multi-stage 빌드 캐시 검증 (GHA cache hit ≥ 80%).
- **PR C**:
  - workflow dry run (`act` 또는 `gh workflow run --ref test-branch`).
  - 첫 deploy 는 manual trigger (workflow_dispatch) — 자동 trigger 는 1주 안정화 후 enable.
- **PR D**: 도메인 등록 후 DNS propagation 검증 (`dig`, `curl -I https://mobruji.app`).
- **PR E**:
  - `caddy validate /etc/caddy/Caddyfile` syntax.
  - origin retry 검증 (`curl -H 'Cf-Connecting-IP: ...'` 으로 Cloudflare 우회 차단 확인).
- **PR F**: ADR + 옵션 매트릭스만. 구현 PR 은 별 spec.
- **PR G**:
  - `mysqldump | gunzip | mysql` round-trip 검증 (로컬 docker mysql 로).
  - rclone R2 credential `rclone lsd r2:` 검증.
  - 복구 런북을 staging 환경에서 1회 dry run (PR G 의 acceptance).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 도메인 1순위 `mobruji.app` 가용성 / `.kr` vs `.app` 결정 | (a) `.app` (강제 HTTPS, $14/년) / (b) `.kr` ($10/년, KR 신뢰감) / (c) `.com` (가용성 검색 필요) | @goohong / PR D 시작 전 |
| Q2 | Hetzner CX22 가 `ai-harness/04-security-policy.md` 의 데이터 거주 룰을 위반하는가 | (a) v0.3 까지는 OK (PII 없음) / (b) v0.4 계정 시스템 진입 전 KR 리전 이관 결정 ADR 필요 | @goohong / v0.4 진입 전 |
| Q3 | 1차 배포 모드 `MOBRUJI_DEPLOY_MODE` 기본값 | (a) `systemd-jar` (단순, 운영 친화) / (b) `docker-compose` (포터블) | @goohong / PR B 머지 전 |
| Q4 | Caddy vs nginx | (a) Caddy (자동 TLS + 짧은 설정) / (b) nginx (생태계 풍부) | @goohong / PR E 시작 전 — **현 spec 권장 = Caddy** |
| Q5 | Grafana Agent 를 systemd 별 unit 으로 두나, docker-compose 안에 두나 | (a) systemd (장애 격리) / (b) compose (의존성 묶음) | @goohong / PR B |
| Q6 | 무중단 배포 ADR (PR F) 1차 결정 | (a) 단순 restart 유지, v0.4 재검토 / (b) blue-green 즉시 구현 | @goohong / PR F |
| Q7 | Cloudflare IP allowlist 강제 시점 | (a) PR E 와 같이 / (b) 1주 운영 후 별 PR | @goohong / PR E 머지 시 |
| Q8 | Discord 배포 알림 채널 분리 (#모부르지-deploy) 필요? | (a) 기존 채널 통합 / (b) 분리 | @goohong / PR C 머지 후 |

## 9) 결정 로그

- **2026-05-22 (plan 36)**: 초안 작성 (status=draft). v0.3 P2 마지막 묶음 = 운영 배포 인프라. ADR-0015 (Hetzner CX22 + Vercel + Cloudflare) 와 함께 spec 신설. 7개 PR 분할 (A=본 spec, B=Dockerfile/systemd, C=GHA CD, D=도메인/SSL, E=Caddy, F=무중단 ADR, G=백업/복구). Discord daemon 별 호스트 정책 유지, multi-AZ/RUM/managed DB 모두 v0.4 후보. ADR-0014 슬롯은 추천 mood/valence ADR 용 예약 유지, hosting ADR 은 0015.
- **2026-05-22 (plan, 같은 날 재결정)**: **ADR-0015 NCP 재결정** (1차안 Hetzner CX22 → NCP maestro VM + NCP 별 VM 백/프론트 + Cloudflare 유지). 본 spec 의 §1~§9 본문은 historical context 로 유지하고, 후속 운영 결정은 §10 단일 진실로 분기. 자세한 항목은 §10 참조.
- **2026-05-23 (plan, 본 PR)**: **§10 ADR-0015 재결정 반영 + Phase 4 NCP dev 운영 경험 반영**. Phase 4 PR B-1/B-2/B-3/C/D/E 머지로 NCP dev (`docs/features/ncp-dev-deployment.md`) 가 가동 — 그 운영 데이터로 prod 토폴로지 가드 박제. §10-1 prod 토폴로지 (NCP 별 VM 채택, Vercel 보류), §10-2 Phase 4 → Phase 5 이관 시 운영 경험 (4GB tight / swap 1GB 필수 / mysql caching_sha2_password / non-root UID 1001 / healthcheck pattern / CD 자동 롤백 150s), §10-3 PR A1~A3 진행 plan (NCP 백/프론트 VM 사양 ADR 후보 / prod compose / Cloudflare DNS), §10-4 사용자 결정 묶음 Q9~Q13 5건 신설.

## 10) Phase 5 prod 진행 plan (ADR-0015 재결정 반영, 2026-05-23)

### 10-1) 결정된 토폴로지 (ADR-0015 §Decision 그대로)

```
                       ┌────────────────────────────────────────┐
                       │   Cloudflare (KR PoP)                  │
                       │   DNS + Proxy + WAF + Origin TLS       │
                       └─────┬──────────────┬───────────────────┘
                             │              │
                       mobruji.app    api.mobruji.app
                             │              │
   ┌─────────────────────────▼────┐   ┌─────▼──────────────────────────┐
   │ NCP VM #2 (frontend, TBD)   │   │ NCP VM #3 (backend + MySQL,    │
   │  • web container (Next.js)  │   │  TBD 사양 — 4GB 또는 8GB ?)    │
   │  • 또는 Vercel Hobby (옵션) │   │  • backend container (Spring)  │
   └─────────────────────────────┘   │  • mysql container             │
                                     │  • nginx (TLS term + admin     │
                                     │    token gate for actuator)    │
                                     └─────────┬──────────────────────┘
                                               │ remote_write
                                     ┌─────────▼──────────────────────┐
                                     │ Grafana Cloud Free (Prom +     │
                                     │ Grafana + Alert)               │
                                     └────────────────────────────────┘

  NCP VM #1 (maestro, 101.79.20.94, c2-g3a, 4GB) — Phase 1~4 가동 중. prod 분리.
  운영자 iPhone ─── Discord 알림 ◀── webhook (외부 API 에러율 / p95 초과)
```

핵심 변경 (Hetzner CX22 vs NCP):

| 항목 | 1차 (Hetzner) | 재결정 (NCP) | 영향 |
|---|---|---|---|
| 백엔드 호스트 | Hetzner CX22 (DE FSN1, €3.79/월) | NCP 별 VM (KR 리전, 사양 TBD) | 한국 latency 240ms → 5~15ms, 가격 TBD |
| 프론트엔드 호스트 | Vercel Hobby (무료) | NCP 별 VM **또는** Vercel Hobby | §10-4 Q9 결정 |
| ARM/x86 | CX22 = ARM64 | NCP c2-g3a 계열 = x86_64 | Dockerfile multi-arch buildx 불요, x86 단일 빌드 |
| DNS / CDN | Cloudflare | Cloudflare (1차 그대로) | 변경 없음 |
| TLS termination | Caddy on CX22 | nginx on NCP (Phase 4 dev 와 동일) **또는** Caddy | §10-4 Q10 결정 |
| maestro 동거 | 별 호스트 (macOS) | NCP maestro VM (별도) — prod VM 과 분리 | maestro 메모리 spike 격리 |
| 비용 | €3.79/월 + ₩0 | NCP 청구 (사용자 콘솔 기준, ₩TBD/월) | §10-4 Q11 |

### 10-2) Phase 4 NCP dev 운영 경험 → Phase 5 prod 가드

Phase 4 가 가동 중 (`docs/features/ncp-dev-deployment.md` + `docs/runbooks/ncp-maestro-setup.md §H`) 인 결과로 다음 가드를 prod spec 에 박는다. 모두 **PR B (Dockerfile + compose) 작성 시 그대로 답습**.

#### 메모리 운영 (4GB → prod 사양 결정 입력)

- Phase 4 dev 1 VM (4GB) 메모리 매트릭스 (실측 컨테이너 limit 합산):
  - mysql 512M + backend 600M + web 256M + nginx 64M = **1.43GB** (containers)
  - maestro/Claude TUI ≈ 300MB + sub-agent spike 1GB + Discord bot 150MB + OS 800MB = **2.25GB** (host side)
  - **합계 ≈ 3.7GB**, swap 1GB 보강 (현재 활성). spike 시 swap 침범 관찰됨.
- prod 가 maestro VM 과 분리되므로 host overhead ≈ 800MB (OS + monitoring agent) 로 축소 → 4GB VM 도 충분 가능.
- 단 **mysql innodb_buffer_pool_size** 를 dev 의 128M → prod 256M 또는 512M 로 키울 가능성 있음 → 메모리 매트릭스 재산정 필요. §10-4 Q11 입력.

#### MySQL 8.4 `caching_sha2_password` 호환 (#379)

- MySQL 8.4 default 인증 플러그인 = `caching_sha2_password`. JDBC 가 SSL 없이 접속할 때 public key 교환을 요구 → 첫 부팅 fail.
- Phase 4 fix (`docker-compose.dev.yml` `SPRING_DATASOURCE_URL`):
  - `jdbc:mysql://mysql:3306/${DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul`
- prod 결정: (a) **container 내부 network 라서 prod 도 `allowPublicKeyRetrieval=true` 유지** (default) — dev/prod 동일 보안 모델. 또는 (b) prod 는 SSL 활성화 → `allowPublicKeyRetrieval` 불요. §10-4 Q12 결정.

#### 비-root UID 1001 컨벤션 (#379)

- `eclipse-temurin:21-jre-noble` base image 가 ubuntu user 로 UID 1000 점유 → `useradd --uid 1000` 충돌.
- Phase 4 결정: backend/web 모두 **UID 1001** 사용 (`backend/Dockerfile`, `web/Dockerfile` 동일). prod Dockerfile 도 이 컨벤션 그대로.

#### Healthcheck 패턴 (Phase 4 검증됨)

- backend: Spring Boot `management.server.port=8081` 별 port + `/actuator/health/liveness`. Dockerfile HEALTHCHECK `--interval=15s --timeout=5s --start-period=60s --retries=4`. prod 그대로.
- nginx: `/_nginx_health` endpoint (compose `healthcheck` 절). prod 도 같은 패턴.
- mysql: `mysqladmin ping`. prod 그대로.

#### CD 자동 롤백 150s window (PR C 검증됨)

- Phase 4 `.github/workflows/cd-dev.yml` 가 `30회 × 5s polling = 150s window` 로 healthcheck → timeout 시 이전 SHA 로 자동 reset + 재빌드.
- prod 도 동일 패턴 (별 workflow `cd-prod.yml`). 단 prod 는 `develop` 머지가 아닌 **`main` 머지 또는 manual `workflow_dispatch`** trigger — `release` 흐름 (CLAUDE.md §8 develop→main Merge commit) 과 정합.

#### swap 1GB 필수 (§B-5 / §H-1 그대로)

- Phase 4 `tools/deploy/ncp-bootstrap-dev.sh` 가 swap 1GB 자동 활성화 + `/etc/fstab` 등록. prod bootstrap 스크립트 (`tools/deploy/ncp-bootstrap-prod.sh`) 신설 시 그대로 답습.

### 10-3) Phase 5 PR 진행 plan (ADR-0015 재결정 반영 후 새 PR 슬롯)

§6 의 PR B~G 는 **Hetzner CX22 가정으로 작성**돼 있어 prod = NCP 로 갈 경우 일부 재작성 필요. NCP 재결정 반영 작업을 **PR A1~A3** 로 §6 표 앞에 추가:

- [ ] **PR A1 (docs, plan)**: ADR 신설 — `docs/decisions/0017-ncp-prod-vm-sizing.md` 또는 ADR-0015 §10 확장. 백엔드 prod VM 사양 (4GB vs 8GB 결정), 프론트 호스트 (NCP VM #2 vs Vercel Hobby), 도메인 1순위 (.app vs .kr vs .com). 결정 입력은 §10-4 Q9~Q13.
- [ ] **PR A2 (docs, plan)**: 본 spec §1~§9 의 "Hetzner CX22" / "Caddy" / "ARM64" 키워드를 **인용 문맥** 으로 명시 (NCP 1차 채택 + caddy → nginx 변경 등). §10-1 표가 단일 진실인 점 박제. §6 PR B~G 항목명 갱신 (Dockerfile = ARM64 → x86, reverse proxy = Caddy → nginx 등).
- [ ] **PR A3 (docs, plan + infra)**: Phase 5 부트스트랩 스크립트 outline — `tools/deploy/ncp-bootstrap-prod.sh` (멱등, swap, docker 설치, .env.prod template, systemd 또는 compose 선택). Phase 4 의 `ncp-bootstrap-dev.sh` 와 1:1 대응. 보호 영역 없음 (스크립트 outline 만, 실제 스크립트는 PR B-prod).
- [ ] (PR A1 머지 후) **PR B-prod (infra)**: `docker-compose.prod.yml` + `tools/deploy/ncp-bootstrap-prod.sh` + `.env.prod.example` + nginx prod conf. Phase 4 compose 와 차이점: (1) volumes 위치 (`/var/lib/mobruji/mysql`), (2) MySQL `innodb-buffer-pool-size` 상향, (3) backend `MOBRUJI_CORS_ALLOWED_ORIGINS` = prod 도메인, (4) GIT_SHA tag = main merge SHA. 보호 영역 → `needs-human-review`.
- [ ] (PR A1 머지 후) **PR C-prod (infra)**: `.github/workflows/cd-prod.yml` — main 머지 또는 manual trigger → NCP prod VM SSH → docker compose up. 자동 롤백 150s. 보호 영역 → `needs-human-review`.
- [ ] (병행) **PR D (docs, 기존 §6 그대로)**: 도메인/DNS/SSL 런북 — Cloudflare Registrar 등록 절차. ADR-0015 NCP 재결정 후에도 Cloudflare 부분은 영향 없음.
- [ ] (병행) **PR E (infra)**: nginx prod conf (Caddy 변경) + Cloudflare Origin CA 설치 안내. Phase 4 nginx conf 와 80% 공통.
- [ ] (병행) **PR F (docs)**: 무중단 배포 ADR — Phase 5 1차는 §5-6 표 (A) `docker compose up -d --no-deps backend` (30~60s 다운) 유지.
- [ ] (병행) **PR G (docs + infra)**: 백업/복구 런북. NCP Object Storage (S3 호환) 또는 R2 선택은 §10-4 Q13.

### 10-4) 사용자 결정 묶음 (Q9~Q13)

본 spec 머지 후 사용자가 결정해 줘야 PR A1 ADR 본문을 채울 수 있다.

| # | 질문 | 선택지 | 영향 |
|---|---|---|---|
| Q9 | 프론트엔드 prod 호스트 | (a) NCP 별 VM #2 (백/프론트 같은 사업자 통합) / (b) Vercel Hobby (Next.js SSR 최적 + 무료) / (c) NCP 백엔드 VM 안에 web container 동거 (Phase 4 dev 와 동일) | PR A1 ADR + PR B-prod compose |
| Q10 | TLS termination 도구 | (a) **nginx** (Phase 4 dev 그대로 답습, 운영 친화) / (b) Caddy (자동 TLS, §6 PR E 1차안) | PR E |
| Q11 | NCP 백엔드 VM 사양 | (a) c2-g3a 4GB (maestro VM 과 동일, 비용 최저, 메모리 spike 위험) / (b) c2-g3a-h 8GB (innodb buffer pool 확보, prod 안정) / (c) 별 NCP 라인업 (사용자 콘솔 가시성) | PR A1 ADR + 월 비용 |
| Q12 | MySQL prod 인증/TLS | (a) `caching_sha2_password` + `allowPublicKeyRetrieval=true` (dev 그대로) / (b) container 간 SSL 활성화 (`require_secure_transport`) / (c) `mysql_native_password` 강제 | PR B-prod compose |
| Q13 | 백업 저장소 (PR G) | (a) Cloudflare R2 (€0/월 < 10GB, 1차안) / (b) NCP Object Storage (한국 리전, 사용자 청구 통합) / (c) 별 사업자 (Wasabi 등) | PR G + 월 비용 |

각 Q 의 default 추천 (plan 의견):
- Q9: **(c) 한 VM 안에 web container 동거** — Phase 4 dev 가 이미 검증된 패턴 + 별 VM 운영 부담 0. 단 backend latency 가 web SSR fetch 으로 늘면 (b) Vercel 으로 분리.
- Q10: **(a) nginx** — Phase 4 운영 경험 그대로 답습. Caddy 자동 TLS 장점은 Cloudflare Origin CA (15년) 가 흡수.
- Q11: **(b) 8GB** — innodb buffer pool 256M~512M + JVM heap 1GB + web 256M + nginx 64M + swap 의존 최소화. 4GB 는 maestro VM 처럼 host overhead 압축이 가능한 경우만.
- Q12: **(a) `allowPublicKeyRetrieval=true` 유지** — container internal network. SSL 도입은 v0.4 multi-VM 시.
- Q13: **(b) NCP Object Storage** — 사용자 결제수단 검증 완료 (ADR-0015 §Context 1) + 한국 리전 latency. R2 가 무료 quota 더 크지만 결제 사업자 분산은 ADR-0015 회피 동인.
