#!/usr/bin/env bash
# dev HTTPS 최초 인증서 발급 (ADR-0028: nip.io + Let's Encrypt HTTP-01).
#
# 실행 위치: NCP maestro VM (101.79.20.94) 의 mobruji user, repo root.
# 호출 (1회):
#   bash tools/deploy/dev-tls-init.sh
#
# ⚠️ 전제: NCP ACG inbound 에 TCP **80 + 443 둘 다** 열려 있어야 한다 (ADR-0028 §5).
#   80 만 열려 있으면: HTTP-01 발급은 되나, 외부에서 :443 접근 불가 → :80 의 301
#   리다이렉트가 닿지 못하는 :443 으로 보내 dev 전체가 외부에서 먹통이 된다. ACG 443
#   inbound 추가는 NCP 콘솔 작업(본 스크립트 범위 밖) — 발급 후 외부에서
#   `nc -z 101.79.20.94 443` 으로 개방 확인할 것 (호스트 자기 자신은 hairpin NAT 로
#   막혀 보일 수 있으니 외부 호스트에서 확인).
#
# 멱등성: 실제 인증서가 이미 발급돼 있으면 skip. 재발급은 FORCE=1 로 강제.
# 옵션 env:
#   LETSENCRYPT_EMAIL — 갱신 만료 알림 수신 메일 (없으면 무등록 발급).
#   STAGING=1         — Let's Encrypt staging endpoint (rate limit 회피용 사전 검증).
#   FORCE=1           — 기존 인증서 무시하고 재발급.
#
# 배경: nginx 443 server 블록은 인증서 파일이 존재해야 boot 된다(닭-달걀). 따라서
#   (1) dummy self-signed 를 volume 에 심어 nginx 를 띄우고, (2) certbot 이 80 ACME
#   challenge 로 실제 인증서를 발급받아 dummy 를 대체한 뒤, (3) nginx 를 reload 한다.
#
# spec: docs/decisions/0028-dev-https-tls-strategy.md

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

DOMAIN="101-79-20-94.nip.io"
COMPOSE="docker compose -f docker-compose.dev.yml --env-file .env.dev"
LIVE_PATH="/etc/letsencrypt/live/${DOMAIN}"

if [ ! -f .env.dev ]; then
    echo "[dev-tls] .env.dev 부재 — 먼저 ncp-bootstrap-dev.sh 로 생성/입력하세요." >&2
    exit 2
fi

# 1) 이미 실제 인증서가 있으면 skip (멱등).
if [ "${FORCE:-0}" != "1" ] && \
   $COMPOSE run --rm --entrypoint sh certbot -c "test -f ${LIVE_PATH}/fullchain.pem" 2>/dev/null; then
    echo "[dev-tls] 실제 인증서가 이미 존재합니다 (${DOMAIN}). 재발급하려면 FORCE=1."
    exit 0
fi

echo "[dev-tls] 1) dummy self-signed 인증서 생성 (nginx 443 boot 용)"
$COMPOSE run --rm --entrypoint sh certbot -c "\
    mkdir -p ${LIVE_PATH} && \
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
      -keyout ${LIVE_PATH}/privkey.pem \
      -out ${LIVE_PATH}/fullchain.pem \
      -subj '/CN=${DOMAIN}'"

echo "[dev-tls] 2) nginx (+ 의존 서비스) 기동 — 80 ACME challenge 서빙"
# --force-recreate: nginx 가 이전 인증서 부재로 crash-loop(Restarting) 중이면 plain
# `up -d` 는 "이미 떠 있음"으로 보고 재기동을 건너뛴다 → dummy 인증서를 못 집어 계속
# crash. force-recreate 로 새 컨테이너를 띄워 방금 심은 dummy 인증서로 boot 시킨다.
$COMPOSE up -d --force-recreate nginx

echo "[dev-tls] 3) dummy 제거 후 Let's Encrypt 실제 인증서 발급"
$COMPOSE run --rm --entrypoint sh certbot -c "rm -rf ${LIVE_PATH} /etc/letsencrypt/archive/${DOMAIN} /etc/letsencrypt/renewal/${DOMAIN}.conf"

EMAIL_ARG="--register-unsafely-without-email"
if [ -n "${LETSENCRYPT_EMAIL:-}" ]; then
    EMAIL_ARG="--email ${LETSENCRYPT_EMAIL}"
fi
STAGING_ARG=""
if [ "${STAGING:-0}" = "1" ]; then
    STAGING_ARG="--staging"
fi

# --entrypoint certbot: docker-compose.dev.yml 의 certbot 서비스 entrypoint 는
# "while :; do certbot renew ...; done" 갱신 루프다. `run certbot certonly ...` 는
# command 만 덮고 entrypoint 는 그대로라, certonly 인자가 갱신 루프 sh -c 의 위치
# 인자로 먹혀 무시되고 `certbot renew` 만 무한 반복(행). entrypoint 를 certbot 바이너리로
# 명시 override 해야 certonly 가 실제 실행된다 (#1579 — dev TLS 최초 발급 불능 근본 원인).
$COMPOSE run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot \
    -d "${DOMAIN}" \
    ${EMAIL_ARG} ${STAGING_ARG} \
    --agree-tos --no-eff-email --non-interactive

echo "[dev-tls] 4) nginx reload — 실제 인증서 적용"
$COMPOSE exec nginx nginx -s reload

echo "[dev-tls] 완료 — https://${DOMAIN}/ 접근 가능"
echo "          이후 갱신은 certbot container 가 12h 주기로 자동 수행합니다."
echo "          전체 스택 가동: ${COMPOSE} up -d"
