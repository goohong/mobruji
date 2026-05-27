---
feature: nginx DNS resolver pattern (stale upstream IP 회피)
slug: nginx-dns-resolver-pattern
status: implementing
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [1142, 1144]
last_reviewed: 2026-05-27
---

# nginx DNS resolver pattern (stale upstream IP 회피)

## 1) 개요 (What / Why)

nginx 의 default `proxy_pass http://hostname:port` 동작은 **nginx worker 기동 시점에 hostname 을 1회 resolve 한 뒤 캐시 영구 보존** 입니다. Docker 환경에서 backend container 가 재기동되면 새 IP 가 할당되지만 nginx 는 옛 IP 를 계속 시도 → connection refused → 502 사용자 가시.

본 spec 은 nginx conf 에 (1) `resolver 127.0.0.11 valid=30s` (Docker 내부 DNS) 명시 + (2) `proxy_pass` 를 변수 패턴 (`set $upstream_backend ...; proxy_pass $upstream_backend;`) 으로 변환하여 nginx 가 매 request 마다 Docker DNS 를 lookup 하도록 강제합니다.

대상 액터: production 운영자 (사용자 한 명). 해결 문제: backend container 재기동마다 nginx restart 가 필요한 운영 부담 + 그 사이 502 노출.

## 2) 사용자 시나리오

- 운영자가 backend container 만 재기동 (`docker restart mobruji-backend-dev`) — nginx restart 없이도 200 유지.
- backend 가 OOM / health fail 로 docker 자동 재기동 — 새 IP 할당되어도 nginx 가 30초 내 자동 인식, 502 minimize.
- backend 의 docker network 변경 (compose recreate) — nginx restart 없이 새 IP 인식.

## 3) 요구사항

### 기능 요구사항
- [ ] nginx conf 에 `resolver 127.0.0.11 valid=30s;` 추가 (Docker 내부 DNS).
- [ ] backend upstream 의 `proxy_pass` 를 `set $upstream_backend http://mobruji-backend-dev:8081;` + `proxy_pass $upstream_backend;` 변수 패턴으로 변환.
- [ ] 변환 후 nginx config 문법 검증 (`nginx -t`) 통과.
- [ ] backend container restart 후 nginx restart 없이 200 응답 확인 (검증 시나리오 §7).

### 비기능 요구사항
- 성능 — `valid=30s` 캐시로 매 request DNS lookup overhead 회피. 30초 stale window 는 운영상 허용 (사용자 1명 트래픽 + 자동 재기동 빈도 낮음).
- 신뢰성 — Docker daemon 의 내부 DNS (127.0.0.11) 가 down 되면 nginx 도 fail. Docker daemon 가용성에 의존 (사실상 docker 가 죽으면 모든 컨테이너도 죽으므로 단일 failure 도메인 추가 없음).
- 관측성 — nginx error log 에 `connect() failed` + IP 가 찍히도록 유지 (현재 conf 변경 없음).
- 롤백 — 변경 commit 1개로 격리 → revert PR 즉시 가능.

## 4) 범위 / 비범위 (중요)

### 포함
- production nginx conf (1 파일) 의 backend upstream 한 곳에 resolver + 변수 패턴 적용.
- 변경 전후 검증 시나리오 (backend restart 후 nginx restart 없이 200 유지 확인).
- 변경 PR 본문에 검증 결과 첨부.

### 제외 (Out of Scope)
- nginx-as-loadbalancer / multi-upstream / weighted balancing — 현재 backend instance 1개.
- 다른 upstream (web frontend, observability stack 등) — 본 PR 은 backend 만. 동일 패턴 확장은 후속 spec.
- nginx 운영 자체의 컨테이너 추상화 (k8s ingress 전환 등).
- 보안 hardening (mTLS, rate-limit) — 무관.
- backend 자체의 graceful restart / blue-green — 본 spec 은 nginx ↔ backend connection layer 만.

## 5) 설계

