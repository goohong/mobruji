# Discord Notify Setup

> 본진(Claude 세션)이 닫혀있을 때 사용자가 모바일 Discord 알림으로 사이클 진행을 모니터링하기 위한 셋업 가이드.
> workflow 본체는 `.github/workflows/discord-notify.yml`.

## 1) 왜 필요한가

본진 Claude 세션을 닫으면 background sub-agent도 모두 종료된다 (`docs/ai-harness/11-multi-session-runbook.md §0` 참조). 그래서 사용자가 외출 중일 때는 사이클 진행 상황을 알 방법이 없다.

단기 보완책으로 **GitHub Actions → Discord webhook → 모바일 push** 흐름을 깐다. 사용자는 핸드폰 Discord 알림으로 PR/이슈/릴리즈 이벤트를 받고, 필요하면 모바일에서 `gh` CLI 또는 GitHub 앱으로 명령을 내린다.

## 2) 알림 대상 이벤트

| 이벤트 | 트리거 | embed 표시 |
|---|---|---|
| PR opened | `pull_request: opened` | 🟦 `[opened] #N type(scope): 제목` + author |
| PR ready for review | `pull_request: ready_for_review` | 🟢 `[ready] #N ...` |
| PR merged | `pull_request: closed` + `merged=true` | ✅ `[merged] #N ...` |
| PR closed (unmerged) | `pull_request: closed` + `merged=false` | ❌ `[closed] #N ...` |
| issue opened | `issues: opened` | ⚪ `[issue opened] #N 제목` + labels |
| issue closed | `issues: closed` | ⚫ `[issue closed] #N 제목` |
| release published | `release: published` | 🚀 `release vX.Y.Z` + body 요약(1800자) |

## 3) 셋업 절차

### 3-1) Discord 채널 + webhook 생성

1. Discord 서버를 하나 만들거나 기존 서버 사용 (개인용이면 본인만 있는 서버 추천).
2. 알림 받을 채널 생성 (예: `#mobruji-bot`).
3. 채널 우측 톱니바퀴 → **Integrations → Webhooks → New Webhook**.
4. 이름/아이콘 설정 후 **Copy Webhook URL** 클릭. URL 형식:
   `https://discord.com/api/webhooks/<id>/<token>`
5. 모바일 Discord 앱 설치 + 해당 채널 알림 ON.

> URL이 곧 비밀이다. 채팅/스크린샷에 노출 금지. 노출되면 즉시 Webhook 삭제 후 재발급.

### 3-2) GitHub repo secret 등록

```bash
# repo root에서
gh secret set DISCORD_WEBHOOK_URL --repo goohong/mobruji
# 프롬프트에 URL 붙여넣고 엔터
```

또는 GitHub UI: **Settings → Secrets and variables → Actions → New repository secret**
- Name: `DISCORD_WEBHOOK_URL`
- Secret: 위에서 복사한 URL

### 3-3) 동작 확인

1. 테스트 이슈 생성:
   ```bash
   gh issue create --title "test: Discord notify 동작 확인" --body "테스트용. 닫아도 됨." --label "task,type:chore,scope:infra"
   ```
2. 모바일 Discord 채널에 `⚪ [issue opened] #N test: ...` 알림 도착 확인.
3. 이슈 close → `⚫ [issue closed]` 알림 도착 확인.
4. 둘 다 OK면 셋업 완료.

알림이 안 오면:
- `gh run list --workflow=discord-notify.yml` 으로 workflow 실행 여부 확인
- 실패한 run의 로그 확인 (`gh run view <run-id> --log-failed`)
- secret 이름 오타, webhook URL 만료, Discord 채널 알림 OFF 등 점검

## 4) graceful skip 동작

`DISCORD_WEBHOOK_URL` secret이 없을 때:
- workflow는 정상 trigger되어 job이 돌지만, 각 notify step의 `if`가 false가 되어 skip한다.
- 첫 step에서 "DISCORD_WEBHOOK_URL secret이 등록되어 있지 않습니다" 안내 echo만 남기고 success로 끝낸다.
- → 다른 PR/이슈에 영향 없음. 셋업 전에도 workflow 파일을 머지해 둘 수 있다.

## 5) 운영 시 유의

- **하드코딩 금지**: webhook URL은 secret으로만. `.github/workflows/`에 URL 박지 말 것 (보호 영역).
- **노이즈 관리**: 알림이 너무 많아지면 `on:` 트리거에서 빼거나 `if:` 조건으로 라벨/브랜치 필터링 추가. 예: `if: contains(github.event.pull_request.labels.*.name, 'needs-human-review')` 만 발송하도록 좁히기.
- **민감정보**: 이슈/PR 제목·본문에 secret/토큰/사용자 음역대 원문이 들어가지 않도록 주의 (`docs/ai-harness/04-security-policy.md`). 알림에 그대로 노출된다.
- **장애 시**: Discord webhook 자체 장애나 GitHub Actions 큐 지연 가능. 알림은 "best-effort 모니터링"이고 단일 SoT 아님. 진짜 상태는 GitHub에서 확인.

## 6) 한계와 다음 단계

이 워크플로우는 **단방향 push**다. 사용자가 모바일에서 명령을 내리려면 별도 채널 필요:
- GitHub 모바일 앱: 이슈/PR 코멘트, 머지, 라벨 조작 가능
- `gh` CLI (모바일 SSH/터미널 앱): 자동화 트리거 가능
- 양방향 봇 (Discord slash command → GitHub Actions dispatch): 향후 ADR/Feature Spec 필요

지금은 push만으로도 "사용자가 외출 중 사이클 진행을 인지하고 귀가 후 결정"하는 흐름이 가능하다.
