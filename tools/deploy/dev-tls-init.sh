#!/usr/bin/env bash
# dev HTTPS 최초 인증서 발급 (ADR-0028: nip.io + Let's Encrypt HTTP-01).
#
# 실행 위치: NCP maestro VM (101.79.20.94) 의 mobruji user, repo root.
# 호출 (1회):
#   bash tools/deploy/dev-tls-init.sh
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
$COMPOSE up -d nginx

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

$COMPOSE run --rm certbot certonly --webroot -w /var/www/certbot \
    -d "${DOMAIN}" \
    ${EMAIL_ARG} ${STAGING_ARG} \
    --agree-tos --no-eff-email --non-interactive

echo "[dev-tls] 4) nginx reload — 실제 인증서 적용"
$COMPOSE exec nginx nginx -s reload

echo "[dev-tls] 완료 — https://${DOMAIN}/ 접근 가능"
echo "          이후 갱신은 certbot container 가 12h 주기로 자동 수행합니다."
echo "          전체 스택 가동: ${COMPOSE} up -d"
