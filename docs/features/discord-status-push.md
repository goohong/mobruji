---
feature: Discord 상태 push 룰 (maestro 사이클 트레일)
slug: discord-status-push
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [352]
related_prs: [352, 353]
last_reviewed: 2026-05-24
---

# Discord 상태 push 룰

## 1) 개요 (What / Why)
maestro 오케스트레이션이 자율 사이클로 돌 때, 사용자(특히 mac 또는 모바일)는 "지금 maestro가 어디까지 갔는가"를 GitHub events로만 추적할 수 있다. 현재 두 흐름이 깔려 있다:

- `.github/workflows/discord-notify.yml` — PR/issue/release 이벤트를 #모부르지 채널에 즉시 push.
- `.github/workflows/discord-periodic-summary.yml` — 6h cron 다이제스트.

이 두 워크플로우는 **GitHub events**만 잡는다. maestro 본인의 사이클 단계 전환 — 사이클 launch / 결정 분기 / 오류 — 은 GitHub event로 expose하지 않으면 채널에 안 떨어진다. 이 spec은 그 누락 구간을 메우는 **maestro 행동 룰**을 정형화한다.

핵심 원칙: **maestro의 사이클 트레일은 GitHub events로 expose해서 기존 webhook 흐름을 재사용한다.** Discord webhook URL을 NCP에 별도로 두고 maestro가 직접 curl 호출하는 패턴은 secret 관리 비용이 커서 피한다.

## 2) 사용자 시나리오
- 사용자(모바일)는 외출 중. maestro는 NCP에서 자율 사이클 가동 중.
- maestro가 `be 사이클 6` launch → 이슈 생성 → `discord-notify.yml`이 ⚪ `[issue opened] #N ...` push.
- be sub-agent가 PR 생성 → 🟦 `[opened] #N feat(be): ...` push.
- 사용자는 모바일 Discord 알림을 보고 "be 사이클이 PR 단계까지 왔구나" 인지.
- maestro가 결정 분기 (스펙 충돌) → 이슈 코멘트로 결정 묶음 등록 → maestro는 이슈 자체에 라벨 `decision:pending` 부여 → label 이벤트는 webhook 안 잡히지만, 6h 다이제스트에는 노출.
- 6h 후 다이제스트가 채널에 📊 카드 1장으로 push → 사용자는 다시 확인.

## 3) 요구사항
### 기능 요구사항
- [ ] **사이클 launch**: 새 사이클을 시작할 때 maestro는 GitHub 이슈를 생성한다 (사이클 1개 = 이슈 1개 1:1). 라벨 `task` + `type:*` + `scope:*` 필수. → `issues: opened` 이벤트가 자동 push.
- [ ] **PR open/ready/머지/close**: sub-agent가 PR을 만들거나 maestro가 직접 만들 때 — 자동으로 webhook 발사. 별도 룰 없음.
- [ ] **오류 / blocker**: sub-agent stall, CI 실패, 외부 의존 장애 발생 시 maestro는 즉시 새 이슈를 등록 (`type:bug` 또는 `type:chore`, 본문에 trace) → 자동 push. 사이클 이슈 코멘트로만 남기지 않는다 (코멘트는 webhook X).
- [ ] **결정 분기**: 사용자 결정이 필요한 분기는 (a) 사이클 이슈에 라벨 `decision:pending` 부여 + (b) maestro task list에 묶음 질문 누적. 다음 6h 다이제스트에서 가시화.
- [ ] **release**: `gh release create` 자동 webhook (이미 동작).

### 비기능 요구사항
- **결정성**: 룰을 따르면 maestro 사이클 트레일이 100% Discord 채널에 expose되어야 한다.
- **노이즈 ≤ 채널 가독성**: 이슈/PR로 expose하지 않을 사이클 단계 (예: sub-agent 가 hour 단위로 push하는 중간 ping) 는 push하지 않는다. 사용자가 "다음 발걸음"을 알 수 있는 단위만 push.
- **secret 노출 금지**: webhook URL은 GitHub repo secret `DISCORD_WEBHOOK_URL`로만. maestro/NCP `.env` 에 webhook URL 별도 보유 금지.
- **관측성**: webhook 실패 시 `gh run list --workflow=discord-notify.yml --status=failure` 로 추적 가능해야 한다.

## 4) 범위 / 비범위
### 포함
- maestro의 사이클 트레일이 GitHub events로 expose되는 룰.
- 기존 `discord-notify.yml` + `discord-periodic-summary.yml` 활용.

