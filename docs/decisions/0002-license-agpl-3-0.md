---
id: 0002
title: 라이선스를 AGPL-3.0-or-later로 적용
status: accepted
date: 2026-05-20
deciders: [@goohong]
---

# 0002. 라이선스를 AGPL-3.0-or-later로 적용

## Context
mobruji 레포는 public으로 운영한다. 누구나 코드를 조회·포크할 수 있는 상황에서, 1인 개발자가 동일 서비스 클론에 대한 최소한의 억제력을 확보하고 싶었다.

## Decision
- 레포 루트에 GNU Affero General Public License v3.0 (`LICENSE`)을 적용한다.
- 라이선스 식별자: `AGPL-3.0-or-later`.
- 추후 라이선스 변경은 새 ADR로 결정한다.

## Consequences
### 긍정적
- 누군가 동일 코드 기반으로 서비스를 운영하면 **소스 공개 의무** 발생 → 상업적 클론 억제력 강함
- public repo의 신뢰성·기여 진입장벽 둘 다 확보
- 추후 dual licensing 전략(상업용 별도 라이선스 판매) 여지 보존

### 부정적
- AGPL을 꺼리는 기업 사용자의 채택률은 낮아질 수 있음 (현 단계 비핵심)
- 향후 의존성 추가 시 라이선스 호환성 검토 필요 (특히 GPL-incompatible 라이브러리)

## Alternatives (considered)
- **(A) MIT/Apache-2.0**: 가장 자유. 누구나 자기 서비스로 가져가도 OK. 도용 억제력 없음 → 1인 개발 단계에선 부담.
- **(B) BSL / Elastic License**: 소스 공개 + 상업 사용 제한. OSI 비공인이라 외부 인지도·기여 진입에 부정적.
- **(C) LICENSE 미부착 (All Rights Reserved)**: 기본값이지만 GitHub TOS상 조회·포크 자동 허용. 의도 명시가 모호.

## References
- `LICENSE` 파일 — GNU 공식 텍스트
- AGPL-3.0 FAQ: https://www.gnu.org/licenses/agpl-3.0.html
- `CLAUDE.md` §4 비협상 룰 / 보호 영역
