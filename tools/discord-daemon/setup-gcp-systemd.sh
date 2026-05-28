#!/usr/bin/env bash
# GCP/Linux 셋업 스크립트 — plan 22 macOS LaunchAgent 의 Linux 짝.
#
# 사용자가 GCP VM SSH 들어가서 한 줄 실행:
#   cd ~ && git clone https://github.com/goohong/mobruji.git \
#       && cd mobruji/tools/discord-daemon && bash setup-gcp-systemd.sh
#
# 1차 실행: venv + .env 생성 후 토큰 입력 안내하고 종료.
# 2차 실행 (.env 채운 뒤): systemd unit 등록 + start.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SERVICE_NAME="mobruji-discord-daemon"
SERVICE_FILE="${SERVICE_NAME}.service"

# 1) 의존성 설치 (idempotent)
sudo apt update
sudo apt install -y python3-venv python3-pip git

# 2) venv 생성 + 패키지 설치
if [ ! -d venv ]; then
  python3 -m venv venv
fi
# shellcheck disable=SC1091
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
deactivate

# 3) .env 가 없으면 템플릿 복사 후 종료 (사용자가 토큰 입력해야 함)
if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  echo ""
  echo ".env 생성됨. 토큰 채워넣고 스크립트 재실행:"
  echo "  nano $SCRIPT_DIR/.env"
  echo "  필수: DISCORD_BOT_TOKEN, GITHUB_PAT"
  echo "  기본값 확인: MOBRUJI_CHANNEL_ID=1506925497651560458, ALLOWED_USER_IDS, GITHUB_REPO=goohong/mobruji"
  echo ""
  echo "재실행: bash setup-gcp-systemd.sh"
  exit 0
fi

# 4) 로그 파일 준비 (append 모드라 미리 touch 필요)
# #909 F-2: 기존 `ubuntu:ubuntu` 하드코드 → 실 운영자(SUDO_USER → USER fallback)
# 자동 감지. NCP 실 계정은 `mobruji`, GCP 옛 셋업은 `ubuntu` 라 비대칭 발생했었음.
# systemd unit 의 `User=` 와 일치해야 로그 회전/append 권한 충돌이 없다.
RUN_USER="${SUDO_USER:-$USER}"
RUN_GROUP="$(id -gn "$RUN_USER")"
echo "로그 owner: ${RUN_USER}:${RUN_GROUP} (systemd unit 의 User= 와 일치해야 함)"
sudo touch /var/log/${SERVICE_NAME}.out.log /var/log/${SERVICE_NAME}.err.log
sudo chown "${RUN_USER}:${RUN_GROUP}" /var/log/${SERVICE_NAME}.out.log /var/log/${SERVICE_NAME}.err.log

# 5) hook symlink ensure — `~/.mobruji/<hook>` 가 부재/regular file 이면 repo 내 hook
# source 로 symlink 자동 생성 (PR #1154 helper-direct-work-guard 등). deploy.sh 와
# 같은 공통 라이브러리 사용 (lib/hook_symlinks.sh, PR #1124).
if [[ -f "$SCRIPT_DIR/lib/hook_symlinks.sh" ]]; then
  # setup script 가 실행되는 경로 = repo 내 hook source 의 진실. 사용자가
  # 별도 HOOK_SOURCE_DIR 을 명시하지 않은 경우 SCRIPT_DIR 을 사용한다.
  export MOBRUJI_HOOK_SOURCE_DIR="${MOBRUJI_HOOK_SOURCE_DIR:-$SCRIPT_DIR}"
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/lib/hook_symlinks.sh"
  ensure_hook_symlinks
else
  echo "[setup] lib/hook_symlinks.sh 부재 — hook symlink ensure skip (PR #1124 머지 전 호환)."
fi

# 6) systemd unit 등록 + 기동
sudo cp "$SERVICE_FILE" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"
sleep 2
sudo systemctl status "$SERVICE_NAME" --no-pager || true

echo ""
echo "daemon 시작됨."
echo "  로그 follow:   sudo journalctl -u $SERVICE_NAME -f"
echo "  out 로그 tail: sudo tail -F /var/log/${SERVICE_NAME}.out.log"
echo "  err 로그 tail: sudo tail -F /var/log/${SERVICE_NAME}.err.log"
echo "  재시작:        sudo systemctl restart $SERVICE_NAME"
echo "  중지:          sudo systemctl stop $SERVICE_NAME"
