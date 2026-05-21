"""Mobruji Discord daemon (macOS LaunchAgent 옵션 A).

기능:
- Discord Gateway WebSocket 으로 24/7 연결을 유지한다.
- 지정된 채널(MOBRUJI_CHANNEL_ID)에서 화이트리스트(ALLOWED_USER_IDS)
  사용자가 보낸 메시지만 처리한다.
- 처리 결과는 GitHub `repository_dispatch` (event_type=discord_message) 로
  mobruji repo에 전달된다. workflow 가 후속 작업을 담당한다.
- 옵션으로 inbox 파일에도 메시지를 append 한다 (디버깅/백업용).

운영 가이드와 셋업 절차는 같은 디렉토리의 README.md 참고.
"""

from __future__ import annotations

import json
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Final

import discord
import requests
from dotenv import load_dotenv

LOG_FORMAT: Final[str] = (
    "%(asctime)s %(levelname)s %(name)s :: %(message)s"
)
INBOX_PATH: Final[Path] = Path(__file__).resolve().parent / "inbox.jsonl"
REQUEST_TIMEOUT_SECONDS: Final[int] = 10
MAX_TEXT_PREVIEW_LEN: Final[int] = 80

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, stream=sys.stdout)
logger = logging.getLogger("mobruji-discord-daemon")


def load_env() -> dict[str, str]:
    """필수 환경변수를 로드한다. 누락 시 즉시 종료한다."""
    load_dotenv(Path(__file__).resolve().parent / ".env")

    required = (
        "DISCORD_BOT_TOKEN",
        "ALLOWED_USER_IDS",
        "MOBRUJI_CHANNEL_ID",
        "GITHUB_PAT",
        "GITHUB_REPO",
    )
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        logger.error("필수 환경변수 누락: %s", ", ".join(missing))
        sys.exit(1)

    return {key: os.environ[key] for key in required}


def parse_allowed_user_ids(raw: str) -> set[int]:
    """CSV 형태 user id 목록을 정수 집합으로 변환한다."""
    ids: set[int] = set()
    for token in raw.split(","):
        token = token.strip()
        if not token:
            continue
        try:
            ids.add(int(token))
        except ValueError:
            logger.warning("ALLOWED_USER_IDS 토큰 무시 (정수 아님): %r", token)
    return ids


def truncate_for_log(text: str) -> str:
    """로그에 메시지를 남길 때 너무 길지 않도록 자른다."""
    if len(text) <= MAX_TEXT_PREVIEW_LEN:
        return text
    return text[:MAX_TEXT_PREVIEW_LEN] + "…"


def append_inbox(payload: dict[str, str]) -> None:
    """inbox.jsonl 에 한 줄 JSON 으로 append (백업/디버깅용)."""
    try:
        with INBOX_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")
    except OSError as exc:  # 디스크 문제는 데몬을 죽이지 않는다
        logger.warning("inbox.jsonl write 실패: %s", exc)


def dispatch_to_github(
    github_pat: str,
    github_repo: str,
    payload: dict[str, str],
) -> None:
    """GitHub repository_dispatch 호출."""
    url = f"https://api.github.com/repos/{github_repo}/dispatches"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {github_pat}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    body = {
        "event_type": "discord_message",
        "client_payload": payload,
    }
    try:
        response = requests.post(
            url,
            headers=headers,
            json=body,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
    except requests.RequestException as exc:
        logger.error("repository_dispatch 요청 실패: %s", exc)
        return

    if response.status_code >= 300:
        logger.error(
            "repository_dispatch 비정상 응답: %s %s",
            response.status_code,
            truncate_for_log(response.text),
        )
        return

    logger.info(
        "repository_dispatch 성공: repo=%s event=discord_message",
        github_repo,
    )


def build_client(env: dict[str, str]) -> discord.Client:
    """discord.py Client 를 셋업하고 핸들러를 바인딩한다."""
    intents = discord.Intents.default()
    intents.message_content = True
    client = discord.Client(intents=intents)

    allowed_user_ids = parse_allowed_user_ids(env["ALLOWED_USER_IDS"])
    try:
        target_channel_id = int(env["MOBRUJI_CHANNEL_ID"])
    except ValueError:
        logger.error(
            "MOBRUJI_CHANNEL_ID 가 정수 아님: %r", env["MOBRUJI_CHANNEL_ID"]
        )
        sys.exit(1)

    @client.event
    async def on_ready() -> None:  # noqa: D401  # discord.py 콜백 명
        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s allowed=%d",
            client.user,
            target_channel_id,
            len(allowed_user_ids),
        )

    @client.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot:
            return
        if message.channel.id != target_channel_id:
            return
        if message.author.id not in allowed_user_ids:
            logger.info(
                "허용되지 않은 사용자 무시: user_id=%s", message.author.id
            )
            return

        ts_iso = message.created_at.astimezone(timezone.utc).isoformat()
        payload = {
            "text": message.content or "",
            "author": str(message.author.id),
            "author_name": message.author.name,
            "ts": ts_iso,
            "message_id": str(message.id),
            "channel_id": str(message.channel.id),
        }

        logger.info(
            "메시지 수신: author=%s ts=%s preview=%r",
            payload["author"],
            payload["ts"],
            truncate_for_log(payload["text"]),
        )
        append_inbox(payload)
        dispatch_to_github(env["GITHUB_PAT"], env["GITHUB_REPO"], payload)

    return client


def main() -> None:
    env = load_env()
    client = build_client(env)
    logger.info("Discord daemon 시작 (received_at=%s)", datetime.now(timezone.utc).isoformat())
    client.run(env["DISCORD_BOT_TOKEN"], log_handler=None)


if __name__ == "__main__":
    main()
