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

## 6) 주기 요약 (Periodic Summary)

`discord-notify.yml`이 "즉시 이벤트 push"라면, `discord-periodic-summary.yml`은 **"자고 일어났을 때 한 눈에 보는 다이제스트"**다. 이벤트별 알림을 다 보지 못했어도 N시간치 누적이 한 카드에 정리된다.

### 6-1) 기본 동작
- 트리거: `schedule: cron '0 */6 * * *'` (UTC 기준 6시간 간격, KST 09/15/21/03시) + `workflow_dispatch`.
- 집계 항목 (마지막 6시간, 또는 `since_hours` input):
  - 🟢 머지된 PR (상위 5건 제목 + "외 N건")
  - ⚪ 신규 이슈
  - ✅ 닫힌 이슈
  - 🚀 published 된 release
- 활동 0건이어도 "조용한 사이클" 카드 1장을 보낸다 (정상적으로 조용함의 신호). 노이즈가 크면 후속 ADR에서 임계치 도입.

### 6-2) cron 간격 변경
`.github/workflows/discord-periodic-summary.yml`의 `cron` 값을 수정한다. 기억할 점:
- GitHub Actions cron은 **UTC** 기준이다. 한국 시간으로 매일 9시면 `0 0 * * *` (UTC 00:00 = KST 09:00).
- 5분보다 짧은 간격은 GitHub이 보장하지 않는다.
- cron을 변경하면 본 문서의 시간표 예시도 같이 갱신.

예시:
| 원하는 주기 | cron | 비고 |
|---|---|---|
| 6시간마다 (기본) | `0 */6 * * *` | KST 09/15/21/03 |
| 매일 아침 09시 KST | `0 0 * * *` | 자고 일어났을 때 1회 |
| 평일 출근/퇴근 | `0 0,9 * * 1-5` | KST 09/18 평일만 |

### 6-3) 수동 트리거 ("지금 요약 받기")
모바일/외출 중 즉시 요약이 필요하면:
```bash
# 기본 6시간치
gh workflow run discord-periodic-summary.yml --repo goohong/mobruji

# 임의 구간 (예: 지난 24시간)
gh workflow run discord-periodic-summary.yml --repo goohong/mobruji -f since_hours=24
```
GitHub 모바일 앱에서도 Actions → workflow → Run workflow로 동일 트리거 가능.

### 6-4) 시간대 (UTC vs KST)
- `cron`은 UTC, embed 본문 표시 구간은 KST로 변환해서 사람이 읽기 쉽게 보낸다.
- workflow 로그(`echo "window: ..."`)에도 KST로 같이 찍어 디버깅 편의 확보.

### 6-5) graceful skip
즉시 알림과 동일하게 `DISCORD_WEBHOOK_URL` 없으면 첫 step의 안내 echo만 남기고 모든 step이 skip된다. 셋업 전에 미리 머지해 둬도 안전.

## 7) mobruji 전용 채널 권장

### 7-1) 현재 상태 (2026-05-21)
- mobruji 본진은 사용자 ppiyaki와 **공유 Discord 채널**(channel ID `1492424075677532260`)을 사용 중이다.
- 같은 채널에 ppiyaki 개인 메시지가 섞여 들어와 noise/오작동 위험이 있다.
- `/discord:access` 정책상 본 채널은 mobruji 본진의 reply 권한이 있는 상태.

### 7-2) 권장: mobruji 전용 채널 신설
혼선을 줄이기 위해 **mobruji 전용 채널 1개**를 별도로 두는 구조로 전환한다.

| 항목 | 현재 | 권장 |
|---|---|---|
| 채널 분리 | ppiyaki와 공유 | mobruji 전용 (`#mobruji` 등) |
| message scope | 모든 발신자 메시지 처리 | 사용자 본인 메시지만 처리 |
| ppiyaki 메시지 | 동일 채널에서 섞임 | **무시 (다른 채널)** |
| webhook | 공유 webhook | mobruji 전용 webhook (옵션) |

### 7-3) 사용자 액션
1. Discord에서 **`#mobruji` 전용 채널**을 새로 만든다 (mobruji 본진 봇이 reply 권한을 가진 서버 내).
2. 새 채널 ID를 복사한다 (채널 우클릭 → "Copy Channel ID", Developer Mode 필요).
3. 본진 Claude 세션에 채널 ID를 전달한다 → 본진 메모리(`MEMORY.md`)에 `mobruji_discord_channel_id`로 등록.
4. 본진은 등록된 채널 ID와 일치하지 않는 채널의 메시지는 모두 **무시**(reply하지 않음)한다.
5. 기존 공유 채널(`1492424075677532260`)에서 사이클 push 알림(`discord-notify.yml` 등)을 받고 있었다면, webhook을 새 채널로 옮기거나 두 채널 모두에 발송하도록 선택한다.

### 7-4) 운영 룰 (전환 후)
- 본진 세션은 `mobruji_discord_channel_id`와 다른 chat_id로 도착한 메시지에 reply하지 않는다.
- 공유 채널에서 mobruji 본진을 호출하고 싶으면, ppiyaki가 메시지를 mobruji 채널로 다시 보낸다 (mention/copy).
- 채널 분리 후 `/discord:access`로 mobruji 전용 채널만 allowlist에 두는 정책도 함께 검토.

## 8) 한계와 다음 단계

이 워크플로우는 **단방향 push**다. 사용자가 모바일에서 명령을 내리려면 별도 채널 필요:
- GitHub 모바일 앱: 이슈/PR 코멘트, 머지, 라벨 조작 가능
- `gh` CLI (모바일 SSH/터미널 앱): 자동화 트리거 가능
- 양방향 봇 (Discord slash command → GitHub Actions dispatch): 향후 ADR/Feature Spec 필요

지금은 push만으로도 "사용자가 외출 중 사이클 진행을 인지하고 귀가 후 결정"하는 흐름이 가능하다.
