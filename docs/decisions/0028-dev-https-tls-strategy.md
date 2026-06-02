---
id: 0028
title: dev 배포 HTTPS 전환 전략 — nip.io wildcard DNS + Let's Encrypt
status: accepted
date: 2026-06-03
deciders: [@mobruji-maestro]
---

# 0028. dev 배포 HTTPS 전환 전략 — nip.io wildcard DNS + Let's Encrypt

## Context

dev 환경([`docs/features/ncp-dev-deployment.md`](../features/ncp-dev-deployment.md))은 NCP maestro VM(`101.79.20.94`)의 docker nginx container가 **plain HTTP(`http://101.79.20.94/`)** 로 서빙한다. nginx-dev conf(`nginx/conf.d/dev.conf` L12)에도 "SSL 미적용(1차). 도메인 발급 후 별 PR"로만 보류되어 있다.

문제는 음역대 자동 측정 기능([`docs/features/voice-range-auto-measurement.md`](../features/voice-range-auto-measurement.md))이 `getUserMedia({ audio: true })`로 마이크 입력을 받는데, **브라우저의 secure context 정책상 `getUserMedia`·`MediaRecorder`·`MediaDevices` 등은 HTTPS(또는 `localhost`)에서만 호출 가능**하다. HTTP origin에서는 `navigator.mediaDevices`가 `undefined`이거나 호출이 즉시 거부된다. 따라서 사용자가 모바일에서 dev URL을 직접 brouse(`(S2) dogfooding`)할 때 자동 측정이 영구히 동작하지 않고, rev sub-agent의 secure-context 기능 E2E도 dev에서 불가능하다.

dev 도메인 정식 발급(`dev.mobruji.app` via Cloudflare)은 prod 인프라([`docs/features/deployment-infrastructure.md`](../features/deployment-infrastructure.md))와 묶여 **v0.4로 보류**된 상태(`ncp-dev-deployment.md` §4 Out of Scope)다. 그 결정을 기다리지 않고 dev에서 secure-context 기능을 즉시 활성화할 경량 HTTPS 경로가 필요하다.

## Decision

dev 환경 HTTPS는 **nip.io wildcard DNS(`101-79-20-94.nip.io`) + Let's Encrypt(HTTP-01 challenge)** 로 전환한다. 도메인 구매·Cloudflare 연동 없이 **공인 신뢰 인증서**를 발급해 데스크탑·모바일 모두에서 secure context를 확보한다. 인증서 갱신은 docker-compose.dev.yml에 **certbot container**를 추가해 자동화한다.

