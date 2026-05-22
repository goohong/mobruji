---
id: 0015
title: 운영 호스팅 스택 — NCP maestro 전용 VM(c2-g3a) + NCP 별 VM(백/프론트 기생)
status: accepted
date: 2026-05-22
deciders: [@goohong]
revised: 2026-05-22
---

# 0015. 운영 호스팅 스택 — NCP maestro 전용 VM(c2-g3a) + NCP 별 VM(백/프론트 기생)

> 본 ADR은 2026-05-22 1차 결정(Hetzner CX22 + Vercel + Cloudflare)을 **같은 날 동일 ADR 번호로 재결정**한다. 1차 결정은 발효 전이므로 ADR-0015 슬롯을 그대로 유지하고 본문을 재작성한다. 과거 본문은 git history에 보존(`git log -p docs/decisions/0015-hosting-stack.md`).

## Context

v0.3 P2 마지막 묶음에서 **사용자 가시 운영 환경**을 띄울 준비를 마치고, 동시에 [`discord-driven-mobruji`](../features/discord-driven-mobruji.md) spec에서 결정된 **maestro(Claude Code interactive) 24/7 가동 호스트**를 함께 결정해야 한다. 1차 결정(Hetzner CX22 + Vercel + Cloudflare) 직후 다음 변화가 발생했다:

