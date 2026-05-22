---
id: 0012
title: 운영 관측성 수집 스택 — Grafana Cloud Free + Prometheus remote_write
status: accepted
date: 2026-05-22
deciders: [@goohong]
---

# 0012. 운영 관측성 수집 스택 — Grafana Cloud Free + Prometheus remote_write

## Context
v0.3 P2 운영 관측성 베이스라인 spec(`docs/features/observability-baseline.md`) 이 핵심 카운터 + p95 매트릭스를 정의했고, 이를 수집·시각화·알림할 외부 스택을 결정해야 한다. 운영 인스턴스는 1개(systemd 단일 호스트, plan 25/26 의 selective backfill + StartLimitInterval 가드 환경), 사용자 트래픽은 미미하며, 운영자는 본인 1인 + 사이클 rev. **운영 부담을 최소화하면서 v0.3 안에 1차 대시보드 + 알림이 동작**해야 하고, 무료 한도를 넘기지 않아야 한다 (예산 0 가정). #62 p95 회귀 가드, #68 MusicBrainz, #69 Spotify 가 모두 본 결정 위에서 카운터/알림을 추가한다.

## Decision
**Grafana Cloud Free 를 1차 채택**한다.

- Spring Boot Actuator + Micrometer 로 노출되는 `/actuator/prometheus` endpoint 를 **Grafana Cloud 가 호스팅하는 Prometheus 인스턴스로 `remote_write` push** (Grafana Agent 또는 `prometheus-node-exporter` + `agent` flow mode).
- 시각화/대시보드: Grafana Cloud Grafana 인스턴스. v0.3 베이스라인은 **단일 대시보드 1개** (recommendation/voice/song 도메인 카운터 + p95 + 외부 API 에러율 + JVM heap). JSON provisioning 으로 IaC 관리 (별 PR).
- 알림: 1차는 **Grafana Cloud Alert 가 아닌 backend 측 자체 임계 평가** 후 Discord webhook(`MOBRUJI_ALERT_WEBHOOK_URL`) 으로 송신. Grafana Cloud Free 의 알림 룰은 향후 대시보드 안정화 후 이관 검토.
- 무료 한도(active series 10k, log 50GB, 14-day retention) 안에서 운영. 카운터/타이머 합 ~30개 × 라벨 카디널리티 ≤ 100 → ≤ 3,000 series. 여유 충분.
- 무료 한도 초과 또는 latency 민감해지는 시점에 self-host (Prometheus + Grafana docker-compose) 로 이관하는 별도 ADR 신설 — 본 ADR 은 superseded 처리.

## Consequences
### 긍정적
- **운영 부담 0**: 자체 Prometheus/Grafana 인스턴스 운영 불요. systemd 단일 호스트 환경에 추가 컨테이너 0개.
- **즉시 사용 가능한 대시보드/알림**: Grafana 기본 차트 + 외부 webhook 통합 즉시.
- **무료**: 사용자 트래픽이 미미한 단계에서 예산 부담 0.
- **이관 비용 낮음**: Prometheus remote_write 는 OSS 표준 — self-host 전환 시 백엔드 코드 변경 없음.

### 부정적
- **vendor lock-in 약간**: Grafana Cloud 가 대시보드 UI/알림 룰을 호스팅. 대시보드는 JSON export 로 portable 하지만 알림 룰은 일부 차이.
- **무료 한도 초과 위험**: 사용자 트래픽이 갑자기 늘거나 라벨 카디널리티가 폭발하면 한도 초과 → 데이터 누락. §5-7 PII 마스킹 룰이 카디널리티 폭발의 1차 방어선.
- **인터넷 연결 의존**: 단일 호스트 ↔ Grafana Cloud 간 네트워크 단절 시 메트릭 누락. Grafana Agent 의 WAL 으로 최대 2시간 버퍼링.
- **알림 latency**: backend 자체 임계 평가는 짧은 윈도우(1~5분) 만 정확. 장기 추세 알림은 Grafana Cloud Alert 로 이관 필요 (v0.3 후반 후속 PR).

## Alternatives (considered)

- **(A) Prometheus + Grafana self-hosted (docker-compose)** — 운영 통제권 최고 + vendor lock-in 0. 거절 사유: 운영 인스턴스 1개에 추가 컨테이너 2~3개 + 디스크/메모리 비용 + 백업/업그레이드 운영 부담. v0.4 사용량 증가 후 재검토.
- **(B) Sentry Performance Free** — 분산 트레이싱/에러 추적은 강하나 무료 한도(5k tx/month) 가 너무 적고, counter/timer 같은 일반 메트릭 모델이 약함. 거절.
- **(C) Datadog/New Relic 유료** — 기능 풍부하지만 예산 0 가정에 어긋남. 사용자 트래픽이 의미 있어진 후 별도 ADR.
- **(D) 로그 기반 (stdout + journalctl + grep)** — 추가 인프라 0 이지만 p95 같은 백분위/시각화/임계 알림이 불가. 거절.
- **(E) Grafana Cloud Free + Loki(로그)** — 로그 집계까지 같이 도입. 거절 사유: v0.3 까지는 단일 인스턴스 stdout 으로 충분, 다중 인스턴스 운영 결정 후 별도 spec(`docs/features/observability-baseline.md §4 제외`).
- **(F) Micrometer push directly to Grafana Cloud (`micrometer-registry-prometheus` + `prometheus-rsocket`)** — Grafana Agent 없이 backend 가 직접 push. 거절 사유: 인증/재시도/WAL 을 backend 가 떠안아야 함, agent 우회 이득 미미.

## References
- Feature Spec: `docs/features/observability-baseline.md` (v0.3 P2 베이스라인)
- 이슈 #242 (운영 관측성 베이스라인)
- 관련 이슈: #62 (p95 회귀 가드), #68 (MusicBrainz), #69 (Spotify), #209 (sessionId TTL — 알림 라벨 정책 영향)
- 룰 문서: `docs/ai-harness/10-observability.md` (본 ADR 머지 후 Phase 1 갱신)
- 후속 ADR 후보: self-host 이관 ADR, Grafana Cloud Alert 이관 ADR (v0.3 후반 또는 v0.4)
- 보안 정책: `docs/ai-harness/04-security-policy.md`, CLAUDE.md §4