### 제외 (Out of Scope)
- **양방향 (Discord → maestro)**: `docs/features/discord-driven-mobruji.md` (#338) 가 담당.
- **/status 슬래시 명령**: 별도 spec/PR (#353).
- **NCP에서 maestro가 직접 webhook curl**: secret 관리 비용 + 이중 SoT 위험. 채택하지 않음.
- **per-event 라벨 필터링**: 노이즈가 크면 후속 ADR로 다룬다.

## 5) 설계
### 5-1) 데이터 흐름
```
maestro reasoning
  ├─ 사이클 launch ─────→ gh issue create ─→ GitHub issues:opened ─→ discord-notify.yml ─→ #모부르지
  ├─ sub-agent PR ─────→ (sub-agent가 gh pr create) ─→ pull_request:opened ─→ same workflow ─→ #모부르지
  ├─ 오류 발생 ─────────→ gh issue create (type:bug) ─→ same flow ─→ #모부르지
  ├─ 결정 분기 ─────────→ gh issue edit --add-label decision:pending ─→ (label 이벤트는 push X)
  │                                                                  └→ 6h 후 discord-periodic-summary.yml 다이제스트
  └─ release ──────────→ gh release create ─→ release:published ─→ same workflow ─→ #모부르지
```

### 5-2) GitHub events 매핑 표
| maestro 단계 | GitHub event | 라벨/조건 | 알림 시점 |
|---|---|---|---|
| 사이클 launch | `issues: opened` | `task` + `type:*` + `scope:*` | 즉시 |
| sub-agent PR open | `pull_request: opened` | (Draft 포함) | 즉시 |
| PR ready for review | `pull_request: ready_for_review` | — | 즉시 |
| PR merged | `pull_request: closed` (`merged=true`) | — | 즉시 |
| PR closed (unmerged) | `pull_request: closed` (`merged=false`) | — | 즉시 |
| 오류 / blocker | `issues: opened` | `type:bug` 또는 `type:chore` | 즉시 |
| 결정 분기 | (event 없음) | `decision:pending` 라벨 부여 | 6h 다이제스트 |
| release | `release: published` | — | 즉시 |

### 5-3) decision:pending 라벨
- 새 라벨 신설 — 사용자 결정 대기를 가시화.
- 6h 다이제스트에 별도 섹션으로 expose하는 enhancement는 후속 PR (이 spec 머지 후 별도 이슈).
- 라벨 색상: 노란색 (#FBCA04), 설명: "사용자 결정 대기 — 다음 묶음 질문에 포함됨".

### 5-4) maestro 의무 체크리스트
maestro가 매 사이클 launch마다 자기 점검:
- [ ] 사이클 = 이슈 1개. 코멘트만으로 묶지 말 것.
- [ ] sub-agent stall 또는 CI 실패는 별도 `type:bug` 이슈로 분리.
- [ ] 사용자 결정 필요 시 사이클 이슈에 `decision:pending` 라벨.
- [ ] 묶음 질문은 maestro task list에 누적 — 다음 사용자 reply 또는 6h 다이제스트에서 일괄 노출.

## 6) 작업 분할 (PR 리스트)
- [x] PR: 본 spec 신설 + `11-multi-session-runbook.md` §추가 + `14-discord-ops.md` cross-reference + memory `feedback-discord-status-push`
- [ ] PR (후속, 옵션): `decision:pending` 라벨 GitHub repo에 신설 (gh label create)
- [ ] PR (후속, 옵션): `discord-periodic-summary.yml` 에 `decision:pending` 라벨 이슈 별도 섹션 추가

## 7) 테스트 전략
- 룰 spec이라 코드 테스트 없음.
- 검증: 본 PR 머지 후 첫 사이클을 룰대로 운영 → Discord 채널에 사이클 launch/PR open/머지/release가 모두 알림으로 떨어지는지 확인.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 결정 분기를 6h 다이제스트보다 빠르게 expose해야 하나? | (a) 6h로 충분 / (b) label 이벤트 webhook 신설 | @user / 후속 사이클 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 + 즉시 운영 적용 (status=approved). 사용자 위임 — 자율 사이클 트레일 가시화 정형화. 기존 `discord-notify.yml` + `discord-periodic-summary.yml` 재사용. maestro가 직접 webhook curl 호출하지 않는다 (secret 이중 보유 회피).
