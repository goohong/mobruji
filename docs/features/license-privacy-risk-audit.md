---
feature: 이용약관·개인정보처리방침 초안 + 법무 리뷰 게이트
slug: license-privacy-risk-audit
status: draft
owner: "@goohong"
scope: infra
related_issues: []
related_prs: [1640]
last_reviewed: 2026-06-03
---

# 이용약관·개인정보처리방침 초안 + 법무 리뷰 게이트

> **⚠ 변호사 자문 대체 X.** 본 spec 은 plan sub-agent 가 작성한 **운영 문서 초안 + 후속 ADR 후보** 입니다. 정식 launch 전 외부 변호사 검토 / 외부 API 사업자 공식 contact 회신 박제 / 표준 양식 (공정거래위 표준약관 + 개인정보보호위 표준 방침) 대조 의무.

## 1) 개요 (What / Why)

### 사용자 directive 정확 인용 (2026-06-03)

> 외부 API (멜론/지니/유튜브 등) 약관상 데이터 수집·재사용 허용 범위 조사 / 이용약관·개인정보처리방침 초안 작성 (사용자 음역대·기호 데이터 수집 관점) / 관련 법령 (저작권법, 개인정보보호법) 요구사항 spec/ADR 문서화

### 본 spec 의 위치 (선행 spec `#1640` 의 자식 사이클)

