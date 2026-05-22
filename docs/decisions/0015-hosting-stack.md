---
id: 0015
title: 운영 호스팅 스택 — Hetzner CX22 단일 VM + Cloudflare 프록시 + Vercel(web)
status: accepted
date: 2026-05-22
deciders: [@goohong]
---

# 0015. 운영 호스팅 스택 — Hetzner CX22 단일 VM + Cloudflare 프록시 + Vercel(web)

## Context

v0.3 P2 마지막 묶음에서 **사용자 가시 운영 환경**을 띄울 준비를 한다. 현재는 로컬 docker-compose(MySQL) + `./gradlew bootRun` + `npm run dev` 만 가능하며 외부 접근이 불가능하다. v0.4(#243 사용자 계정 시스템) 진입 전에 다음을 끝낸다:

- Spring Boot 백엔드 + MySQL + Python audio analysis runner 를 묶어 단일 호스트에서 24/7 가동.
- Next.js 프론트엔드를 SSR 가능한 환경으로 배포.
- 도메인 + HTTPS + 기본 DDoS/CDN 방어.
- 운영자 1인이 SSH 한 번으로 점검/롤백 가능한 운영 단순성.

제약:

- **예산 ≤ 월 €5 / $5** 가정. 사용자 트래픽 미미 (자가 사용 + 베타 테스터 수 명).
- **신용카드 거부 이력 (OR-CBAT-23)** — Oracle Always Free, GCP, AWS 카드 인증 통과 사례 일관성 없음. Hetzner / Vercel / Cloudflare 는 통과 확인됨 (discord-daemon-hosting spec §5-2 와 같은 사용자 결제수단).
- **한국 사용자 대상** → 한국 리전 또는 ≤ 200ms RTT 가 가능한 위치.
- **JVM + Node + MySQL 동거** → ≥ 2GB RAM 필요. 1GB RAM 머신(e2-micro, Oracle A1 1c1g)은 JVM 만으로도 swap 폭주.
- **운영 부담 0** 가정 (1인 운영). managed 서비스를 쓸 수 있으면 쓰되, 예산 초과면 self-host.

ADR-0012 (관측성 = Grafana Cloud Free), ADR-0013 (sessionId TTL = sliding 180d), discord-daemon-hosting spec(이미 GCP 단념 → macOS LaunchAgent + Cloudflare Workers 병행) 와 정합성을 맞춰야 한다.

## Decision

**Backend + MySQL + audio runner = Hetzner CX22 단일 VM (ARM64, 2vCPU/4GB/40GB, €3.79/월, FSN1/Falkenstein DE 리전), Frontend = Vercel Hobby (무료), 도메인 = Cloudflare Registrar + Cloudflare Proxy** 를 v0.3 운영 1차 스택으로 채택한다.

### 채택 항목

1. **Backend host**: Hetzner Cloud **CX22** (ARM64, 2vCPU, 4GB RAM, 40GB NVMe, 20TB egress, €3.79/월). 리전 **FSN1 (Falkenstein, 독일)**.
   - 한국 ↔ DE RTT ≈ 240ms. Cloudflare proxy (KR PoP 종단) 가 정적 자원/HTTPS 핸드셰이크 흡수 → 사용자 체감 first byte ≤ 350ms 목표.
   - 한국 리전(`fly.io NRT`, `Vultr Seoul`) 는 €5 초과 + 신용카드 이슈 → v0.4 트래픽 증가 시 재검토.
2. **Backend 배포 단위**: **systemd unit (JAR 직접 실행)**. Docker compose 도 같이 제공하되 1차는 systemd 가 단일 진실 (discord-daemon-hosting spec 의 systemd 운영 노하우 그대로 재사용).
3. **MySQL**: **같은 VM 안 self-host (apt mysql-server-8.4)**. managed DB(PlanetScale free 폐지, DO managed $15/월) 모두 부적합. 데이터 사이즈 ≤ 1GB (v0.3 시드 100곡 + 세션 데이터). 백업은 mysqldump + Cloudflare R2 (10GB 무료) cron.
4. **Frontend host**: **Vercel Hobby (무료)**. Next.js App Router + SSR + edge cache 즉시. 백엔드 origin 은 `api.mobruji.<tld>` (Cloudflare proxy → CX22).
5. **도메인 + DNS + CDN/WAF**: **Cloudflare** 1사 통합. Registrar (TLD 원가) + DNS + Proxy + Origin TLS (Let's Encrypt or Cloudflare Origin CA). 도메인은 v0.3 P2 후속 PR (`docs/features/deployment-infrastructure.md` PR D) 에서 등록 — 1순위 `mobruji.app` (.app 강제 HTTPS), 2순위 `mobruji.kr`.
6. **SSL**: Cloudflare Full(strict) + Origin CA 15년 인증서. Let's Encrypt 는 백업 옵션 (Cloudflare 일시 장애 시).
7. **CI/CD**:
   - **PR → develop merge**: GitHub Actions 가 `bootJar` 빌드 → GHCR 이미지 push → CX22 SSH `systemctl restart mobruji-backend` (1차는 단순 restart, 무중단은 PR F 로 별도).
   - **main merge (release)**: 동일 흐름 + git tag + Discord 알림.
   - **Vercel 은 GitHub 연동**으로 자동 배포 (web 폴더만 trigger).
8. **관측성**: ADR-0012 그대로 (Grafana Cloud Free remote_write). CX22 에 Grafana Agent 1개 추가.
9. **Discord daemon 동거 여부**: **별도 호스트 유지** (discord-daemon-hosting spec 의 macOS LaunchAgent + Cloudflare Workers 병행 그대로). 운영 VM 에 같이 띄우지 않는다 — discord-daemon 은 GitHub repository_dispatch 만 호출하므로 backend host 와 결합 무의미, 장애 격리 우선.

### 환경 변수 / 시크릿 관리

- Backend `.env` 는 `/etc/mobruji/backend.env` (root:mobruji, 0640) — systemd unit 의 `EnvironmentFile=` 로 주입.
- 필수: `SPRING_PROFILES_ACTIVE=prod`, `MOBRUJI_ADMIN_TOKEN`, `MOBRUJI_ALERT_WEBHOOK_URL`, `SPRING_DATASOURCE_*`, `SPRING_DATASOURCE_PASSWORD`, `GRAFANA_CLOUD_*` (Agent 가 별도 파일 사용).
- GitHub Actions secrets: `CX22_SSH_HOST`, `CX22_SSH_USER`, `CX22_SSH_KEY`, `GHCR_TOKEN`.
- 평문 yml/Dockerfile 커밋 금지 — CLAUDE.md §4 보안 룰.

## Consequences

### 긍정적

- **예산 ≤ €4/월** (CX22 €3.79 + Cloudflare Registrar 도메인 원가). Vercel Hobby/Cloudflare proxy/Let's Encrypt 모두 무료.
- **운영 단순**: 단일 VM + systemd. 백업/롤백/로그/메트릭 모두 1대에서 처리. ADR-0012 의 단일 인스턴스 가정 그대로.
- **카드 인증 우회**: Hetzner / Vercel / Cloudflare 모두 사용자 카드 통과 확인.
- **장애 격리**: web (Vercel CDN edge) + backend (CX22) + discord daemon (별 호스트) 분리 → 한 영역 장애가 다른 영역으로 전파되지 않음.
- **이관 비용 낮음**: Docker 이미지 + systemd unit 표준 → fly.io NRT / Vultr Seoul / GCP KR 로 옮길 때 IaC 재작성 0.

### 부정적

- **DE 리전 latency**: 한국 ↔ FSN1 RTT ≈ 240ms. API 호출 first byte 350~400ms (Cloudflare proxy 흡수 후). v0.4 트래픽 ≥ 10 RPS 가 되면 NRT/Seoul 이관 필요 — 별도 ADR.
- **단일 VM SPOF**: VM 장애 = 전체 서비스 다운. v0.3 SLO 정의 없음 (`observability-baseline §4 제외`) 하에 허용. v0.4 multi-AZ 결정 시 별도 ADR.
- **MySQL 동거 메모리 압박**: JVM (Xmx=1.5GB) + MySQL (innodb_buffer_pool=512MB) + Node(Vercel 외주) + OS = 약 2.5GB. CX22 4GB 한도 안. swap 1GB 활성화 (운영 중 사용량 ≤ 10% 목표).
- **무중단 배포 X (1차)**: `systemctl restart` 30~60초 다운타임. graceful shutdown + health check + Cloudflare Origin retry 로 사용자 체감 최소화. blue-green/rolling 은 PR F (deployment-infrastructure spec §5-5).
- **Vercel SSR 콜드스타트**: Next.js App Router edge 함수 첫 호출 200~400ms. PWA 캐시(이미 도입) 가 흡수.
- **백업 외부 저장소 의존**: Cloudflare R2 10GB 무료 한도 안에서 운영. 초과 시 Backblaze B2 (€0.005/GB/월) 이관.

## Alternatives (considered)

- **(A) Oracle Cloud Always Free (A1 ARM 4c24g 또는 e2-micro)** — 영구 무료 + 한국 리전(ICN) 가능. **거절 사유: 사용자 신용카드 인증 차단 (discord-daemon-hosting spec §1 동일)**. 가입 자체 불가.
- **(B) GCP Free Tier (e2-micro 1c1g + Cloud SQL db-f1-micro)** — 영구 무료. 거절: 1GB RAM JVM 부적합 + Cloud SQL 무료 한도 모호 + 카드 이슈.
- **(C) AWS Lightsail $3.5/월 (512MB)** — 한국 리전(ICN) 가능. 거절: 512MB JVM 불가, $5 (1GB) 도 부족, IPv6 만 무료 등 함정.
- **(D) AWS Lightsail $10/월 (2GB)** — 한국 리전 + 충분한 RAM. 거절: 예산 2배 + 1년 후 인상.
- **(E) fly.io Hobby (shared-cpu-1x 256MB × 3 무료)** — 한국 리전(NRT) 가능, 무료. 거절: 무료 인스턴스 256MB 불가, scale-up 시 카드 + $5 초과.
- **(F) Render Free (web service)** — 자동 sleep 15분, cold start 30초. 거절: voice-range/추천 API latency 치명.
- **(G) Railway Trial $5 credit/월** — credit 소진 후 sleep. 거절: 운영 부담 (매월 결제 갱신 + credit 모니터링).
- **(H) DigitalOcean Droplet $4/월 (512MB) / $6/월 (1GB)** — 한국 리전(SGP 가까움) 가능. 거절: $4 RAM 부족, $6 예산 초과 + 한국 리전 없음.
- **(I) Hetzner CX22 (Falkenstein DE)** — **채택**. €3.79/월, 4GB RAM, ARM64. 사용자 카드 통과 확인. 한국 리전 없음이 유일 단점.
- **(J) Hetzner CAX11 (Helsinki ARM €3.29)** — 더 싸지만 1c2g RAM 부족.
- **(K) Self-host on home Mac + Cloudflare Tunnel** — 비용 0. 거절: 사용자 Mac sleep 불확정 (discord-daemon-hosting spec §5-3 A 옵션과 동일 문제) + 가정 회선 업로드/SLA.
- **(L) Frontend = same VM (PM2)** — 비용 절약 0 (Vercel Hobby 무료) + Vercel CDN 손실. 거절.
- **(M) Frontend = Cloudflare Pages** — Vercel 대안. **2순위로 보류** — Next.js SSR 호환은 OK 지만 Vercel 이 Next.js 1차 시민. v0.4 multi-region 결정 시 재검토.
- **(N) DB = PlanetScale Free** — 무료 한도 종료 (2024). 거절.
- **(O) DB = DigitalOcean Managed MySQL $15/월** — 운영 부담 0 이지만 예산 4배. 거절. v0.4 사용자 데이터 ≥ 10GB 시 재검토.
- **(P) DB = Neon Postgres Free** — Postgres 전환 비용 (Flyway 마이그레이션 V1~V5 재작성 + JPA dialect + 테스트 H2→PG). 거절.

## References

- Feature Spec: `docs/features/deployment-infrastructure.md` (v0.3 P2 마지막 묶음, 본 ADR 의 PR 분할 + 운영 절차)
- 정합성: ADR-0012 (관측성 = Grafana Cloud Free, 단일 인스턴스 가정), ADR-0013 (sessionId TTL — multi-region 결정 시 영향), `docs/features/discord-daemon-hosting.md` (Discord 데몬 별도 호스트)
- 무관: ADR-0014 슬롯은 추천 mood/valence ADR 용으로 예약 (v03-roadmap.md plan 33 결정 로그). 본 ADR 은 다음 번호 0015.
- 관련 이슈: #242 (관측성 베이스라인), #243 (v0.4 계정 시스템 — 본 ADR 가 선행 인프라).
- 보안 룰: `docs/ai-harness/04-security-policy.md`, CLAUDE.md §4.
- 후속 ADR 후보: NRT/Seoul 리전 이관 ADR, multi-AZ HA ADR, managed DB 전환 ADR (모두 v0.4 트래픽/SLO 결정 후).
