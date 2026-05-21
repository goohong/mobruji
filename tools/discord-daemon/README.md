# mobruji discord-daemon (옵션 A — macOS LaunchAgent)

`docs/features/discord-daemon-hosting.md` §3-A 구현체. 사용자 Mac 이
켜져 있는 동안 Discord Gateway 에 24/7 붙어, 지정 채널의 화이트리스트
사용자 메시지를 GitHub `repository_dispatch` 로 전달한다.

## 구성 파일

| 파일 | 역할 |
|---|---|
| `bot.py` | discord.py 기반 Gateway 데몬 |
| `requirements.txt` | discord.py / python-dotenv / requests |
| `.env.example` | 환경변수 템플릿 (실제 값은 `.env` 에 채움) |
| `com.mobruji.discord-daemon.plist` | LaunchAgent 템플릿 (sed 치환 대상) |
| `setup-launchagent.sh` | venv·.env·plist·launchctl 한 번에 처리 |

## 사전 준비

1. Discord Developer Portal → New Application → Bot 생성 → **Privileged Gateway Intents → MESSAGE CONTENT INTENT 활성화**
2. OAuth2 → URL Generator: scope=`bot`, permissions=`Read Messages/View Channels`, `Send Messages` 정도면 충분. 생성된 URL 로 봇을 mobruji 서버에 초대.
3. GitHub PAT 발급 (Classic 권장). 스코프: **`repo` (private repo 라면 필수)**. `repository_dispatch` 호출에는 `repo` 가 필요하다.
4. mobruji 채널 id 확인 (현재: `1506925497651560458`). 본인 Discord user id 확인.

## 셋업 절차

```bash
cd tools/discord-daemon
bash setup-launchagent.sh
# 이후 .env 의 DISCORD_BOT_TOKEN / GITHUB_PAT 채우고
bash setup-launchagent.sh    # 다시 실행하면 자동 reload
```

`setup-launchagent.sh` 가 하는 일:

- `tools/discord-daemon/venv` 생성 + `pip install -r requirements.txt`
- `.env` 가 없으면 `.env.example` 복사 (chmod 600)
- `com.mobruji.discord-daemon.plist` 토큰 치환 후 `~/Library/LaunchAgents/` 로 복사
- `launchctl bootstrap gui/$(id -u)` 로 등록 + `kickstart` 로 즉시 기동

## 로그

```bash
tail -F ~/Library/Logs/mobruji-discord-daemon.out.log \
       ~/Library/Logs/mobruji-discord-daemon.err.log
```

로컬 디버깅 시 inbox 백업:

```bash
tail -F tools/discord-daemon/inbox.jsonl
```

## 절전 / sleep 가이드

LaunchAgent 는 사용자 세션이 sleep 들면 함께 멈춘다. 24/7 유지하려면
다음 중 하나가 필요하다.

```bash
# A. 시스템 sleep 영구 차단 (디스플레이는 꺼짐, 권장)
sudo pmset -a disablesleep 1

# B. 임시 (터미널 세션 살아있는 동안만)
caffeinate -dimsu &

# 되돌리기
sudo pmset -a disablesleep 0
```

전원 어댑터 연결 상태에서만 sleep 을 차단하고 싶으면 `pmset -c` 로
프로필 분리.

## 동작 확인

1. Discord 모바일/데스크탑에서 mobruji 채널에 메시지 입력
2. 로그에 `메시지 수신` + `repository_dispatch 성공` 라인 확인
3. GitHub Actions 의 `discord-dispatch-handler` workflow run 확인

## Troubleshooting

| 증상 | 원인 / 조치 |
|---|---|
| `Privileged intent` 오류 | Developer Portal 에서 MESSAGE CONTENT INTENT 활성화 |
| `401 Unauthorized` (Discord) | 봇 토큰 재발급 후 `.env` 갱신 + `setup-launchagent.sh` 재실행 |
| `403` (repository_dispatch) | PAT 스코프 부족 (private repo 면 `repo` 필요) 또는 토큰 만료 |
| `404` (repository_dispatch) | `GITHUB_REPO` 값 오타 또는 repo 접근 권한 없음 |
| 메시지가 무시됨 | `MOBRUJI_CHANNEL_ID` 또는 `ALLOWED_USER_IDS` 불일치. 로그에 `허용되지 않은 사용자` 또는 채널 미스매치 라인 확인 |
| 데몬이 자꾸 죽음 | `ThrottleInterval=30` 으로 30초 간격 재시작. `*.err.log` 의 traceback 확인 |
| 채널 id 가 바뀜 | `.env` 의 `MOBRUJI_CHANNEL_ID` 수정 → `setup-launchagent.sh` 재실행 |

## 운영자 체크리스트

- [ ] `.env` 가 `git status` 에 안 잡힌다 (.gitignore 의 `.env` 룰)
- [ ] PAT 은 별도 토큰 분리 (Q2 결정). 만료일 캘린더 등록.
- [ ] 정기적으로 `~/Library/Logs/mobruji-discord-daemon.err.log` 사이즈 점검 (rotate 필요시 별도 launchd)

## 중지 / 제거

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.mobruji.discord-daemon.plist
rm ~/Library/LaunchAgents/com.mobruji.discord-daemon.plist
rm -rf tools/discord-daemon/venv tools/discord-daemon/.env
```

## 관련

- spec: `docs/features/discord-daemon-hosting.md`
- 워크플로우: `.github/workflows/discord-dispatch-handler.yml`
- 후속 옵션 C (Cloudflare Workers): spec §6 PR 3
