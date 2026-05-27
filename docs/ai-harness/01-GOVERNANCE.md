# AI Harness Volume 1: GOVERNANCE

> 모든 에이전트(Maestro, Plan, BE, FE, Rev)가 준수해야 할 공통 운영 헌법 및 보안 프로토콜입니다.

---

## 1. 에이전트 협업 및 브랜치 전략
- **1 이슈 = 1 브랜치 = 1 PR**: 모든 변경은 PR을 통해 수행하며, Squash Merge를 원칙으로 한다.
- **브랜치 명명**: `<type>/<summary>-#<issue-number>` (예: `feat/recommendation-#12`)
- **에이전트 식별**: 모든 커밋에 `Co-Authored-By: <Agent Name> <email>` 트레일러 포함 필수.
- **워크트리 격리**: 각 서브 에이전트는 지정된 전용 워크트리(`mobruji-be`, `mobruji-fe` 등)에서만 작업하며, 타 세션의 파일을 절대 수정하지 않는다.
- **Reasoning Chunk Limit**: 단일 턴의 reasoning/도구 호출이 5분을 넘지 않도록 작업을 쪼개어 수행한다.

## 2. PR 및 핸드오프 프로토콜
- **라벨링 의무**: PR 생성 직후 `type:*`, `scope:*`, `ai-generated`, `session:*` 라벨을 즉시 부착한다.
- **보호 영역 (Rev 위임)**: CI/CD, DB 마이그레이션, 인프라 등 민감 경로의 변경 시 **Rev 에이전트가 판단**하여 필요 시에만 `needs-human-review`를 요청한다. Rev는 품질 게이트 통과 여부와 리스크를 종합적으로 고려하여 자율적으로 머지 승인 여부를 결정할 수 있다.
- **보고 양식**: 작업 완료 후 Maestro에게 `PR URL`, `변경 요약`, `품질 게이트 결과`, `발견 사항(🔴/🟡/🟢)`을 표준 양식에 맞춰 보고한다.

## 3. 보안 정책 (Security First)
- **비밀번호/토큰**: 코드, 문서, PR 본문, 로그에 API 키나 시크릿을 절대 노출하지 않는다. (`mask_secrets` 파이프라인 준수)
- **사용자 데이터**: 음역대, 취향 등 개인 식별 가능 정보는 마스킹 처리하거나 비식별화된 샘플 데이터만 사용한다.
- **금지 행위**: Secret key 커밋, 민감 정보 외부 무단 전송, 운영 DB 덤프 로컬 업로드 금지.
- **Discord 브릿지**: `bot.py`를 통한 알림 시 `@everyone` 등의 멘션이 발화되지 않도록 `sanitize_mentions`를 적용한다.

## 4. Feature Spec 프로세스
- **신규 기능/도메인/외부 연동** 작업 시 반드시 `docs/features/<slug>.md`를 먼저 작성(Plan 에이전트)하고 합의된 후 구현에 착수한다.
- **라이프사이클**: `draft` → `approved` → `implementing` → `shipped`.
- 에이전트는 구현 착수 전 관련 Spec을 반드시 정독하고, Spec과 코드가 충돌할 경우 Spec을 우선 갱신한다.

---
**참조 문서**: `docs/ai-harness/01, 02, 04, 05, 12, 14, 15`
