## PR 제목 규칙
- 형식: `type(scope): 제목`
- 예시: `feat(recommendation): 음역대 기반 1차 추천 알고리즘 구현`
- `type`: `feat` `fix` `docs` `style` `refactor` `test` `chore`
- `scope`: `user` `song` `recommendation` `voice` `infra` `web` `feedback`

## AS-IS
- 현재 상태/문제점을 간단히 작성

## TO-BE
- 변경 후 기대 상태를 간단히 작성

## 관련 이슈
- closes #이슈번호

## 리뷰어 참고 포인트
- 특히 봐주면 좋은 포인트를 적어주세요.

## visual baseline update

> **조건부 의무**: `type:feat scope:web` PR 만 본 섹션 작성 의무 (그 외 scope 옵션).
> 본 섹션은 `docs/features/visual-regression-ci.md §3-2 / §3-4` SoT 의 baseline drift
> 룰 (ADR-0026 §Decision 2 후보 (a)) 강제 — rev sub-agent 가 단계 1 review 시
> 본 섹션 부재 + visual diff > 0.1% 발견 시 `rev단계1: 🔴 시각 회귀 의심` 코멘트.
> 본 spec 머지 후 PR template auto-check workflow (`visual-baseline-pr-body-check.yml`)
> 신설 여부는 `visual-regression-ci.md §8 Q6` 결론 의존.

다음 둘 중 하나 선택:

- [ ] **baseline 변경 없음** (UI 영향 변경 X, 또는 visual diff 0% 확인)
- [ ] **N 페이지 baseline 갱신** — 사유 + 영향 페이지 명시
  - 갱신 페이지 수:
  - 사유 (예: ADR-0018 단계 4 color swap):
  - 영향 페이지 (예: `/`, `/history`, `/voice` light + dark, desktop + mobile):
  - baseline 갱신 명령 (참고): `cd web && npx playwright test --update-snapshots`

## 라벨 부여 확인
- [ ] `type:*` 라벨 1개 부여 완료
- [ ] `scope:*` 라벨 1개 부여 완료
- [ ] (AI 작성 시) `ai-generated` 라벨 부여 완료

<details>
<summary>AI Agent 전용 체크리스트 (해당 시 작성)</summary>

## AI 작업 여부
- [ ] AI 작업 아님 (사람 작성 PR)
- [ ] AI 보조/생성 사용

## 품질 게이트 체크 (AI 작성 시)
**Backend 변경 시:**
- [ ] `cd backend && ./gradlew checkstyleMain spotlessCheck`
- [ ] `cd backend && ./gradlew test`

**Web 변경 시:**
- [ ] `cd web && npm run lint`
- [ ] `cd web && npm run typecheck` (또는 `npx tsc --noEmit`)
- [ ] `cd web && npm test` (테스트 셋업 후)

**공통:**
- [ ] 변경 범위의 회귀 가능 구간을 확인했다.
- [ ] 롤백 절차를 작성했거나, 롤백 불필요 사유를 작성했다.

## DDD/OOP 준수 체크 (AI 작성 시)
- [ ] 도메인 용어가 기존 컨텍스트와 일치한다.
- [ ] 계층 침범(Controller -> Repository 직접 호출 등)이 없다.
- [ ] 객체 역할/책임이 명확하며, 과도한 God Object를 만들지 않았다.

## 보안/민감정보 체크 (AI 작성 시)
- [ ] 시크릿(API 키/토큰/비밀번호) 하드코딩이 없다.
- [ ] 사용자 입력값(음역대·기호)을 로그/스크린샷에 원문 노출하지 않았다.
- [ ] 민감정보가 필요한 경우 마스킹 처리했다.

## 예외 머지 여부 (AI 작성 시)
- [ ] 예외 머지 아님
- [ ] 예외 머지임 (사유 작성 + 머지 후 24시간 내 `Post-Review` 진행)

### 예외 머지 사유 (해당 시 작성)
- 사유:
- 영향 범위:
- 후속 조치/기한:

## AI 작업 기록
- 사용한 프롬프트/지시 요약:
- AI가 직접 수정한 파일/범위:
- 사람이 수동 검증한 항목:
- 잔여 리스크/추가 확인 필요사항:

</details>
