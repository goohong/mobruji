# 모부르지 (mobruji)

> 노래방에서 **"뭐 부르지?"** 할 때 답을 주는 곡 추천 서비스.
> 음역대 · 성별 · 분위기를 입력하면 부르기 좋은 곡을 추천합니다.

## Status

🚧 초기 셋업 단계. 실제 추천 로직·데이터 소스·UI 모두 미구현.

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
│   ├── ai-harness/   # AI 협업 규칙
│   ├── decisions/    # ADR
│   └── features/     # Feature Spec
├── docker-compose.yml
└── CLAUDE.md         # AI 세션 시작 시 자동 로딩되는 레포 룰
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
