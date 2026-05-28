---
feature: cycle-backlog-and-auto-merge-hooks
slug: cycle-backlog-and-auto-merge-hooks
status: shipped
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-26
---

# Cycle Backlog Forum Thread + 자동 머지 + Stale Escalation Hook 5종 박제

## 1) 개요 (What / Why)

사용자 정정 (2026-05-26 12:24 KST):

> "근본 해결해줘. 관리가 안되면 머지 후에 rev 가 리뷰하지도 않을 거 같은데 forum 을 잘 활용해서 각 agent 가 자기 계획이 있어야지. 백로그 보면서 작업 안 까먹고 다 진행하고. 마무리하고 그렇게."

현상 박제:
- OPEN PR 12건 중 #1079 #1083 (rev pass + MERGEABLE) 머지 안 됨 → 방치.
- 모든 cycle channel (be/fe/rev/plan forum) 0 메시지 → 백로그 시스템 정착 X.
- #994 #1032 #1042 CONFLICTING 2일+ stale → rebase 책임 모호.
- nmae sub-agent thread silence → 사용자 가시성 X.

사용자 헌법: "중요 작업 = 봇/스크립트 강제, 컨벤션·말투 = 메모리". LLM 자율 / 학습 의존 = 굴복 패턴.

본 spec 은 위 4 사고에 대응하는 5 hook 의 박제 + 호출 contract:

| Hook | 위치 | 트리거 | 동작 |
|---|---|---|---|
| A1 | `.github/workflows/auto-merge-on-rev-pass.yml` | label/CI/cron 5min/manual | `reviewed:claude` + CLEAN PR squash merge |
| A2 | `.github/workflows/stale-pr-watch.yml` | cron 1h/manual | CONFLICTING 24h+ / MERGEABLE 6h 방치 → 라벨+코멘트 |
| B1 | `tools/discord-daemon/discord-reply.sh --cycle-backlog-upsert` | 외부 호출 | cycle forum 의 `[BACKLOG] <cycle>` thread upsert |
| B2 | `tools/cycle-backlog/upsert.sh` | nmae/sub-agent/wrapper | GitHub PR/issue → markdown 빌드 → B1 호출 |
| B3 | `tools/agent-launch-wrapper.sh --refresh-backlog` | nmae launch 직전 | B2 호출로 sub-agent 시작 직전 백로그 갱신 |
| C  | `.github/workflows/rev-stage2-post-merge.yml` | PR closed+merged (base=develop) | rev stage2 audit 큐 알림 코멘트 |

## 2) 사용자 시나리오

- **OPEN rev pass PR**: 라벨 부착 직후 (또는 5분 cron) `auto-merge-on-rev-pass.yml` 가 squash merge — 사용자 손 안 댐.
- **CONFLICTING 2일+ PR**: 1h cron 이 `stale:rebase-needed` 라벨 + 코멘트 `@author rebase 필요` 부착. 사용자 가시 → nmae 가 다음 사이클에서 잡음.
- **be sub-agent launch**: nmae 가 `tools/agent-launch-wrapper.sh be --title "..." --refresh-backlog` 호출 → wrapper 가 set-active + cycle 채널 push + backlog forum thread 갱신 → sub-agent 가 launch 즉시 자기 `[BACKLOG] be` thread 보고 계획 starting.
- **PR 머지 직후**: `rev-stage2-post-merge.yml` 가 코멘트 `📋 rev stage 2 audit 큐에 추가됨` 남김. 다음 rev 사이클이 `rev-queue.sh stage2` 호출로 잡음.

## 3) 요구사항

### 기능 요구사항
- [x] A1 `auto-merge-on-rev-pass.yml` — 5분 cron + label/check_suite 트리거. `reviewed:claude` + CLEAN + base=develop + 차단 라벨 없음.
- [x] A2 `stale-pr-watch.yml` — 1h cron. CONFLICTING 24h+ / MERGEABLE 6h+ 방치 / 7일 OPEN 별 라벨/코멘트.
- [x] B1 `discord-reply.sh --cycle-backlog-upsert <cycle> "<markdown>"` — forum thread upsert (`[BACKLOG] <cycle>`).
- [x] B2 `tools/cycle-backlog/upsert.sh <cycle>` — GitHub PR/issue → markdown 자동 빌드.
- [x] B3 `agent-launch-wrapper.sh --refresh-backlog` — launch 직전 backlog refresh (graceful).
- [x] C `rev-stage2-post-merge.yml` — PR merged 직후 코멘트 + summary.

