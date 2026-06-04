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
#   4) .env.dev 가 없으면 .env.dev.example 복사 + chmod 600 (HOST_PROJECT_DIR/DOCKER_GID 자동 채움)
#   5) /data/tmp (1777) 생성 — 오디오 자체분석 임시 디렉토리 (#1779)
#   6) mobruji/audio-analysis:dev 이미지 빌드 (#1779)
#   7) docker compose pull/up 은 사용자가 직접 실행 (.env 채운 뒤)
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
    # 오디오 자체분석 DooD (#1779): 호스트 의존 값 자동 채움.
    # HOST_PROJECT_DIR = repo 절대경로, DOCKER_GID = 호스트 docker 그룹 GID.
    DOCKER_GID="$(getent group docker | cut -d: -f3)"
    sed -i "s|^HOST_PROJECT_DIR=.*|HOST_PROJECT_DIR=${REPO_ROOT}|" "$REPO_ROOT/.env.dev"
    if [ -n "$DOCKER_GID" ]; then
        sed -i "s|^DOCKER_GID=.*|DOCKER_GID=${DOCKER_GID}|" "$REPO_ROOT/.env.dev"
    fi
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

echo "==> 5) 오디오 자체분석 임시 디렉토리 /data/tmp (#1779)"
# audio-analysis 컨테이너가 yt-dlp mp3 + librosa wav 임시 산출물을 여유 디스크(/data)에 격리.
# sticky world-writable(1777) — 컨테이너 analyzer(비-root) 가 쓰고 종료 시 rmtree 로 정리.
if [ -d /data/tmp ]; then
    echo "    /data/tmp 이미 존재 — perm 보정만"
else
    mkdir -p /data/tmp
fi
chmod 1777 /data/tmp
echo "    /data/tmp (1777) 준비 완료"

echo "==> 6) 오디오 분석 이미지 빌드 mobruji/audio-analysis:dev (#1779)"
# docker root=/data/docker 이라 빌드 산출물은 여유 디스크에 저장됨 (root fs 협소 무관).
if docker image inspect mobruji/audio-analysis:dev >/dev/null 2>&1; then
    echo "    이미지 이미 존재 — skip (재빌드: docker compose -f docker-compose.audio.yml build)"
else
    docker compose -f "$REPO_ROOT/docker-compose.audio.yml" build audio-analysis
    echo "    mobruji/audio-analysis:dev 빌드 완료"
fi

echo "==> 7) HTTPS (ADR-0028) 사전 작업 안내"
cat <<'EOF'
    dev HTTPS(nip.io + Let's Encrypt) 활성화 전 다음을 확인하세요:
      1) NCP ACG inbound 에 443/TCP 추가 (현재 80 만 — 콘솔에서 1회 설정).
         80 은 ACME challenge·리다이렉트용으로 유지.
      2) .env.dev 입력 후 최초 인증서 1회 발급:
           bash tools/deploy/dev-tls-init.sh
         (이후 갱신은 certbot container 가 자동 수행)
EOF

echo "==> 8) 부트스트랩 완료"
echo "    가동 명령: docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build"
echo "    오디오 자체분석(use-docker) 활성 가동: docker compose -f docker-compose.dev.yml -f docker-compose.dev.audio.yml --env-file .env.dev up -d --build"
echo "    HTTPS 발급: bash tools/deploy/dev-tls-init.sh  (최초 1회)"
echo "    상태:     docker compose -f docker-compose.dev.yml ps"
echo "    로그:     docker compose -f docker-compose.dev.yml logs -f backend"
