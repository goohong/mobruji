# Sub-Agent Prompt Template

> Maestro(`nmae`)가 서브 에이전트(BE/FE/Rev/Plan)를 launch할 때 주입하는 공통 지침입니다.
> **단일 진실 원칙**: 모든 서브 에이전트는 `docs/ai-harness/00-MANIFEST.md`의 라우팅 지도를 따릅니다.

## 1. 서브 에이전트 호출 표준 (Maestro 전용)

Maestro는 에이전트를 가동할 때 아래 한 줄을 반드시 포함해야 합니다:

```
공통 프로토콜은 docs/ai-harness/01-GOVERNANCE.md를, 기술 표준은 03-ENGINEERING_STANDARDS.md를 따릅니다. 역할은 <BE|FE|Rev|Plan> 입니다.
```

## 2. 역할별 컨텍스트 주입 가이드

Maestro는 에이전트의 역할에 따라 다음 볼륨을 추가로 읽게 합니다:

- **Plan**: `02-ARCHITECT_NOTE.md` (도메인 및 설계 지식)
- **BE**: `02-ARCHITECT_NOTE.md` (엔티티 및 BC 참조용)
- **FE/Rev**: 해당 역할에 특화된 `03-ENGINEERING_STANDARDS.md` 섹션 강조

## 3. 공통 핵심 수칙 (모든 에이전트)

1. **상태 기록 의무**: 매 turn 시작 시 `tools/cycle-status/update.sh <role> set-in-progress --title '...'`를 실행하고, 작업 완료 시 `set-idle`을 실행한다. (nmae와 사용자가 실시간으로 인지하게 하기 위함)
2. **워크트리 격리**: 지정된 경로(`/home/mobruji/mobruji-<role>`) 외의 다른 세션 영역을 절대 수정하지 않는다.
2. **5분 룰**: 단일 턴의 작업이 5분을 넘지 않도록 단위를 쪼개어 수행한다.
3. **메모리 보호**: `~/.claude/projects/*/memory/` 쓰기는 Maestro만 수행하며, 서브 에이전트는 보고서에 '메모리 후보'만 남긴다.
4. **라벨링**: PR 생성 즉시 `type`, `scope`, `ai-generated`, `session` 라벨을 부착한다. 보호 영역 변경 시 `needs-human-review`를 부착한다.
5. **보고**: 작업 종료 시 `01-GOVERNANCE.md`의 표준 양식(🔴/🟡/🟢)에 따라 요약 보고한다.

## 4. 안티패턴 (절대 금지)
- **시크릿 노출**: 로그나 PR 본문에 raw 토큰/키를 박는 행위.
- **도메인 침범**: BE 에이전트가 FE 코드를, 혹은 그 반대로 수정하는 행위.
- **hook 우회**: `git push --no-verify` 등으로 검증 프로세스를 건너뛰는 행위.
- **자의적 해석**: 하네스에 명시된 비기능 요구사항(결정성, PII 마스킹 등)을 "효율성"을 이유로 누락하는 행위.

---
**업데이트 이력**: 2026-05-27 — 모듈형 하네스 볼륨(00~03) 체제로 전면 개편.
