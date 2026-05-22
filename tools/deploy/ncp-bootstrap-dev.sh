#!/usr/bin/env bash
# Phase 4 NCP dev 배포 1차 부트스트랩.
#
# 실행 위치: NCP maestro VM (101.79.20.94) 의 mobruji user.
# 멱등성: 같은 스크립트를 두 번 실행해도 안전. 이미 설치된 항목은 skip.
#
# 호출:
#   sudo bash tools/deploy/ncp-bootstrap-dev.sh
#
# 수행:
#   1) apt 의존성 (docker + compose v2)
#   2) mobruji user 를 docker 그룹에 추가 (재로그인 필요할 수 있음)
#   3) swap 1GB 추가 (4GB RAM tight 에 대한 안전마진)
#   4) .env.dev 가 없으면 .env.dev.example 복사 + chmod 600 후 종료 (사용자 토큰 입력 안내)
#   5) docker compose pull/up 은 사용자가 직접 실행 (.env 채운 뒤)
#
# spec: docs/features/ncp-dev-deployment.md §3, §5-3

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ "$(id -u)" -ne 0 ]; then
    echo "이 스크립트는 sudo 로 실행해야 합니다." >&2
    echo "  sudo bash $0" >&2
    exit 1
fi

# 누구를 위해 동작하는지 — sudo 호출자 본인의 user 이름.
TARGET_USER="${SUDO_USER:-mobruji}"

echo "==> 1) docker + compose v2 설치 (idempotent)"
if ! command -v docker >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y docker.io docker-compose-v2
else
    echo "    docker 이미 설치됨 — skip"
fi

echo "==> 2) ${TARGET_USER} 를 docker 그룹에 추가"
if ! id -nG "$TARGET_USER" | grep -qw docker; then
    usermod -aG docker "$TARGET_USER"
    echo "    추가됨 — ${TARGET_USER} 가 재로그인 (또는 'newgrp docker') 해야 sudo 없이 docker 사용 가능"
else
    echo "    이미 docker 그룹 — skip"
fi

echo "==> 3) swap 1GB (NCP 4GB tight 안전마진)"
if swapon --show | grep -q '/swapfile'; then
    echo "    /swapfile 이미 활성화 — skip"
else
    if [ ! -f /swapfile ]; then
        fallocate -l 1G /swapfile
        chmod 600 /swapfile
        mkswap /swapfile
    fi
    swapon /swapfile
    if ! grep -q '^/swapfile' /etc/fstab; then
        echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
    echo "    1GB swap 활성화 + fstab 등록 완료"
fi

echo "==> 4) .env.dev 부재 시 placeholder 생성"
if [ ! -f "$REPO_ROOT/.env.dev" ]; then
    cp "$REPO_ROOT/.env.dev.example" "$REPO_ROOT/.env.dev"
    chown "$TARGET_USER":"$TARGET_USER" "$REPO_ROOT/.env.dev"
    chmod 600 "$REPO_ROOT/.env.dev"
    cat <<EOF

.env.dev 가 생성되었습니다. 사용자가 다음 값을 채워야 합니다:
  - MYSQL_ROOT_PASSWORD
  - MYSQL_PASSWORD
  - MOBRUJI_ADMIN_TOKEN

  nano $REPO_ROOT/.env.dev

채운 뒤 dev 환경 가동:
  cd $REPO_ROOT
  docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build

EOF
else
    echo "    .env.dev 이미 존재 — skip (수정은 직접)"
fi

echo "==> 5) 부트스트랩 완료"
echo "    가동 명령: docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build"
echo "    상태:     docker compose -f docker-compose.dev.yml ps"
echo "    로그:     docker compose -f docker-compose.dev.yml logs -f backend"
