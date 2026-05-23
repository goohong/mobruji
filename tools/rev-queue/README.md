# tools/rev-queue

> rev sub-agent 가 매 사이클 첫 액션으로 호출하는 처리 큐 스크립트. **메모리/룰 학습 의존 X — GitHub 라벨 + 본 스크립트가 single source of truth.**

## 배경 (사용자 2026-05-24)

> "메모리는 계속 까먹으니까 ... 스크립트로 rev해야하는 목록을 관리한다던가 ... 물리적 방법이 필요"

기존 rev 룰 ([[feedback-rev-e2e-always]], `docs/features/rev-e2e-3-stages.md`) 은 라벨 기반이지만, rev sub-agent 가 매 사이클 어디서부터 처리할지 메모리에 의존. 학습 불안정 시 누락. 본 스크립트는 **물리적 큐** — 호출만 하면 처리 후보가 stage 별로 출력된다.

## 사용법

```bash
bash tools/rev-queue/rev-queue.sh stage1   # 머지 전 (reviewed:claude 없음)
bash tools/rev-queue/rev-queue.sh stage2   # 머지 1h+ (rev-post-merge-pass 없음)
bash tools/rev-queue/rev-queue.sh stage3   # 최근 release PR (rev-prod-pass 없음)
bash tools/rev-queue/rev-queue.sh all      # 세 stage 모두
```

환경변수:
- `REPO=<owner/name>` — default `goohong/mobruji`
- `GH_BIN=<path>` — default `gh` (테스트에서 mock 주입용)

의존: `gh` CLI 인증, bash 4+, GNU `date` (`date -u -d '1 hour ago'`).

## 3 stage 의미

| Stage | 대상 | 필터 라벨 (없으면 처리 큐) | 비고 |
|-------|------|---------------------------|------|
| **stage1** | open PR | `reviewed:claude` | 머지 전 단계 1 e2e — `docs/features/rev-e2e-3-stages.md §3-1` |
| **stage2** | merged PR (develop, 1h+) | `rev-post-merge-pass` | 사후 검증 단계 2 — `§3-2`. 1h 대기는 NCP dev deploy 사이클 완료 여유 |
| **stage3** | 최근 release tag 포함 PR | `rev-prod-pass` | production 검증 단계 3 — `§3-3` |

## 라벨 의미 (영속 truth)

| 라벨 | 의미 | 부착 시점 |
|------|------|----------|
| `reviewed:claude` | rev 단계 1 (머지 전) 통과 | PR 머지 전 — 머지 게이트 (CLAUDE.md §4 품질 게이트) |
| `rev-post-merge-pass` | rev 단계 2 (사후) 통과 | develop 머지 1h+ 후 e2e 재실행 통과 |
| `rev-prod-pass` | rev 단계 3 (production) 통과 | release 후 main deploy 환경 e2e 통과 |

## rev sub-agent 통합

rev sub-agent prompt (`docs/ai-harness/12-sub-agent-prompt-template.md §2 rev / §E-3`) 매 사이클 첫 액션:

```
1. bash /home/mobruji/mobruji/tools/rev-queue/rev-queue.sh all   # discovery (§E-3)
2. stage1 후보 ≥ 1 → 단계 1 e2e 수행 (§E-2.1 절차 — comment + reviewed:claude 라벨)
3. stage2 후보 ≥ 1 → 단계 2 사후 audit (§E-2.2 절차 — comment + rev-post-merge-pass 라벨)
4. stage3 후보 ≥ 1 → 단계 3 production 검증 (§E-2.3 절차 — comment + rev-prod-pass 라벨)
5. 모두 빈 큐 → ADR audit / 도메인 audit / cross-ref 정리 등 기존 작업 (§E-1)
```

처리 완료 시 PR 에 해당 라벨 부착 → **다음 호출에서 자동 제외**. 메모리 학습 불필요.

§E-2 (3단계 절차) 와 §E-3 (큐 discovery) 의 관계: §E-3 출력 → §E-2 절차 적용 → 라벨 → 다음 §E-3 호출에서 자동 제외.

## 자동화 잠재 확장 (별 PR 후보)

- cron 5분 마다 `stage1` 큐 길이 → Discord push (큐 ≥ 3 시 alert)
- nmae `cycle_idle_watch_loop` 와 통합: rev 워크트리 idle + 큐 ≥ 1 → 자동 sub-agent inject
- stage3 자동화: release tag push event → GitHub Action 으로 stage3 enqueue + rev 자동 launch

## 관계 메모리 / spec

- 메모리 [[feedback-rev-e2e-always]] — rev 가 모든 type:* PR 3단계 e2e 가능하면 항상 검사
- 메모리 [[feedback-nmae-cycle-watchdog]] — bot.py 5분 polling idle 워크트리 자동 정정
- 메모리 [[feedback-helper-role-boundary]] — helper 본체 ≠ nmae. rev 큐 처리는 rev sub-agent 만
- spec `docs/features/rev-e2e-3-stages.md` — 3단계 e2e 전체 spec
- spec `docs/features/rev-qa-protocol.md` — rev QA 프로토콜

## 테스트

```bash
bash tools/rev-queue/tests/test_rev_queue.sh
```

gh CLI mock 으로 stage1 빈/하나/여러 시나리오 + stage2 정상 시나리오 검증.