### 5-1) 도메인 모델
- 도메인 모델 변경 없음 (infra 단일 변경). `docs/ai-harness/06-domain-model.md` 갱신 불요.

### 5-2) API 엔드포인트
- 신규 endpoint 없음. 기존 backend API 모두 본 변경의 영향 없음 (nginx → backend connection 만 변경).

### 5-3) 외부 연동
- Docker daemon 내부 DNS (127.0.0.11) — 외부 서비스 아님 (host-local). API 키 불필요.

### 5-4) 데이터 흐름 / 시퀀스

```
[Before — stale 캐시 사고 시점]
client → nginx (캐시: 172.18.0.5) → connect 172.18.0.5:8081 → refused → 502

[After — resolver + 변수 패턴]
client → nginx → DNS lookup `mobruji-backend-dev` via 127.0.0.11
       → cache valid 30s → connect 현재 IP:8081 → backend → 200
```

backend 재기동 직후 30초 window:
- nginx 캐시 만료 (≤30s) → 새 lookup → 새 IP → 200.
- 30초 동안 stale 캐시일 수 있으나 backend 가 healthy 가 될 때까지의 시간 (보통 5-10초) 과 겹치므로 실 노출 ≤ max(0, 30 - backend_startup) 초.

### 5-5) DB 마이그레이션
- 해당 없음 (infra 변경).

### 5-6) 프론트엔드 화면
- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (impl, be sub-agent)** — production nginx conf 변경 + 검증 결과 첨부.
  - scope=infra, 보호 영역 (`docker-compose*.yml` 또는 nginx conf 가 보호 영역에 포함되는 경우) → `needs-human-review` 라벨.
  - 검증: §7 시나리오 1-2 모두 수행 후 결과 PR 본문에 첨부.

## 7) 테스트 전략

### 검증 시나리오 1 — backend restart 후 nginx restart 없이 200 유지

1. 사전 — production `/actuator/health` 200 확인.
2. `sudo docker restart mobruji-backend-dev` 실행.
3. backend healthy 까지 wait (`docker inspect ... --format '{{.State.Health.Status}}'` healthy).
4. nginx **재시작 없이** `curl https://<production-host>/actuator/health` 호출 → 30초 내 200 응답 확인.
5. nginx error log 에 `connect() failed` 가 없거나 (있더라도) 30초 내 정상화 확인.

### 검증 시나리오 2 — DNS lookup 실제 발생 확인

1. nginx conf 변경 후 `sudo docker exec mobruji-nginx-dev nginx -t` 통과.
2. `sudo docker exec mobruji-nginx-dev nginx -s reload` 로 graceful reload.
3. nginx access log (debug level 일시 활성 가능) 또는 `tcpdump -i any -n port 53` 으로 backend hostname 의 DNS query 가 매 30초 내 발생함 확인.

### Mock 전략
- 외부 연동 없음 → mock 불요. 실 production 환경 직접 검증.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | production nginx conf 파일 경로는 어디인가 | (a) `docker-compose.yml` 의 nginx service 내부 conf mount / (b) 별도 `nginx/default.conf` 파일 / (c) nginx image 빌드 시 COPY | be sub-agent 가 PR 1 작업 시 확인 / 2026-05-27 |
| Q2 | 변경 점검 절차 — production 적용 전 staging / canary 가 있는가, 아니면 production 직접 적용인가 | (a) staging 먼저 / (b) production 직접 (현재 단일 환경) | 사용자 확정 필요 / 2026-05-27 |
| Q3 | `valid=30s` 가 적절한가 — 너무 짧으면 DNS query overhead, 너무 길면 stale window 확대 | (a) 30s default / (b) 10s (사용자 트래픽 적음, 빠른 회복 우선) / (c) 60s (overhead 최소) | be sub-agent + 운영 관찰 / 2026-05-28 |

## 9) 결정 로그

- 2026-05-26: 초안 작성 (status=draft). 2026-05-26 16:30 KST P0 사고 (nginx stale DNS → 502) 가 트리거.
