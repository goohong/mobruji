# Release Prompt Template — 1-shot release PR 자동화

> maestro가 release cutoff 임계치 도달 시 **1번 호출**해서 release PR draft 까지 한 번에 생성하는 Bash command snippet.
> 후속 PR #774-2 산출물. 본 문서는 spec(`docs/features/release-cadence-v0.4.0.md`) + workflow(`docs/ai-harness/02-agent-workflow.md` §8) 의 **실행 layer**다.

## 1) 사용 시점
maestro 사이클 끝에 다음 조건 모두 충족 시 호출한다.

1. `git rev-list --count origin/main..origin/develop` ≥ 20 **OR** `type:fix` + `type:feat` 머지 카운트 ≥ 5
2. `release:hotfix` 라벨 PR 머지 (임계치 무시)
3. 사용자 명시 (`/release` 또는 "release ㄱㄱ")

판정 룰 상세는 `02-agent-workflow.md` §8-1-1 / `release-cadence-v0.4.0.md` §5-1 참조.

## 2) 1-shot snippet
복붙 가능한 단일 블록. cwd 무관(절대경로 처리). `REPO=/home/mobruji/mobruji` 가정.

```bash
#!/usr/bin/env bash
# release-prompt.sh — develop → main release PR 자동 생성
set -euo pipefail

REPO="${REPO:-/home/mobruji/mobruji}"
cd "$REPO"

# --- 1. fetch 최신화 ---
git fetch origin main develop --tags --quiet

# --- 2. 마지막 tag + 다음 version 산정 ---
LAST_TAG=$(git describe --tags --abbrev=0 origin/main)
LAST_VER="${LAST_TAG#v}"
IFS='.' read -r MAJ MIN PAT <<< "$LAST_VER"

# 사용자 facing feat 카운트 (scope:web 또는 scope:recommendation 또는 scope:song 또는 scope:user 또는 scope:voice)
FEAT_USER=$(git log --oneline "origin/main..origin/develop" \
  | grep -cE "^[a-f0-9]+ feat\((web|recommendation|song|user|voice)\)" || true)

# breaking change 감지 (커밋 메시지에 ! 또는 BREAKING CHANGE)
BREAKING=$(git log "origin/main..origin/develop" --format=%B \
  | grep -cE "(^[a-z]+!\(|BREAKING CHANGE:)" || true)

if [ "$BREAKING" -gt 0 ]; then
  NEXT_VER="$((MAJ + 1)).0.0"
elif [ "$FEAT_USER" -gt 0 ]; then
  NEXT_VER="${MAJ}.$((MIN + 1)).0"
else
  NEXT_VER="${MAJ}.${MIN}.$((PAT + 1))"
fi
echo "[release-prompt] last=${LAST_TAG} next=v${NEXT_VER} (feat_user=${FEAT_USER} breaking=${BREAKING})"

# --- 3. type별 grouping (PR squash 커밋 패턴: 'type(scope): 제목 (#N)') ---
declare -A GROUPS=(
  [feat]="신규 기능"
  [fix]="버그 수정"
  [docs]="문서"
  [test]="테스트"
  [refactor]="리팩터"
  [chore]="잡무"
  [cleanup]="정리"
  [style]="스타일"
)

# 사용자 영향 / 운영 영향 자동 분류 (scope 기준)
#   user-facing : scope ∈ {web, recommendation, song, user, voice}
#   ops-facing  : scope ∈ {infra}
RAW_LOG=$(git log --oneline "origin/main..origin/develop")

format_section() {
  local label="$1" type="$2" scope_filter="$3"
  local lines
  # NOTE: alternation 그룹은 반드시 한 번 더 () 로 감싸야 한다.
  # `feat\(web|recommendation\)` 처럼 쓰면 precedence 가
  # `feat\(web` OR `recommendation` 으로 갈라져 무관 커밋도 매칭됨.
  lines=$(echo "$RAW_LOG" | grep -E "^[a-f0-9]+ ${type}\((${scope_filter})\)" || true)
  [ -z "$lines" ] && return
  echo "### ${label}"
  echo "$lines" | sed -E 's|^[a-f0-9]+ |- |'
  echo ""
}

USER_FACING_SCOPES="web|recommendation|song|user|voice"
OPS_FACING_SCOPES="infra"

# --- 4. body 조립 ---
BODY=$(cat <<EOF
## Summary
- \`develop\` → \`main\` release (${LAST_TAG} → v${NEXT_VER})
- ahead: $(git rev-list --count origin/main..origin/develop) commits
- 사용자 facing feat: ${FEAT_USER} 건 / breaking: ${BREAKING} 건

## 사용자 영향 (scope: web / recommendation / song / user / voice)

$(format_section "신규 기능 (feat)" "feat"  "${USER_FACING_SCOPES}")
$(format_section "버그 수정 (fix)"  "fix"   "${USER_FACING_SCOPES}")
$(format_section "테스트 (test)"     "test"  "${USER_FACING_SCOPES}")
$(format_section "정리 (cleanup)"    "cleanup" "${USER_FACING_SCOPES}")

## 운영 영향 (scope: infra)

$(format_section "신규 기능 (feat)" "feat" "${OPS_FACING_SCOPES}")
$(format_section "버그 수정 (fix)"  "fix"  "${OPS_FACING_SCOPES}")
$(format_section "문서 (docs)"      "docs" "${OPS_FACING_SCOPES}")
$(format_section "테스트 (test)"    "test" "${OPS_FACING_SCOPES}")

## Release checklist (AI 체크리스트)
- [ ] 포함 PR 모두 \`reviewed:claude\` 라벨 부여 확인 (rev gate)
- [ ] CI green (backend + web)
- [ ] \`docs/ai-harness/02-agent-workflow.md\` §8-4 머지 방식: **Merge commit** (Squash 금지)
- [ ] 머지 후 tag \`v${NEXT_VER}\` + \`gh release create\`

🤖 Generated with release-prompt-template (#774-2)
EOF
)

# --- 5. PR 생성 ---
PR_URL=$(gh pr create \
  --base main \
  --head develop \
  --title "release: v${NEXT_VER}" \
  --body "$BODY" \
  --label "type:chore" \
  --label "scope:infra" \
  --label "ai-generated")

echo "[release-prompt] PR created: ${PR_URL}"
echo "${PR_URL}"
```

## 3) 동작 원리

| 단계 | 입력 | 출력 |
|---|---|---|
| 1. fetch | `origin/main`, `origin/develop`, tags | local refs 최신화 |
| 2. version | last tag, scope filter, BREAKING 감지 | `vX.Y.Z` 자동 산정 (patch/minor/major) |
| 3. grouping | `git log --oneline` + `grep type(scope)` | type×scope 매트릭스 |
| 4. body | grouping 결과 | markdown body (사용자/운영 분리) |
| 5. PR | `gh pr create` | PR URL stdout |

### 3-1) version 산정 룰 (`02-agent-workflow.md` §8-2-1 박제본)
- **major (X+1.0.0)**: 커밋 메시지에 `type!(scope):` 또는 `BREAKING CHANGE:` 트레일러 존재
- **minor (X.Y+1.0)**: 사용자 facing feat 1 건 이상 (scope ∈ web/recommendation/song/user/voice)
- **patch (X.Y.Z+1)**: 그 외 (fix/docs/test/refactor/chore/cleanup 만)

> infra-only feat 은 patch로 분류한다. 사용자가 체감하지 않는 운영 개선이므로.

### 3-2) scope → 영향 분류 매핑
| scope | 영향 |
|---|---|
| `web`, `recommendation`, `song`, `user`, `voice` | 사용자 영향 |
| `infra` | 운영 영향 |
| 그 외 (신규 scope) | spec 갱신 후 표 추가 |

## 4) 사용 예시

```bash
# maestro가 release cutoff 도달 후 1번 호출
bash docs/ai-harness/release-prompt-template.md   # (snippet 추출 후 실행)

# 또는 inline:
REPO=/home/mobruji/mobruji bash -c "$(awk '/^```bash$/,/^```$/' docs/ai-harness/release-prompt-template.md | sed '/^```/d')"
```

> 운영 단계에서는 별도 `scripts/release-prompt.sh` 로 추출 권장 (별도 PR).

## 5) 검증 결과 (v0.4.0 실측 — rev dry-run 2026-05-23)

rev sub-agent 가 PR #781 머지 후 실제 snippet 을 dry-run 실행한 결과 (`develop=ebef296`, main=`v0.3.3`).

### 5-1) 입력
```
$ git rev-list --count origin/main..origin/develop
38

$ git log --oneline origin/main..origin/develop | grep -oE '^[a-f0-9]+ [a-z]+\(' | grep -oE '[a-z]+\(' | sort | uniq -c
      1 cleanup(
     12 docs(
      5 feat(
     12 fix(
      7 test(
```

### 5-2) version 산정
- `feat(web)` 1건 (#784 requestId UUID) = 사용자 facing feat → **minor 자동 승격**
- 나머지 feat 4건 = `feat(infra)` (#733 #735 #737 #776) → 운영 영향
- breaking 0건
- snippet 기본 출력: **v0.4.0** (자동 minor)

> 사용자 facing scope (web/recommendation/song/user/voice) 의 feat 1건만 있어도 자동 minor 승격된다.

### 5-3) body 길이 (실측)
- 사용자 영향 섹션: feat 1 + fix 6 + test 6 + cleanup 1 = 14 줄
- 운영 영향 섹션: feat 4 + fix 6 + docs 9 + test 1 = 20 줄
- 총: 약 49 줄 (header 포함, checklist 별도). 사용자가 1분 내 훑기 가능.

### 5-4) PR #781 머지 후 발견된 버그 (rev dry-run 적발 → 본 PR 동시 fix)
- **`format_section()` grep alternation precedence 버그**: `grep -E "^[a-f0-9]+ feat\(web|recommendation|song|user|voice\)"` 패턴이 alternation precedence 때문에 `feat\(web` OR `recommendation` OR ... 로 갈라져 무관 커밋 (예: `docs(recommendation): …`) 까지 매번 모든 type 섹션에 중복 매칭됨.
- 수정: scope_filter 를 `\((${scope_filter})\)` 로 한 번 더 group 으로 감싸 precedence 고정.
- FEAT_USER 카운트는 원래부터 `feat\((web|...)\)` 로 group 되어 있어 v0.4.0 산정은 정상 동작.

### 5-4) maestro 인지 비용 비교
| 항목 | Before (수동) | After (template) |
|---|---|---|
| changelog 작성 시간 | rev 위임 ~10분 | 0 (자동) |
| version 산정 | maestro 판단 | 자동 (override 가능) |
| body markdown | 수동 작성 | 자동 grouping |
| PR 라벨 부여 | 별도 step | 한 명령 안에 포함 |
| maestro 명령 수 | 4-5 | **1** |

## 6) 한계 / 후속

- **사용자 facing 판정**: 현재 scope 화이트리스트로만 판정. PR 본문의 "AS-IS/TO-BE" 까지 보지는 않음. 오버라이드는 사용자 1줄.
- **release notes 외부 공개**: 본 template 는 release PR body 까지만 생성. `gh release create --notes` 의 노출용 별도 압축 버전은 후속 PR(#774-3 또는 release.yml 신설).
- **hotfix 분기**: `release:hotfix` 라벨 감지는 본 snippet 에 없음. spec §5-2 의 hotfix 정책 박제 후 추가.
- **PR draft 모드**: 현재 즉시 ready PR. draft 로 만들고 싶으면 `gh pr create --draft` 추가.

## 7) 참고 문서
- `docs/features/release-cadence-v0.4.0.md` — cadence + label 정책 spec (PR #774)
- `docs/ai-harness/02-agent-workflow.md` §8 — release workflow 박제 (PR #777)
- `docs/ai-harness/12-sub-agent-prompt-template.md` — maestro prompt template 후속 통합 지점 (PR #774-3 예정)