### 비기능 요구사항
- 모든 workflow `workflow_dispatch` + `dry_run` input 지원 — 사용자 안전 검증 가능.
- A1/A2/C 본 workflow 파일 변경 = `.github/workflows/**` 보호 영역 → 신설 PR `needs-human-review` 라벨.
- B 그룹은 graceful — backlog upsert 실패가 Agent launch 차단 안 함.
- B2 markdown 본문 2000자 초과 시 잘라내고 `_…(잘림: 전체는 GitHub 에서)_` 표시.

## 4) 차단 라벨 / 예외

자동 머지 (A1) 차단 라벨 — 하나라도 있으면 skip:
- `rev:hold` — rev audit HOLD (author rebase 또는 nmae 결정 대기)
- `needs-human-review` — 보호 영역 변경 (사용자 사후 리뷰 필수)
- `scope:release` — release PR (사용자 수동 머지)
- `do-not-merge` — escape hatch

stale watch (A2) 라벨 — 중복 방지로 라벨 있으면 skip:
- `stale:rebase-needed` — CONFLICTING 24h+
- `stale:auto-merge-missed` — MERGEABLE + reviewed:claude 인데 6h+ 방치
- `stale:long-open` — 7일+ OPEN (사용자 결정 보존)

## 5) 호출 흐름 (nmae 측)

```bash
# nmae 가 sub-agent launch 시 — 학습 의존 없이 wrapper 한 줄.
bash /home/mobruji/mobruji/tools/agent-launch-wrapper.sh be \
  --title "songs/[id] 캐싱 추가" \
  --refresh-backlog \
  --echo-prompt "$(cat <<'EOF'
너는 be sub-agent. ...
EOF
)"
```

wrapper 가 자동 수행:
1. `cycle-status/update.sh be set-active --title "..."` (cycle-status.json 갱신)
2. (선택) `cycle-backlog/upsert.sh be` — `[BACKLOG] be` forum thread upsert.
3. `discord-reply.sh --cycle-channel be --auto-ack-thread "..."` — per-cycle 채널 launch 알림 + thread 생성.
4. stdout: `LAUNCH_THREAD_ID=<id>` + `CYCLE_CHANNEL_MSG_ID=<id>`.

nmae 는 `eval` 또는 `grep` 으로 thread_id 추출 → Agent tool prompt 에 inherit.

## 6) 검증

```bash
# A1 dry-run
gh workflow run auto-merge-on-rev-pass.yml -f dry_run=true

# A2 dry-run
gh workflow run stale-pr-watch.yml -f dry_run=true

# B2 dry-run (discord push X)
bash tools/cycle-backlog/upsert.sh be --dry-run
bash tools/cycle-backlog/upsert.sh fe --dry-run

# B3 wrapper dry-run (cycle push 도 skip)
AGENT_LAUNCH_NO_DISCORD=1 \
  bash tools/agent-launch-wrapper.sh be \
    --title "test" --refresh-backlog --no-cycle-push

# C: PR 머지 시 자동 트리거 — workflow_dispatch 로 강제 실행도 가능
gh workflow run rev-stage2-post-merge.yml
```

## 7) 회귀 가드

- A1 차단 라벨 ∪ `rev:hold` 매트릭스 (workflow 안 `blockLabels` 배열) — 변경 시 회귀 위험 → CODEOWNERS 알림 또는 PR scope:infra 라벨 enforce.
- B1 forum thread 검색 (`forum_find_active_thread_by_name`) — Discord API 변경 시 회귀 가능. fake-curl 테스트로 커버.
- B3 `--refresh-backlog` graceful 정책 — upsert 실패 시 wrapper exit 0 유지. 회귀 (exit non-zero) 시 모든 launch 차단.

## 8) 관련

- 메모리: [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-keep-4-cycles-active]]
  [[feedback-per-cycle-channel]] [[feedback-cycle-status-json]]
- 자매 spec: `docs/features/work-cycle-refactor.md` (5단계 마이그 중 단계 1-2 의 일부)
- 사용자 정정 인용: 2026-05-26 12:24 KST.