prod 도메인(`dev.mobruji.app`)이 v0.4에서 발급되면 본 결정을 폐기하고 정식 도메인 + Cloudflare Origin CA(또는 Let's Encrypt DNS-01)로 마이그레이션한다 — 그때 server_name과 `NEXT_PUBLIC_API_BASE_URL`만 교체하면 되도록 conf를 구성한다.

> 본 ADR scope는 **전략 결정**까지다. nginx-dev conf·docker-compose.dev.yml·NCP ACG·CD workflow의 실제 변경은 후속 구현 PR(infra)에서 다룬다.

### 채택 항목

1. **호스트네임**: `101-79-20-94.nip.io` — nip.io는 호스트네임에 인코딩된 IP를 그대로 반환하는 무료 wildcard DNS이므로 `101-79-20-94.nip.io → 101.79.20.94`로 해석된다. 도메인 등록 불필요.
2. **인증서**: Let's Encrypt, **HTTP-01 challenge**(`/.well-known/acme-challenge/`를 80포트로 노출). DNS-01은 nip.io에 TXT 레코드를 쓸 수 없으므로 불가.
3. **갱신**: docker-compose.dev.yml에 `certbot/certbot` container 추가. 공유 volume(`certbot-certs`, `certbot-webroot`)로 nginx와 인증서·webroot를 공유. `certbot renew`를 12시간 주기 loop(또는 host cron)로 실행 — Let's Encrypt 90일 만료를 만료 30일 전 자동 갱신, 갱신 후 nginx `-s reload`.
4. **nginx-dev conf 변경 범위**(후속 PR):
   - `server_name _` → `server_name 101-79-20-94.nip.io;`
   - `listen 443 ssl;` + `ssl_certificate /etc/letsencrypt/live/101-79-20-94.nip.io/fullchain.pem;` + `ssl_certificate_key .../privkey.pem;`
   - 80포트: `/.well-known/acme-challenge/`는 webroot 서빙 유지, 그 외 path는 `return 301 https://$host$request_uri;`로 리다이렉트
   - 기존 `/api/`·`/actuator/health/*`·catch-all 프록시 블록은 443 server 블록으로 이동(로직 동일)
5. **NCP ACG**: inbound **443 추가**(현재 80만). 80은 ACME challenge·리다이렉트용으로 유지.
6. **프론트엔드 origin**: `NEXT_PUBLIC_API_BASE_URL`을 `https://101-79-20-94.nip.io`로 갱신(dev env). dev/prod 분기는 env로만 — 화면 변경 없음.
7. **HSTS·HTTP/2**: dev는 SLO 없음. HSTS는 마이그레이션 시 origin 변경을 막을 수 있으므로 dev에서는 **미적용**. HTTP/2(`http2 on;`)는 선택(부하 무관, 적용 무해).

## Consequences

### 긍정적

- **secure context 즉시 확보**: `getUserMedia` 등 secure-context API가 데스크탑·모바일 모두에서 동작 → 음역대 자동 측정 dogfooding·rev E2E 가능.
- **공인 신뢰 인증서**: 브라우저 경고 없음. 모바일(iOS Safari / Android Chrome)에서 cert 프로파일 설치 같은 수동 신뢰 절차 불필요.
- **비용 0**: nip.io 무료 + Let's Encrypt 무료. 도메인 구매·Cloudflare 계정 연동 불필요.
- **prod 도메인 결정과 디커플링**: v0.4 prod 도메인/Cloudflare 결정을 기다리지 않고 진행. 마이그레이션 시 server_name + env 2곳만 교체.
- **갱신 무인화**: certbot container loop로 90일 만료 자동 갱신. 운영자 개입 0.

### 부정적

- **nip.io 외부 의존**: nip.io는 제3자 무료 서비스 — 드물게 다운/응답 지연 시 dev 접근 영향. dev는 24/7 SLA 없으므로 허용. 다운 시 `/etc/hosts` 매핑 또는 self-signed로 일시 우회 가능.
- **Let's Encrypt rate limit**: 동일 도메인 주당 인증서 5개 제한. dev 잦은 재발급 시 staging endpoint로 먼저 검증 권장. nip.io 같은 public suffix 특성상 통상 운영에서는 무관.
- **80포트 상시 노출 필요**: HTTP-01 challenge가 80포트 도달성을 요구 → 80을 닫을 수 없음(리다이렉트 + ACME 전용으로만 사용).
- **마이그레이션 부채**: prod 도메인 발급 시 본 결정 폐기 + conf/env 교체 작업 발생. 단 범위가 작아(2곳) 부담 낮음.
- **인증서 volume 백업 없음**: certbot-certs volume 유실 시 재발급(rate limit 내). dev는 휘발 허용이므로 수용.

## Alternatives (considered)

- **(A) self-signed 인증서** — 거절. nginx에서 `openssl`로 즉시 생성·0비용·갱신 불요(장기 만료)이나, **모바일에서 신뢰 절차가 치명적**이다. iOS Safari는 cert 프로파일 설치+신뢰 토글 없이는 secure context를 부여하지 않고, Android Chrome도 경고 우회 후에도 `getUserMedia`가 불안정하다. 사용자 dogfooding 대상이 모바일(`ncp-dev-deployment.md` §2 S2)이므로 핵심 목표(모바일 마이크 활성화)를 달성하지 못한다. 데스크탑 빠른 점검용으로만 유효해 채택 불가. 단 nip.io/Let's Encrypt 동시 장애 시 **비상 우회** 카드로는 유지.
- **(B) nip.io + Let's Encrypt (HTTP-01)** — **채택**. 공인 신뢰 인증서 + 0비용 + 도메인 구매 불요 + 모바일 정상 + prod 도메인 결정과 디커플링. 갱신 자동화가 유일한 비용이나 certbot container loop로 무인화.
- **(C) 도메인 취득(`dev.mobruji.app`) + Let's Encrypt/Cloudflare Origin CA** — 거절(현 시점). "정식" 경로이나 (1) prod 도메인/Cloudflare 결정이 v0.4로 명시 보류(`ncp-dev-deployment.md` §4), (2) 도메인 구매·DNS 셋업이 **사용자 액션**을 요구해 자율 진행 불가, (3) SLO 없는 dev에 과대 투자. v0.4 prod 인프라 묶음에서 본 ADR을 대체하는 정식 경로로 채택 예정.
- **(D) Cloudflare Tunnel(cloudflared) — Cloudflare 발급 TLS로 터널링** — 거절. 도메인·Cloudflare 계정 연동 필요(=옵션 C 의존) + tunnel daemon이 4GB VM에 상주 부담. ADR-0015가 maestro VM inbound를 최소화하기로 한 방향과도 결이 다름.
- **(E) HTTP 유지 + secure-context 기능 비활성화** — 거절. 음역대 자동 측정이 dev에서 영구 미검증 → dogfooding·rev E2E 목적 자체가 무산.

## References
- Feature Spec: [`docs/features/ncp-dev-deployment.md`](../features/ncp-dev-deployment.md) (§4에서 SSL을 prod 도메인과 묶어 보류 — 본 ADR이 dev 한정 경량 경로로 선행), [`docs/features/voice-range-auto-measurement.md`](../features/voice-range-auto-measurement.md) (getUserMedia secure-context 요구 출처), [`docs/features/deployment-infrastructure.md`](../features/deployment-infrastructure.md) (prod 도메인/Cloudflare — v0.4 마이그레이션 대상), [`docs/features/nginx-dns-resolver-pattern.md`](../features/nginx-dns-resolver-pattern.md) (nginx-dev conf 변수 패턴 — 443 이전 시 동일 패턴 유지).
- 정합성: ADR-0015 (호스팅 스택 — maestro VM inbound 최소화 방향, 본 ADR은 dev 한정 443 추가), [`nginx/conf.d/dev.conf`](../../nginx/conf.d/dev.conf) (L12 SSL 보류 주석 — 본 ADR이 후속 PR에서 해소).
- 보안 룰: [`docs/ai-harness/04-security-policy.md`](../ai-harness/04-security-policy.md) (마이크 audio 스트림 서버 미업로드 — secure context와 별개로 유지), CLAUDE.md §4.
- 후속 구현 PR(infra, 본 ADR scope 외): nginx-dev conf 443 + ACME 블록, docker-compose.dev.yml certbot container + volume, NCP ACG 443 inbound, `NEXT_PUBLIC_API_BASE_URL` 갱신, CD workflow healthcheck URL `https://` 전환.
- 후속 ADR 후보: prod 도메인 발급 시 본 ADR을 대체하는 정식 TLS 경로(`dev.mobruji.app` + Cloudflare) — v0.4.