- **선행 SoT** = `docs/features/legal-risk-data-collection-and-pii.md` (PR #1640, 2026-06-03 plan 사이클 1차) — 세 축 (저작권 / 약관 / PII) 리스크 레지스터 단일 본문.
- **본 spec 의 분담** = 선행 spec 의 미충족 directive 산출물 3종 추가:
  1. **이용약관 초안** (`docs/legal/terms-of-service-draft.md`) — 본 PR 신설
  2. **개인정보처리방침 초안** (`docs/legal/privacy-policy-draft.md`) — 본 PR 신설
  3. **후속 ADR 후보 3건** — ADR-0030 (곡 메타 source 전략) / ADR-0031 (데이터 최소화·식별성 정책) / ADR-0032 (정식 launch 전 법무 리뷰 게이트). 본 spec §3 에서 골격 박제, 채택은 별 PR.
- **선행 spec 과의 중복 회피**: 리스크 매트릭스 본문 (외부 API 약관·PII 항목·저작권 적용) 은 선행 spec 단일 SoT 유지. 본 spec 의 §2 는 **선행 spec 의 어떤 행이 본 약관 / 방침 / ADR 의 어떤 절·조항으로 흘러갔는지** 매핑만 박제 (중복 본문화 X).

### 한계 (재확인)

- 본 spec 의 모든 산출물은 plan sub-agent 의 1차 desk research 입니다. 외부 변호사 검토 + 외부 API 사업자 공식 contact 회신 + 개인정보보호위원회 표준 양식 대조 의무 (`LegalReviewGate`).

## 2) 선행 spec → 산출물 매핑

| 선행 spec 행 (`legal-risk-data-collection-and-pii.md`) | 본 spec 산출물 |
|---|---|
| §5-1 (음원·가사 저작권 — C1 audio / C2 가사 / C3 커버 / C4 사실 메타) | 이용약관 초안 §11 (저작권 및 콘텐츠 — v2 본문 확장 대상) + ADR-0030 (곡 메타 source 전략) |
| §5-2 (외부 약관 — T1 YouTube / T2 노래방 차트 / T3 Spotify / T4 iTunes / T5 MusicBrainz·CAA / T6 멜론·지니) | 이용약관 초안 §4 (서비스 제공) + ADR-0030 (UGC 우선 + CC0 출처 + 공식 contact 외 미사용) |
| §5-3 (PII — P1 음성 / P2 음역대 / P3 좋아요·북마크 / P4 sessionId / P5 회원 PII / P6 방침·동의 / P7 추천 history) | 개인정보처리방침 초안 §1-§13 + §12-1 / §12-2 / §12-3 특화 박제 + ADR-0031 (데이터 최소화) |
| §6 후속 트랙 (출시 전 법무 자문 게이트) | ADR-0032 (정식 launch 전 법무 리뷰 게이트) — 본 spec §3 후보 |
| §8 오픈 질문 Q1-Q4 (운영 audio / 방침·동의 / 크롤링 ADR / GDPR 트리거) | 본 spec §6 오픈 질문 Q1-Q6 로 일부 승계 + ADR 후보로 흡수 |

## 3) 후속 ADR 후보 (3건)

> 본 spec 채택 후 별 PR 3건으로 ADR 본문화. 각 ADR 의 SoT 후보 = 본 spec + 선행 spec.

### ADR-0030 (제안) — 곡 메타 source 전략 (`terms-of-service-source-strategy`)

- **Context**: 외부 음원 사업자 약관·DB 제작자 권리 + 본 서비스 출시 일정 (선행 spec §5-2 T1-T6).
- **Decision 후보**: (A) 사용자 직접 입력 (UGC) 우선 / (B) CC0 출처 (MusicBrainz) 보조 / (C) 공식 contact 회신 받은 사업자만 추가, **scraping 영구 금지**.
- **Alternatives**: 사업자 API 전면 / scraping / UGC 만.
- **Consequences**: 카탈로그 확장 속도 ↓ / 법적 risk ↓↓. ADR-0029 (앨범 커버) 와 정렬.

### ADR-0031 (제안) — 데이터 최소화·식별성 정책 (`privacy-policy-data-minimization`)

- **Context**: 비식별 sessionId 단위 누적 데이터 (음역대 / 좋아요 / 추천 history) 의 결합 식별 risk + access log 보존 (선행 spec §5-3 P2-P4-P7).
- **Decision 후보**: (A) sessionId TTL = ADR-0013 그대로 유지 (180일) / (B) IP 마스킹 (last octet 제거) / (C) access log 보존 기간 최소화 (90일 후보) / (D) 마이크 audio 서버 미전송 strict 유지 / (E) v0.4 머지 시 동의 명시.
- **Alternatives**: TTL 단축 / 영구 보존 / 음성 서버 분석 옵션.
- **Consequences**: 추천 학습 신호 손실 가능성 + 개인정보 risk ↓.

### ADR-0032 (제안) — 정식 launch 전 법무 리뷰 게이트 (`legal-review-gate-pre-launch`)

- **Context**: 본 spec + 선행 spec 모두 plan sub-agent 의 desk research. 정식 출시 시 법적 결론 필요.
- **Decision 후보**: 정식 launch (v1.0 / 회원 모집 / 광고 / 매출 발생) **이전** 3 조건 충족 의무:
  1. 외부 변호사 검토 (본 spec + 선행 spec + 약관·방침 초안 + 데이터 흐름)
  2. 외부 API 사업자 공식 contact 회신 박제 (각 사업자별 1건 이상)
  3. 약관 / 개인정보처리방침 정식 게시
- 미충족 시 launch 보류. `LegalReviewGate` 운영 용어.
- **Alternatives**: 자체 검토만 / launch 후 검토.
- **Consequences**: launch 일정 영향 + 법적 risk ↓↓.

## 4) 산출 문서 (본 PR 신설)

- `docs/legal/terms-of-service-draft.md` — 이용약관 초안 v1 (목차 + 핵심 5 조).
- `docs/legal/privacy-policy-draft.md` — 개인정보처리방침 초안 v1 (법정 9 항목 + 본 서비스 특화 §12-1/12-2/12-3).
- `docs/ai-harness/06-domain-model.md §4-2` 신규 운영 용어 등재 (`ExternalApiTermsRiskMatrix` / `UserDataPrivacyMatrix` / `CombinedIdentifiabilityRisk` / `PrivacyLawObligationMatrix` / `CopyrightLawRiskMatrix` / `LegalReviewGate` / `SongMetadataSourceStrategy`).
- `docs/ai-harness/04-security-policy.md §3` cross-ref 1줄 (선행 spec / 본 spec 으로 법적 근거 연결).

## 5) 범위 / 비범위

### 포함

- 이용약관 / 방침 초안 골격 (v1 — 핵심 5 조 / 법정 9 항목 + 본 서비스 특화 3항).
- 후속 ADR 3건 골격 (본 spec §3).
- 도메인 모델 §4-2 운영 용어 등재.

### 제외 (Out of Scope)

- 약관 / 방침 v2 본문 확장 (12-15 조 전면) — 별 PR.
- 본 ADR 3건 본문 — 별 PR (`docs/decisions/0030-*.md` 등).
- 변호사 회신 / 외부 API 사업자 회신 박제 — 사용자 액션 (nmae 위임).
- 리스크 매트릭스 본문 (선행 spec SoT 유지).

## 6) 오픈 질문

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | 외부 변호사 검토 의뢰 시점 | (a) v0.4 회원화 진입 직전 / (b) 정식 출시 직전 / (c) 매출 발생 직전 | 사용자 결정 (nmae ask 후보) |
| Q2 | 곡 메타 1차 source | (a) UGC 1차 + MusicBrainz 보조 / (b) MusicBrainz 1차 + UGC 보조 / (c) 양립 | ADR-0030 |
| Q3 | 음역 마이크 audio 서버 분석 도입 시 동의 | (a) 영구 클라이언트만 / (b) 동의 시 서버 분석 옵션 | ADR-0031 + voice-range 측정 spec |
| Q4 | sessionId TTL — ADR-0013 (180일) 유지 vs 단축 | (a) 180일 / (b) 단축 | ADR-0031 |
| Q5 | access log 보존 기간 | (a) 90일 / (b) 1년 / (c) 통신비밀보호법 별도 검토 | ADR-0031 + observability spec |
| Q6 | CPO (개인정보 보호책임자) 지정 | (a) 사업자 본인 (초기) / (b) 별도 지정 (사업 규모 확장 후) | 사용자 결정 |

## 7) 작업 분할 (예상 PR 리스트)

- [x] (plan) 본 PR: spec + 약관·방침 초안 v1 + 도메인 모델 신규 용어 등재.
- [ ] (plan) 별 PR: ADR-0030 (곡 메타 source 전략) 본문 작성.
- [ ] (plan) 별 PR: ADR-0031 (데이터 최소화·식별성 정책) 본문 작성.
- [ ] (plan) 별 PR: ADR-0032 (정식 launch 전 법무 리뷰 게이트) 본문 작성.
- [ ] (plan) 별 PR: 약관·방침 v2 본문 확장 (12-15 조 전면).
- [ ] (사용자 액션) 외부 변호사 검토 의뢰 — nmae 위임 후보.
- [ ] (사용자 액션) 외부 API 사업자 공식 contact 회신 박제 — nmae 위임 후보.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음. 본 PR = `docs/features/**` + `docs/legal/**` (신규 디렉토리) + `docs/ai-harness/04-security-policy.md` 1줄 cross-ref + `docs/ai-harness/06-domain-model.md §4-2` 신규 용어 row 7건. 코드 / build / migration / workflow 변경 X.

## 8) 테스트 전략

- 코드 변경 없음 — 자동 테스트 대상 외.
- `.github/workflows/spec-status-check.yml` 가 frontmatter 8 필드 검증 (push 시).
- 본 spec 의 매트릭스 / 약관·방침 초안의 법적 정확성 검증 = 외부 변호사 / 외부 API contact 회신 (별 trigger — 본 spec scope 외).

## 9) 결정 로그

- 2026-06-03: 초안 작성 (status=draft) — plan sub-agent.
  - PR #1640 (선행 spec) 발견 → 본 spec 의 §2·§3·§4 (이미 작성한 매트릭스 본문) 를 폐기하고 선행 spec 의 자식 산출물 (약관·방침 초안 + ADR 후보) 로 재정렬. 중복 본문화 회피.
- 2026-06-03: 변호사 자문 대체 X 명시 (본 spec 두번 + 약관 초안 footer + 방침 초안 footer).

## 10) Cross-ref

- **선행 SoT**: `docs/features/legal-risk-data-collection-and-pii.md` (PR #1640) — 세 축 리스크 매트릭스 단일 SoT.
- `docs/ai-harness/06-domain-model.md §7 D2` (canonical 오픈 이슈 — 미편집).
- `docs/ai-harness/04-security-policy.md §1·§2·§3`.
- `docs/decisions/0002-license-agpl-3-0.md` — 본 서비스 코드 라이선스.
- `docs/decisions/0006-audio-source-youtube.md` — YouTube audio ToS 결정.
- `docs/decisions/0011-session-bound-auth-policy.md` + `docs/decisions/0013-sessionid-ttl-rotation.md` — sessionId.
- `docs/decisions/0029-album-cover-art-source.md` + `docs/features/album-cover-art.md` — 앨범 커버 외부 출처.
- `docs/features/anonymous-session-lifecycle.md` / `docs/features/user-authentication-and-profile.md` / `docs/features/anonymous-to-account-conversion.md` — 익명 / v0.4 회원 / 머지.
- `docs/legal/terms-of-service-draft.md` + `docs/legal/privacy-policy-draft.md` (본 PR 신설).

---

**⚠ Footer 재확인**: 본 spec + 산출 문서 (`docs/legal/*`) 는 **변호사 자문 대체 X** 입니다. 정식 launch 전 외부 변호사 검토 + 외부 API 공식 contact 회신 박제 + 표준 양식 대조 (`LegalReviewGate`) 의무.