1. **사용자 결제수단 재검증**: NCP(Naver Cloud Platform)는 사용자 카드 호환성이 이미 검증되어 있다(사용자가 NCP에 `ppiyaki`, `routie` 서비스를 운영 중). 1차 ADR이 가정했던 "Hetzner/Vercel/Cloudflare만 통과"는 보수적 추정이었고, 한국 발급 카드 호환성이 더 확실한 NCP가 우선순위가 더 높다.
2. **GCP OR-CBAT-23 정산 묶임**: GCP 결제 거부 + 선결제 ₩45k가 환불 지연. 한국계 결제수단을 일관 사용해 회피 동인.
3. **maestro 호스트 동시 결정 요구**: discord-driven-mobruji spec(#338) Phase 1 진입 직전. maestro를 사용자 Mac LaunchAgent로 두는 1차안은 sleep/회선 불확정성이 남아있음. NCP에 별도 c2-g3a VM을 띄우면 maestro/백엔드/프론트 모두 한 사업자(NCP)에서 처리 가능 → 인프라 통합.
4. **한국 사용자 latency**: 한국 ↔ DE FSN1 RTT 240ms 가 한국 ↔ NCP KR1/KR2 RTT 5~15ms로 단축. Cloudflare proxy 흡수 가정이 사라져도 first byte ≤ 100ms 달성 여유.
5. **NCP VM 이미 발급 완료**: 사용자가 c2-g3a(High CPU 2vCPU/4GB, Ubuntu 24.04, 10GB SSD) + 공인 IP `101.79.20.94` + SSH 키 기반 접근까지 확보. 1차 결정 ADR 잉크가 마르기 전 셋업 가능 상태.

ADR-0012(관측성 = Grafana Cloud Free), ADR-0013(sessionId TTL = sliding 180d), discord-daemon-hosting spec(macOS LaunchAgent + Cloudflare Workers 병행)와의 정합성은 본 재결정에서 다음과 같이 정리한다:

- discord-daemon은 NCP maestro VM과 동거(같은 VM의 별 systemd unit) — maestro와 같은 호스트에 있어야 tmux bridge가 자연스러움.
- Grafana Cloud Free remote_write는 maestro VM + 백/프론트 VM 모두에서 발사(인스턴스 라벨로 구분).

## Decision

**maestro(Claude Code interactive) = NCP c2-g3a 전용 VM(공인 IP 101.79.20.94, Ubuntu 24.04, 2vCPU/4GB/10GB), Backend + MySQL + audio runner = NCP 별 VM(기존 사용자 NCP 자원에 기생, 사양/리전 TBD by maestro spec 외), Frontend = NCP 같은 별 VM 또는 Vercel Hobby(추후 결정), 도메인/CDN = Cloudflare(1차안 유지)** 를 v0.3 운영 1차 스택으로 채택한다.

본 ADR scope는 **maestro VM**에 한정하며, 백/프론트 VM 사양/리전/배포 토폴로지는 후속 ADR 또는 spec에서 다룬다.

### 채택 항목 (maestro VM 한정)

1. **maestro host**: **NCP c2-g3a** (High CPU, 2vCPU/4GB RAM/10GB SSD), Ubuntu 24.04, 공인 IP `101.79.20.94`, SSH 키 기반(사용자 보유, `~/workspace/secret/<keyname>`). NCP KR1 또는 KR2 리전(사용자 발급 시 선택).
2. **maestro 실행 단위**: **systemd unit (tmux 세션 부트스트랩 → 그 안에서 `claude` 실행)**. Phase 1은 수동 `tmux attach`로 검증, Phase 2부터 systemd unit으로 자동 복구. maestro/Discord bridge는 책임 분리(`mobruji-maestro.service` + `mobruji-discord-bridge.service`).
3. **Anthropic API 인증**: **`ANTHROPIC_API_KEY` 환경변수** (`~/.bashrc` 또는 systemd unit `Environment=`). OAuth(`claude login`)는 headless 부적합. API key는 `console.anthropic.com` → API Keys에서 `mobruji-maestro` 라벨로 발급. 사용량은 Anthropic console에서 모니터링.
4. **Discord daemon 동거**: maestro와 **같은 VM의 별 systemd unit**(`tools/discord-daemon/bot.py` 확장본). tmux bridge가 같은 호스트의 tmux 세션을 `send-keys` 해야 하므로 동거 강제. 1차 ADR이 가정했던 "별 호스트(macOS LaunchAgent)"는 폐기 — maestro를 NCP로 옮기면서 daemon도 NCP로 통합.
5. **워크트리 5개 동시 사용**: `~/mobruji` + `~/mobruji-be` + `~/mobruji-fe` + `~/mobruji-rev` + `~/mobruji-plan` — 기존 멀티 세션 런북(`docs/ai-harness/11-multi-session-runbook.md`) 그대로 답습.
6. **메모리 운영**: c2-g3a 4GB는 maestro(Node + Claude TUI ≈ 300MB) + sub-agent 동시 spike(최대 3개, 약 1GB) + tmux + Discord bot(Python venv ≈ 150MB) + OS = 약 2GB 상시. 여유 2GB. swap 1GB 활성화(spike 대비). 향후 sub-agent 동시 5개 또는 maestro transcript 무거워지면 swap 증설 또는 c2-g3a → c2-g3a 상위 사양으로 vertical scale.
7. **시크릿 관리**: `ANTHROPIC_API_KEY`, `DISCORD_BOT_TOKEN`, `GITHUB_PAT`는 `mobruji` 유저 권한 0600 파일(`~/.bashrc` 또는 `/etc/mobruji/maestro.env`) — 평문 yml 금지(CLAUDE.md §4).
8. **방화벽 (ACG)**: SSH 22 inbound만 허용, 나머지 outbound 전체 허용. Discord bot은 outbound WebSocket. GitHub API/Anthropic API도 outbound only. maestro VM에 inbound HTTP/HTTPS 노출 불필요(백/프론트 VM이 별도).
9. **백/프론트 VM**: **별도 spec에서 결정**. NCP 다른 사용자 VM에 기생 가능성 검토 중. 본 ADR scope 외.

### 환경 변수 / 시크릿 관리 (maestro VM)

- `mobruji` 유저 `~/.bashrc` 또는 `/etc/mobruji/maestro.env` (0600):
  - `ANTHROPIC_API_KEY` (필수)
  - `DISCORD_BOT_TOKEN` (필수, Discord bridge 가동 시)
  - `GITHUB_PAT` (필수, gh CLI 인증 대체 또는 fallback)
- `tools/discord-daemon/.env` (0600): 기존 변수 그대로(`DISCORD_BOT_TOKEN`, `ALLOWED_USER_IDS`, `MOBRUJI_CHANNEL_ID`, `GITHUB_PAT`, `GITHUB_REPO` + 신규 `TMUX_BRIDGE_ENABLED`, `TMUX_SESSION_NAME` 등 — discord-driven-mobruji spec §5-4 참고).
- root SSH 비밀번호 인증 비활성(이미 키 인증), `mobruji` 유저에 sudo 부여(셋업/유지보수용), 일상 작업은 `mobruji` 유저로.

## Consequences

### 긍정적

- **사용자 카드 호환성 검증 완료**: NCP는 사용자가 이미 운영 중인 사업자. 결제 거부 위험 0. GCP OR-CBAT-23 + 선결제 묶임 회피.
- **한국 latency**: 한국 ↔ NCP KR RTT 5~15ms. maestro ↔ Discord WebSocket / GitHub API 응답성 ↑. 백/프론트도 KR 리전 → 사용자 first byte ≤ 100ms.
- **인프라 통합**: maestro/백/프론트 한 사업자(NCP) → 콘솔 1개, 결제 1건, 모니터링 1곳. 사용자 운영 부담 ↓.
- **maestro 호스트 sleep 위험 제거**: 1차안(macOS LaunchAgent)의 sleep/회선 불확정성 없음. VM 24/7 가동 + systemd auto-restart.
- **Discord daemon 동거 자연성**: maestro와 같은 호스트 → tmux send-keys 자연. 1차 ADR의 "별 호스트" 가정(repository_dispatch 우회)이 사라지면서 latency P95 < 2s(spec §3) 여유.
- **이관 비용 낮음**: Ubuntu + systemd + tmux 표준. 향후 다른 KR 사업자(NHN Cloud / KT Cloud) 또는 자체 IDC 이관 시 IaC 재작성 0.

### 부정적

- **단일 VM SPOF**: VM 장애 = maestro 다운. 사용자가 Discord로 maestro와 소통 불가. v0.3 SLO 정의 없음(`observability-baseline §4 제외`) 하에 허용. multi-AZ는 v0.4 별도 ADR.
- **NCP 비용 ≈ Hetzner CX22보다 높을 가능성**: c2-g3a 한국 리전 가격이 Hetzner DE €3.79/월보다 비쌀 수 있음. 정확한 청구액은 사용자 NCP 콘솔에서 확인. 예산 한도(월 ₩10k 가정)는 사용자 재량.
- **메모리 4GB 한계**: maestro + sub-agent 동시 spike 시 swap 의존. swap 활성화 + 모니터링 필수. 한계 도달 시 vertical scale 또는 sub-agent 동시 수 제한(현재 3개) 유지.
- **API key 평문 노출 위험**: `ANTHROPIC_API_KEY`를 `~/.bashrc`에 두면 셸 히스토리 leak 가능. systemd unit `Environment=` 이전 권장(Phase 2). Phase 1은 chmod 600 + ALLOWED_USER_IDS 게이트로 완화.
- **Discord daemon 동거 = 장애 결합**: maestro VM 장애 = Discord daemon도 다운. 사용자가 "maestro 죽었다"를 Discord로 알 수 없음. 보조 채널(SMS / 이메일 alert)은 v0.4 ADR.
- **OAuth maestro 불가**: `claude login`(OAuth) headless 미지원 → API key 발급 + 결제 등록 필수. Anthropic 무료 한도 초과 시 비용 발생(사용자 부담).

## Alternatives (considered)

### maestro host 선택지

- **(A) 1차안 — Hetzner CX22 (DE FSN1) + maestro는 macOS LaunchAgent** — 폐기. maestro 분산 운영 복잡 + macOS sleep 불확정 + 한국 ↔ DE latency. NCP 통합이 더 단순.
- **(B) Oracle Cloud Always Free (A1 ARM 4c24g, ICN)** — 영구 무료 + 한국 리전. 거절: 사용자 카드 인증 차단 이력(OR-CBAT-23 정황). NCP가 검증된 대체.
- **(C) GCP e2-small ($13/월, ICN)** — 한국 리전 + 충분한 RAM. 거절: 카드 거부 + ₩45k 묶임 사례 + 비용 NCP 대비 우위 없음.
- **(D) AWS Lightsail $10/월 (2GB, ICN)** — 한국 리전 + 충분한 RAM. 거절: 카드 호환 검증 안 됨 + 1년 후 가격 인상.
- **(E) NCP Micro Server (1vCPU/1GB)** — 더 저렴. 거절: maestro + sub-agent + Discord bot 동거 1GB 부족.
- **(F) NCP c2-g3a (2vCPU/4GB)** — **채택**. 사용자 카드 검증 + 한국 리전 + maestro + Discord daemon + 약간의 여유 동시 수용.
- **(G) NCP c2-g3a 상위 사양 (4vCPU/8GB)** — 메모리 여유 ↑. 거절: 현 sub-agent 동시 3개 수준에서 과대 사양. 필요 시 vertical scale.
- **(H) 사용자 Mac + Cloudflare Tunnel** — 비용 0. 거절: sleep / 회선 불확정 / 1차 ADR 폐기 사유 동일.
- **(I) maestro + 백/프론트 같은 VM (c2-g3a) 동거** — 비용 ↓. 거절: maestro 메모리 spike와 JVM/MySQL 동거 시 OOM. 책임 분리 + 장애 격리 우선.

### maestro 실행 단위 선택지

- **(P) tmux 안 직접 `claude` 실행 + 수동 attach** (Phase 1) — **채택**. 셋업 즉시 검증 가능.
- **(Q) systemd unit으로 tmux 부트스트랩** (Phase 2) — **채택 (Phase 2)**. 재부팅 자동 복구 + 무인 운영.
- **(R) Docker container 안 claude** — 거절: tmux send-keys 외부에서 컨테이너로 주입 복잡. Phase 3+.
- **(S) SDK headless (Anthropic SDK 직접 호출)** — 거절: 컨텍스트 누적/세션 영속화 자체 구현 부담. discord-driven-mobruji spec Phase 3+로 보류.

### Discord daemon 위치 선택지

- **(α) maestro VM 동거 (같은 호스트의 별 systemd unit)** — **채택**. tmux send-keys 자연 + 책임 분리.
- **(β) 사용자 Mac LaunchAgent (1차 ADR + discord-daemon-hosting spec 원안)** — 폐기. maestro를 NCP로 옮기면서 daemon도 같이 이동.
- **(γ) 별 NCP VM (Discord daemon 단독)** — 거절: 비용 + tmux send-keys 원격 호출 복잡(SSH wrapping).

## References

- Feature Spec: [`docs/features/discord-driven-mobruji.md`](../features/discord-driven-mobruji.md) (maestro 24/7 가동 + tmux bridge), [`docs/features/deployment-infrastructure.md`](../features/deployment-infrastructure.md) (백/프론트 배포 묶음, 본 ADR과 별도 진행), [`docs/features/discord-daemon-hosting.md`](../features/discord-daemon-hosting.md) (Discord daemon 호스트 — 본 ADR에서 NCP 동거로 갱신).
- 런북: [`docs/runbooks/ncp-maestro-setup.md`](../runbooks/ncp-maestro-setup.md) — **본 ADR의 셋업 절차** (Phase 1~3 + 트러블슈팅 + 보안).
- 정합성: ADR-0012 (관측성 = Grafana Cloud Free, 단일 인스턴스 가정), ADR-0013 (sessionId TTL — multi-region 결정 시 영향), [`docs/ai-harness/11-multi-session-runbook.md`](../ai-harness/11-multi-session-runbook.md) (멀티 세션 워크트리 운영).
- 보안 룰: [`docs/ai-harness/04-security-policy.md`](../ai-harness/04-security-policy.md), CLAUDE.md §4.
- 관련 이슈: #242 (관측성 베이스라인), #243 (v0.4 계정 시스템 — 본 ADR이 선행 인프라), #338 (discord-driven-mobruji spec), #340~#342 (PR B/C/D 트래커, 본 PR에서 갱신/연동 예정).
- 후속 ADR 후보: 백/프론트 VM 사양 결정 ADR, multi-AZ HA ADR, managed DB 전환 ADR (모두 v0.4 트래픽/SLO 결정 후).
