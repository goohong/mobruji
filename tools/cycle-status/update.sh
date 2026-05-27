#!/usr/bin/env bash
# tools/cycle-status/update.sh — cycle-status.json 안전 업데이트 도구.
# 수동 JSON 편집 시 발생하는 시각 포맷(Z suffix), 오타, 구조 파괴 방지.

set -uo pipefail

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME}/.mobruji}"
STATUS_FILE="${MOBRUJI_DIR}/cycle-status.json"

usage() {
  echo "Usage: $0 <ws> <action> [options]"
  echo "Actions:"
  echo "  set-in-progress --title '...' [--note '...']"
  echo "  set-idle [--note '...']"
  exit 1
}

[ $# -lt 2 ] && usage

WS=$1
ACTION=$2
shift 2

NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# 파일 부재 시 초기 구조 생성
if [ ! -f "$STATUS_FILE" ]; then
  mkdir -p "$MOBRUJI_DIR"
  echo "{}" > "$STATUS_FILE"
fi

case "$ACTION" in
  set-in-progress)
    TITLE=""
    NOTE=""
    while [[ $# -gt 0 ]]; do
      case $1 in
        --title) TITLE="$2"; shift 2 ;;
        --note) NOTE="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    [ -z "$TITLE" ] && echo "Error: --title is required" && exit 1
    
    jq --arg ws "$WS" --arg title "$TITLE" --arg note "$NOTE" --arg now "$NOW" \
      '.[$ws].in_progress = {title: $title, note: $note, started_at: $now}' \
      "$STATUS_FILE" > "${STATUS_FILE}.tmp" && mv "${STATUS_FILE}.tmp" "$STATUS_FILE"
    echo "Status: $WS set to IN_PROGRESS ($TITLE)"
    ;;

  set-idle)
    NOTE=""
    while [[ $# -gt 0 ]]; do
      case $1 in
        --note) NOTE="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    
    jq --arg ws "$WS" --arg note "$NOTE" --arg now "$NOW" \
      '.[$ws].last_completed = (.[$ws].in_progress // {}) | .[$ws].last_completed.completed_at = $now | .[$ws].last_completed.final_note = $note | del(.[$ws].in_progress)' \
      "$STATUS_FILE" > "${STATUS_FILE}.tmp" && mv "${STATUS_FILE}.tmp" "$STATUS_FILE"
    echo "Status: $WS set to IDLE"
    ;;

  *) usage ;;
esac
