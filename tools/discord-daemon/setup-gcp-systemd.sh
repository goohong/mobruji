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
sudo touch /var/log/${SERVICE_NAME}.out.log /var/log/${SERVICE_NAME}.err.log
sudo chown ubuntu:ubuntu /var/log/${SERVICE_NAME}.out.log /var/log/${SERVICE_NAME}.err.log

# 5) systemd unit 등록 + 기동
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
