# AGENTS.md (mobruji)

> 이 파일은 OpenAI Codex CLI 등 Claude 외 AI 에이전트의 진입점이다.
> Claude는 별도로 `CLAUDE.md`를 자동 로딩한다.

## 1) 먼저 읽을 것

**모든 비협상 룰은 `CLAUDE.md`에 있다. Codex도 이 룰을 따른다.**

- [CLAUDE.md](./CLAUDE.md) — 프로젝트 룰 요약 + 비협상 룰 + 안티패턴
- [docs/ai-harness/](./docs/ai-harness/00-index.md) — 상세 문서 인덱스
- 프론트엔드 작업 시 추가: [web/AGENTS.md](./web/AGENTS.md) — Next.js 최신 버전 관련 주의

## 2) Codex 식별

이 레포에서 Codex가 작성한 PR은 다음을 반드시 갖춘다:

- **커밋 trailer**: `Co-Authored-By: OpenAI Codex <noreply@openai.com>`
- **라벨**: `ai-generated` + `ai:codex` 둘 다
- **PR 본문**: "AI 작업 기록" 섹션에 사용 모델과 지시문 요약

상세: `docs/ai-harness/02-agent-workflow.md §10 다중 AI 에이전트 운영`.

## 3) Claude와 분담 (1차 권장)

- **백엔드(Spring Boot, Java)** — 주로 Claude
- **프론트엔드(Next.js, TypeScript)** — 주로 Codex
- 풀스택 변경은 두 PR로 분리(한 PR = 한 에이전트).

> 사용자가 명시적으로 분담을 변경하면 그 지시를 우선한다.

## 4) 한 브랜치 = 한 에이전트

- 다른 에이전트의 브랜치에 직접 push 금지.
- 의견은 PR 코멘트로만 전달.
- 충돌은 사람 중재.

## 5) Codex-Specific 메모

- Codex의 자동 작성 한도/타임아웃에 맞게 PR 사이즈 조절. 가이드는 `docs/ai-harness/03-quality-gates.md §3-2`.
- `web/AGENTS.md`가 명시하듯 Next.js는 최신 버전이라 학습 시점 가정과 다를 수 있다. 코드 작성 전 `node_modules/next/dist/docs/` 확인.
- 모든 라벨/시크릿/정책은 Claude와 동일. 별도 우대 없음.
