# 모부르지 (mobruji)

> 노래방에서 **"뭐 부르지?"** 할 때 답을 주는 곡 추천 서비스.
> 음역대 · 성별 · 분위기를 입력하면 부르기 좋은 곡을 추천합니다.

동시에 이 레포는 **자율 멀티에이전트 AI 개발 하네스 실험장**입니다. 구현은 AI 에이전트가 맡고, 아키텍처·품질 기준·운영 규칙은 사람이 정의합니다. 자세한 내용은 아래 [AI 멀티에이전트 개발 하네스](#ai-멀티에이전트-개발-하네스) 섹션을 보세요.

## Status

🚧 개발 진행 중 — v0.8.0 릴리스, 커밋 1,000+. 서비스와 개발 하네스를 함께 발전시키고 있습니다.

## AI 멀티에이전트 개발 하네스

이 레포는 "무엇을 만드는가"만큼 "어떻게 만드는가"가 실험 대상입니다.

- **역할별 에이전트 분업** — 오케스트레이터가 백엔드(be)·프론트(fe)·리뷰(rev)·기획(plan) 역할의 에이전트를 git worktree 단위로 병렬 조율합니다. ([ADR-0014](./docs/decisions/0014-multi-agent-worktree-orchestration.md))
- **멀티 벤더 전제 설계** — Claude 외 에이전트(Codex 등)도 같은 룰로 진입할 수 있도록 진입점을 분리했습니다([CLAUDE.md](./CLAUDE.md) · [AGENTS.md](./AGENTS.md)). 한 브랜치 = 한 에이전트, AI 작성 PR은 라벨과 커밋 trailer로 식별합니다. 현재는 Claude 단일 벤더로 운영합니다.
- **품질 게이트** — AI가 만든 단일 PR에서 사람 리뷰가 계층 위반 8건을 놓친 경험을 계기로, 계층 의존 방향을 [ADR-0008](./docs/decisions/0008-archunit-layer-verification.md)로 정의하고 ArchUnit 테스트([LayerDependencyTest](./backend/src/test/java/com/mobruji/architecture/LayerDependencyTest.java))로 병합 전에 차단합니다. checkstyle · spotless · test와 함께 빌드 게이트로 강제됩니다.
- **결정은 ADR로** — 아키텍처와 하네스 운영 결정을 [ADR 29건](./docs/decisions/)으로 기록합니다.
- **하네스 문서** — 워크플로 · 품질 게이트 · 보안 정책 · 멀티세션 런북을 [docs/ai-harness/](./docs/ai-harness/00-index.md)에 정리했습니다.

이 하네스는 후속 프로젝트 [도취(dochi)](https://github.com/goohong/dochi)로 이식되어 두 번째 실험이 진행되었습니다.

## Stack

- **Backend**: Spring Boot 3.5.12 / Java 21 / Gradle
- **Frontend**: Next.js (App Router) / TypeScript / Tailwind
- **DB**: MySQL 8.4 (로컬 docker-compose)
- **License**: AGPL-3.0-or-later

## Monorepo 구조

```
mobruji/
├── backend/          # Spring Boot
├── web/              # Next.js
├── docs/
│   ├── ai-harness/   # AI 협업 규칙 (하네스)
│   ├── decisions/    # ADR
│   └── features/     # Feature Spec
├── docker-compose.yml
├── CLAUDE.md         # Claude 진입점 — 레포 룰
└── AGENTS.md         # Claude 외 AI 에이전트(Codex 등) 진입점
```

## 로컬 개발

### Backend

```bash
# 로컬 MySQL 기동
docker compose up -d

# 백엔드 실행 (local 프로필)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'

# 품질 게이트
./gradlew checkstyleMain spotlessCheck test
```

### Frontend

```bash
cd web && npm install && npm run dev
```

## 기여 / AI 작업 규칙

- 모든 변경은 PR로. `develop` 브랜치 분기 → squash merge.
- 상세 룰: [`CLAUDE.md`](./CLAUDE.md), [`docs/ai-harness/`](./docs/ai-harness/00-index.md)
- 기능 작업 전: [`docs/features/`](./docs/features/README.md) Feature Spec 작성
- 횡단 결정: [`docs/decisions/`](./docs/decisions/) ADR로 기록

## License

[GNU Affero General Public License v3.0 or later](./LICENSE) — 클론 서비스로 운영 시 소스 공개 의무.
