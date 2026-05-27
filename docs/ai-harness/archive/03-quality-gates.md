# Quality Gates

## 1) 목표
- 품질 안정성을 기본값으로 두고, 실패를 조기에 차단한다.

## 2) 최소 필수 게이트

### Backend (`backend/`)
- `./gradlew build` 통과 필수
- `./gradlew test` 통과 필수
- `./gradlew checkstyleMain spotlessCheck` 통과 필수

### Frontend (`web/`)
- `npm run build` 통과 필수
- `npm run lint` 통과 필수
- `npm run typecheck` 통과 필수
- `npm test` 통과 필수 (테스트 셋업 후)

필수 게이트 미통과 PR은 머지 금지.

## 3) 권장 확장 게이트
- 정적 분석/포맷 검사 (backend: checkstyle, spotless / web: ESLint, Prettier)
- 아키텍처 규칙 검사 (DDD 레이어 침범 감지)
- 변경 영향 테스트(핵심 도메인 우선)
- 부하 테스트 게이트 (recommendation): `.github/workflows/load-test.yml` — k6로 p95/p99/TPS/에러율 회귀 감시. 임계 및 운영 가이드는 `scripts/load/README.md`.

## 3-1) 테스트 정책
- **단위 테스트**: 도메인 로직(서비스/엔티티 메서드)에 신규 코드를 추가하면 같은 PR에 단위 테스트를 동반한다.
- **통합 테스트**: Repository/Controller 등 외부 경계를 건드리는 변경은 통합 테스트를 권장한다.
- **테스트 생략 조건**: 설정/문서/스타일 변경, 또는 "테스트 불가 사유"를 PR 본문에 명시한 경우.
- **커버리지 기준**: 현재 정량 기준은 두지 않는다 (추후 jacoco 도입 시 도메인 패키지 70% 목표를 검토).
- **테스트 네이밍**: `메서드명_시나리오_기대결과` 또는 BDD 스타일(`given_when_then`) 중 일관 사용.

## 3-2) PR 사이즈 가이드
- 권장: 변경 +400 LOC 이내 (테스트/생성 파일 제외)
- 초과 시: PR 본문에 분할 불가 사유를 명시하고 리뷰어에게 미리 공지
- 1000 LOC 초과 PR은 원칙적으로 분할한다 (대규모 리네임/포맷 변경 제외)

## 4) 게이트 실패 처리
- 필수 게이트 실패 시 머지 차단
- 원인/조치/재발방지 메모를 PR에 남김
- 긴급 예외는 팀 승인 후 적용하고, 후속 정리 PR을 만든다.
- 예외 머지는 `Post-Review`를 24시간 내 완료해야 한다.

## 5) PR 기록 규칙
- 공통(사람/AI): PR 본문에 `AS-IS`, `TO-BE`를 작성한다.
- 필수 게이트(build/test) 미통과 PR은 템플릿 작성 여부와 무관하게 머지하지 않는다.

## 6) 현재 CI 게이트 한계 (알려진 사항)
- CI 워크플로우는 `pull_request` → `develop` 기본 트리거. `main` 대상 PR(릴리즈 머지)도 트리거되도록 둔다.
- 트리거 경로 필터로 모노레포 영역별 분리: backend 변경은 `backend/**`, web 변경은 `web/**` 기준.
- Branch protection으로 강제하지 않으므로 CI 실패 PR도 기술적으로 머지가 가능하다. 팀 합의로 차단한다.

## 7) 수용 기준 (MVP)
- 모든 PR이 빌드/테스트 통과 상태에서만 머지된다.
- 모든 PR이 `AS-IS`, `TO-BE`를 포함한 상태에서만 머지된다.

## 8) rev QA gate (CI 게이트 보강)

§2 필수 게이트는 **자동(CI)** 게이트로 unit + integration 테스트와 빌드/lint를 검증한다. 그러나 다음 부류는 CI만으로 잡히지 않는다:
- 런타임 에러 (NPE, 직렬화 실패, lazy init, env 누락)
- API 통합 회귀 (FE→BE 계약 불일치, CORS, 미들웨어 순서)
- 결정성 / p95 / 다양성 등 spec §3 비기능 요구사항 위반
- DB 마이그레이션 실 적용 시 발생하는 충돌

이 영역은 **rev 세션 QA 실행 검증**이 보강한다 (사용자 결정 2026-05-22).

### 8-1) rev QA의 위치
- CI 필수 게이트(§2) = 머지 차단 게이트. 사람 개입 없이 자동.
- rev QA = 머지 직후 또는 release 직전 사후 검증. sub-agent가 실 환경(local 3-tier)에서 smoke 시나리오 실행.
- 두 게이트는 직렬이 아니라 보강 관계. CI는 빠른 자동 차단, rev QA는 느린 깊은 검증.

### 8-2) rev QA의 강제 시점
| 시점 | 트리거 | 범위 |
|---|---|---|
| PR 머지 직후 | maestro이 머지 이벤트 감지 시 rev sub-agent launch | 해당 PR 단건 (PR 범주 매트릭스 따라) |
| release 직전 | maestro이 `develop → main` PR 생성 시 | 미QA PR(reviewed:claude 라벨 없음) 일괄 |
| idle 사이클 | 머지된 PR 1시간+ 없음 | develop 전체 회귀 (BE 테스트, FE 게이트, 통합) |

### 8-3) rev QA 실패 처리
- 🟢 PASS → `reviewed:claude` 라벨 부여. 끝.
- 🟡 NOTE → `reviewed:claude` 라벨 부여 + maestro이 후속 이슈 등록 (다음 사이클 fix).
- 🔴 BLOCK → 라벨 부여 안 함. **release gate 차단**. maestro이 fix 사이클 즉시 launch.

`reviewed:claude` 라벨이 없는 PR은 release PR 본문에서 색출 가능:
```bash
gh pr list --base develop --state merged \
  --search "merged:>=<이전 release 이후> -label:reviewed:claude" \
  --json number,title,labels
```

### 8-4) 정형 spec
QA 결정 트리, smoke 시나리오 라이브러리, 환경 선택, 도구, 결과 리포트 형식은 모두 `docs/features/rev-qa-protocol.md`에 있다. 로컬 3-tier 가동은 `docs/runbooks/local-3tier-setup.md` 참조.
