---
feature: 회원화 도입 검토 — 개인화 맞춤 전략 관점 가치/마찰 매트릭스 + 권고
slug: membership-personalization-review
status: draft
owner: @goohong
scope: user
related_issues: [243, 1491]
related_prs: []
last_reviewed: 2026-06-03
---

# 회원화 도입 검토 — 개인화 맞춤 전략 관점 가치/마찰 매트릭스 + 권고

> **본 spec 의 성격: 메타-검토(meta-review) spec.** 구현 산출이 아니라, 이미 작성된 4개의 자식 spec(`user-authentication-and-profile.md` / `anonymous-to-account-conversion.md` / `preference-learning-personalization.md` / `first-user-onboarding-flow.md`)을 **"개인화 맞춤 전략"** 단일 렌즈로 한 번에 묶어 가치/마찰을 매트릭스화하고 권고 옵션 + 사용자 결정 필요 항목을 박제하는 **합의 문서** 다. 본 spec 머지가 구현 개시 신호는 아니다 — 머지 후 nmae 가 §11 의 사용자 결정 항목을 받아낸 뒤, §9 결정 로그에 추가 + 자식 spec 의 §8 오픈 질문을 동시 갱신한 다음 자식 spec 의 구현 PR(P1~P5, A~F)을 dispatch 한다.

## 1) 개요 (What / Why)

### 1-1) 사용자 directive (정확 인용)

> "개인화 맞춤 전략 관점에서, 회원화 어떤지 검토하게 지시해줘"
> — 사용자, 2026-06-03

### 1-2) 본 spec 이 답하려는 질문

- mobruji 의 추천 가치 핵심은 "이 사용자에게 맞는 곡" 이라는 **개인화** 다. 음역대 / 선호 장르 / 선호 분위기 / 좋아요·패스 누적 / 부른곡 시드 등 **시간이 갈수록 누적되어야 정확해지는** 신호가 추천 알고리즘의 입력이다.
- 현재(v0.3) 모든 신호는 **익명 sessionId 위에서만 영속**된다(ADR-0011 + ADR-0013). 쿠키 클리어 / 디바이스 교체 / 180일 미접속 시 누적이 0으로 리셋된다. 즉 **개인화의 시간 지평이 sessionId 수명에 묶여 있다.**
- 회원화(`User` + `UserProfile`)는 개인화의 시간 지평을 **영구 + 디바이스 횡단**으로 확장하는 거의 유일한 수단이지만, sign-up 흐름이 진입 마찰을 일으켜 **첫 추천 도달 이탈률**을 높일 risk 가 있다.
- 따라서 본 spec 의 핵심 질문은 다음 두 가지다.
  - **Q-A. 개인화 가치 관점에서 회원화는 얼마나 큰 이득인가?** 차원별(음역대 영구화 / 디바이스 동기화 / 학습 누적 / 좋아요·북마크 영속 / 정확도) 정량 평가.
  - **Q-B. 마찰 비용을 최소화하는 도입 형태는 무엇인가?** "필수 sign-up" 부터 "가치 지점 트리거 optional" / "OAuth-only magic" / "anonymous→account 점진 머지" 까지 옵션 비교.

### 1-3) 자식 spec 과의 경계 (SoT 분리 — 중복 방지)

| 자식 spec | 단일 진실 범위 | 본 spec 과의 관계 |
|---|---|---|
| `user-authentication-and-profile.md` | 인증 메커니즘(HOW) + `User`/`UserProfile` 엔티티 + 영속 프로필 prefill | 본 spec 은 **WHY/WHEN** 합의를 제공해 자식의 §8 Q1·Q3·Q4 의 결정 입력을 박제 |
| `anonymous-to-account-conversion.md` | 비회원/회원 매트릭스 + 전환 트리거 + `sessionId→user_id` 머지 알고리즘 | 본 spec 은 트리거 정책 옵션을 **개인화 가치 vs 마찰** 단일 축으로 재해석 |
| `preference-learning-personalization.md` | 익명 sessionId 단위 implicit 학습(`LearnedPreferenceProfile`) | 본 spec 은 회원화가 학습 지평을 ≤180일→영구로 확장할 때 가치 증분을 정량화 |
| `first-user-onboarding-flow.md` | 3 페르소나 진입 경로 + 첫 추천 도달 | 본 spec 은 sign-up 모달 트리거가 온보딩 흐름의 **어느 단계** 이후에 들어가야 첫 추천 도달 이탈률을 깨지 않는지 권고 |

본 spec 머지 후 자식 spec 의 §8 오픈 질문 일부는 본 spec §9 결정 로그를 참조해 닫힌다(자식 spec PR 별도). 본 spec 은 자식의 구현 계획 / API 표면 / 머지 알고리즘은 **재명세하지 않는다.**

### 1-4) 대상 액터

- 비회원 첫 진입 사용자(3 페르소나 A·B·C, `user-persona-and-pain-points.md §2`).
- 비회원 재방문 사용자(같은 디바이스, sessionId 유효).
- 회원 사용자(v0.4 이후 — 본 검토가 도입 합의의 입력).
- plan(본 spec 작성·합의) / nmae(사용자 결정 항목 ask + §9 결정 로그 추가) / be·fe(자식 spec PR 구현 — 본 spec 범위 외).

## 2) 사용자 시나리오 (개인화 관점 5 case)

> 각 시나리오는 **"개인화 누적이 어디서 끊기는가"** 와 **"회원화가 그 끊김을 어떻게 메우는가"** 를 보이도록 구성. 시나리오 본문은 4개 자식 spec 의 시나리오에서 개인화 차원만 추출 + 재구성.

1. **(S1) 신규 첫 진입 → 음역 측정 → 첫 추천 — sign-up 벽 없음**
   - 비회원 A 가 처음 접속. 의도 카드(BEGINNER / PRACTICE / MOOD, `first-user-onboarding-flow.md`) 선택 → 음역 측정 → 추천 5곡. **로그인 화면 전혀 노출 안 됨.** 모든 신호는 sessionId 단위로 영속(180일 TTL, ADR-0013).
   - 개인화 baseline: 이 시점에는 학습 신호 0 (콜드스타트). 추천은 음역대 + 분위기 입력만으로 결정성 결과(`recommendation-algorithm-v1.md §3`).

2. **(S2) 같은 sessionId 로 재방문 → 학습 누적 반영**
   - 비회원 A 가 며칠 후 같은 디바이스로 재접속(sessionId 쿠키 유효). 지난 세션의 좋아요/패스/부른곡 시드가 `LearnedPreferenceProfile`(익명 학습, `preference-learning-personalization.md`)에 누적되어 **첫 추천부터** 발라드·잔잔 성향 곡이 상위에 배치된다.
   - 개인화 시간 지평: **≤ 180일(TTL) + ≤ sessionId 회전 주기.** 회원화 없이도 익명 학습으로 상당 부분 메워진다 — 이게 본 검토의 **"회원화가 정말 필요한가"** 의 반례 축.

3. **(S3) 디바이스 교체 / 쿠키 클리어 → 누적 0 리셋**
   - 비회원 A 가 휴대폰 교체 또는 시크릿 모드 사용. 이전 sessionId 없음 → 신규 익명 진입. **모든 학습/음역대/좋아요/북마크 손실.** 다시 음역 측정부터 시작.
   - 개인화 손실 지점: **디바이스 경계.** 회원화의 가치 1순위 정당화 — 디바이스 횡단 영속.

4. **(S4) 좋아요/북마크 클릭 시점 → 가치 지점 sign-up 모달**
   - 비회원 B 가 추천 결과 카드의 좋아요 버튼 클릭. fe 가 비회원 감지 → 로그인 모달(`anonymous-to-account-conversion.md §3-C`). 사용자가 (a) 로그인 → 좋아요 즉시 반영 + sessionId 의 음역대/학습 프로필 자동 머지(`§3-D` 알고리즘) / (b) 닫기 → 좋아요 미반영, 사용 흐름 그대로 유지.
   - 개인화 가치 명확 지점: 좋아요는 "이 곡을 영구 저장" 의도, 북마크는 "다음에 부르려고 저장" 의도. 둘 다 **시간 지평 영구화 요구가 자명** — 가치 vs 마찰의 비용 정당화가 가장 쉬운 트리거.

5. **(S5) 회원 → 디바이스 횡단 sync**
   - 회원 C 가 집 PC 에서 음역 측정 + 좋아요 5곡 누적. 다음 날 휴대폰에서 같은 계정 로그인 → 음역대 + 학습 프로필 + 좋아요/북마크 모두 동기화. 추천 폼은 `GET /users/me/profile` 으로 prefill(`user-authentication-and-profile.md §5-4`).
   - 개인화 시간 지평: **영구 + 디바이스 횡단.** 회원화가 메우는 정확한 빈칸.

6. **(S6) 회원 sign-out 후 sessionId 잔존** (정책 결정 항목 — §11 D-3)
   - 회원 C 가 sign-out. 새로 발급된 익명 sessionId 위에서 사용 → 회원 시절 누적된 학습/좋아요/북마크 어느 것도 보이지 않음 (정상 — 익명/회원 분리). 사용자가 sign-out 의도를 "기록 보호" 로 가졌다면 안내 필요(공유 디바이스 시나리오). 정책 권고: §11 D-3.

## 3) 요구사항

### 기능 요구사항 (본 spec 산출 = 검토 결과)

- [ ] §5 의 **개인화 가치 매트릭스**(5 차원) + **마찰 비용 매트릭스**(5 항목) 박제.
- [ ] §6 의 **도입 옵션 비교**(4 case: A/B/C/D) 권고 1건 명시 + 자식 spec 의 §8 오픈 질문에 본 spec §9 결정 로그 cross-ref.
- [ ] §10 의 **자식 spec 영향 분석** (본 spec 권고 적용 시 어느 자식 spec 의 어느 섹션이 어떻게 바뀌는지) — 자식 spec 직접 편집은 본 PR 에서 하지 않음(중복·드리프트 risk). 자식 spec 갱신은 본 spec 머지 후 별 PR(plan 후속 사이클).
- [ ] §11 의 **사용자 결정 필요 항목 list**(D-1 ~ D-5) — nmae 가 사용자에게 직접 ask 한 뒤 답을 §9 에 추가, 자식 spec §8 닫음.

### 비기능 요구사항

- **결정성 보존**(`recommendation-algorithm-v1.md §3`): 본 spec 의 어느 권고도 추천 점수 산식을 변경하지 않는다. 회원화는 사용자 데이터의 **시간 지평·영속 범위**만 확장한다(가치) — 알고리즘 신호 가중치 / 계산식은 자식 spec 의 별 결정. 회귀 가드: 본 spec 채택 후에도 같은 (입력, 후보 집합) → 같은 결과.
- **익명 사용자 호환성**(P-0 호환 룰, `anonymous-to-account-conversion.md §3-C 단계 0`): 본 spec 의 어느 권고도 **익명 사용자의 첫 진입 ~ 첫 추천 도달 흐름을 깨면 안 된다.** 회귀 가드 = `first-user-onboarding-flow.md` 의 5 시나리오가 회원화 도입 전후 동일 통과.
- **개인정보 / 보안**(`04-security-policy.md`): OAuth client id/secret / 토큰 secret / 비밀번호 해시는 `application.yml` 외부화 + 환경변수 주입. sessionId/email/토큰 원문 로그/응답 비노출. 본 spec 의 어느 옵션도 자식 spec 의 보안 비기능 요구를 약화시키지 않는다.
- **관측성**(`observability-baseline.md §5-3`): 본 spec 채택 후 자식 spec 구현 시 권고 옵션 (B+D 조합) 의 **첫 추천 도달률 / sign-up conversion / 머지 성공률** 카운터를 동시 신설(자식 spec 의 카운터 표에 추가). 본 spec 자체는 카운터 신설 책임 없음 — 자식 spec PR 영역.

## 4) 범위 / 비범위

### 포함

- 개인화 차원별 회원화 가치 정량 평가(§5-1).
- sign-up 마찰 비용 정량 평가(§5-2).
- **데이터 영속 전략 3종 대안 비교**(익명 식별자 vs 브라우저 로컬 저장 vs 회원화, §5-4) — directive 명시 항목.
- 도입 옵션 4 case 비교 + 권고(§6).
- **단계적 도입 마일스톤**(익명 → 경량 계정 → 풀 계정, §6-4) — directive 명시 항목.
- 자식 spec 4종과의 영향 분석(§10).
- 사용자 결정 필요 항목 박제(§11) — nmae 가 답을 받아내야 자식 spec PR 진입 가능.

### 제외 (Out of Scope — 자식 spec SoT)

- **인증 메커니즘 상세**(JWT vs session / refresh token / Spring Security 도입 형태) — `user-authentication-and-profile.md` + 별 ADR.
- **`sessionId → user_id` 머지 알고리즘** — `anonymous-to-account-conversion.md §3-D` SoT.
- **익명 implicit 학습 알고리즘 / `LearnedPreferenceProfile` 스키마** — `preference-learning-personalization.md`.
- **3 페르소나 온보딩 라우팅 / `PersonaEntryPath`** — `first-user-onboarding-flow.md`.
- **OAuth provider 별 콘솔 등록 / 이메일 메일 발송 인프라** — 운영 런북 / 별 ADR.
- **계정 탈퇴 / 비밀번호 재설정 / 2FA / passkey / SSO** — v0.5+.
- **추천 알고리즘 점수 산식 변경** — 본 spec 은 데이터 지평만 다룸. 산식은 `recommendation-algorithm-v1.md`.

## 5) 설계 — 매트릭스 분석

### 5-1) 개인화 가치 매트릭스 (회원화 도입 시 가치 증분)

> 각 차원의 "현재 상태" = v0.3(익명 sessionId only) + 익명 학습(`preference-learning-personalization.md` 구현 후 가정). "회원화 후" = `user-authentication-and-profile.md` + `anonymous-to-account-conversion.md` 자식 spec 의 §3 요구사항 모두 충족 가정.
>
> 가치 (1~5) 는 **"개인화 정확도가 사용자 체감 단위에서 얼마나 더 올라가는가"** 를 1=무, 5=결정적 가치로 평가. 회귀 가드: 본 평가는 추천 산식 변경이 아니라 **신호 시간 지평**의 증분 가치 평가다.

| # | 차원 | 현재(sessionId + 익명 학습) | 회원화 후 | 가치 |
|---|---|---|---|---|
| V1 | **음역대 영구 보존** | sessionId TTL 180일 + 쿠키/디바이스 손실 시 0 (재측정 강제) | `VoiceRange` owner=userId, 영구 + 시계열 `VoiceRangeSnapshot` 누적 | **5** |
| V2 | **디바이스 횡단 sync** | 불가 — 새 디바이스 = 신규 익명 진입 | 같은 계정 로그인 → 음역대 + 학습 + 좋아요/북마크 즉시 sync | **4** |
| V3 | **학습 누적 시간 지평** | `LearnedPreferenceProfile` (≤180일 TTL, sessionId revoke 시 cascade-delete) | userId owner 치환 후 영구 누적 + N년 시계열 학습 가능 | **5** |
| V4 | **Like / Bookmark 영구화** | sessionId 단위로만 영속 — 디바이스 경계에서 손실 | userId owner 영구, 디바이스 횡단 | **4** |
| V5 | **명시 선호(장르·분위기·성별) prefill** | 미존재 — 매 추천 폼 진입마다 재입력 | `UserProfile` (`preferredGenres`/`preferredMoods`/`defaultGender`) + `GET /users/me/profile` prefill (#1491) | **5** |
| **합계** | | | | **23 / 25** |

**해석**: 5차원 중 4차원이 **결정적 가치(5)**, 1차원(V2)이 **명확한 가치(4)**. 회원화는 **개인화 시간 지평 확장**의 거의 유일한 수단이며 가치 증분이 매우 크다. 단, V3 의 가치(5)는 **익명 학습 자체가 먼저 구현되어 있어야 회원화로 인한 증분이 의미를 가진다** — `preference-learning-personalization.md` 구현이 본 회원화보다 선행 또는 동시에 진행되어야 가치 발현(§10 영향 분석 P-3).

### 5-2) 마찰 비용 매트릭스 (회원화 도입 시 마찰)

| # | 항목 | 비용 (정량/정성) | 마찰 |
|---|---|---|---|
| F1 | **sign-up flow UI 추가** | 모달 / OAuth redirect / 콜백 처리 / 토큰 저장 / interceptor — fe 사이클 약 4-5 PR(자식 spec PR C/D/F) | 중 |
| F2 | **OAuth provider 등록 / 시크릿 관리** | 카카오 + 구글 콘솔 client id/secret 발급 + 운영/dev 분리 + `application.yml` 외부화. 운영 부담 발생(키 회전 / redirect_uri 화이트리스트) | 중-높음 |
| F3 | **이탈 risk — 트리거 시점에 따라 다름** | **첫 진입 강제 sign-up = 30-50% 이탈 추정**(업계 일반). **가치 지점 트리거(좋아요/북마크) = 5-15% 추정 (이미 가치를 본 사용자만 결정)** | 트리거 정책에 따라 **낮음~매우 높음** |
| F4 | **GDPR / 개인정보 / 이용약관** | 회원 도입 = 개인정보 수집(이메일 / OAuth sub / 비밀번호 해시) → 약관 + 동의 + 탈퇴 흐름 + 데이터 보존 정책 필요(v0.5+ 별 spec). 한국 사용자 한정이라도 개인정보보호법 의무 | 중-높음 (운영) |
| F5 | **백엔드 인증 인프라** | Spring Security + OAuth client lib + `UserAuthGuard` + 토큰 발급/검증/갱신 — be 사이클 약 3-4 PR(자식 spec PR A/B/E + 본 spec 영향 PR P1/P2). 기존 `SessionAuthGuard` 와 공존(별 트랙) | 중 |

**해석**: F1·F5 는 **일회성 도입 비용**, F2·F4 는 **지속 운영 비용**, F3 는 **트리거 정책에 따라 격차가 큰** 비용. 따라서 **트리거 정책 선택이 마찰의 결정 변수**다 — 첫 진입 강제는 가치(23/25)를 받기 전에 사용자 절반을 잃으므로 도입 자체의 ROI 가 깨질 수 있다. **가치 지점 트리거(좋아요/북마크)** 가 마찰 최소화의 핵심.

### 5-3) 가치 vs 마찰 단일 축 요약

- 가치 23/25 (V1-V5 합) = **결정적**.
- 마찰 = 트리거 정책 선택에 의해 **5-50% 이탈률**로 격차 매우 큼.
- 본 spec 의 핵심 권고 = **마찰 결정 변수(F3)를 최소화하는 트리거 정책 선택**, 즉 §6 옵션 비교.

### 5-4) 대안 비교 — 데이터 영속 전략 3종 (익명 식별자 vs 브라우저 로컬 저장 vs 회원화)

> §5-1·§5-2 가 "회원화 도입 시 가치/마찰" 을 다뤘다면, 본 절은 **개인화 데이터를 어디에 영속할 것인가** 의 세 전략을 정면 비교한다. 회원화가 유일 해가 아닐 수 있으므로(§5-1 V3 의 익명 학습 반례 축), "회원화 없이 개인화를 어디까지 달성하나" 를 명시해 회원화 비용을 과대 청구하지 않는다.
>
> **핵심 분리**: 개인화 효용은 **① 같은 디바이스 재방문 개인화** 와 **② 디바이스 횡단 + 데이터 내구성** 으로 쪼갤 수 있다. ①은 익명·로컬로도 달성되며, ②만이 회원화의 고유 가치다.

| 축 | **A. 익명 식별자**(현행, sessionId/ADR-0013) | **B. 브라우저 로컬 저장**(LocalStorage/IndexedDB) | **C. 회원화**(계정) |
|---|---|---|---|
| 같은 디바이스 재방문 개인화 | ✅ 서버측 누적 학습(`LearnedPreferenceProfile`) | △ 클라이언트 보유분만, 서버측 누적 학습 어려움 | ✅ (로그인 시) |
| 디바이스 횡단 sync | ❌ sessionId 는 1 브라우저에 결합 | ❌ 새 디바이스엔 저장소 없음 | ✅ **고유 가치** |
| 쿠키 삭제·기기 교체 내구 | ❌ | ❌ | ✅ 로그인 복원 |
| TTL/장기 내구 | ❌ 180일 cascade-delete | △ 브라우저 정리에 취약·비결정적 | ✅ 영속 |
| 서버측 학습/랭킹 결합 | ✅ 자연스러움(서버가 신호 소유) | ❌ 매 요청 전량 동봉 필요 → 결정성·검증 부담 | ✅ |
| 인증 인프라 비용 | 0 (현행 sessionId) | 0 (서버 무계정) | 높음(§5-2 F2·F5) |
| 스키마·운영 비용 | 낮음(운영 중) | 낮음(클라이언트) | 높음(User/머지/시크릿 — F1·F4·F5) |
| 프라이버시(수집 최소화) | ✅ 집계·TTL·회전 | ✅✅ 데이터가 기기에 잔류(서버 미전송 옵션) | △ 영구 식별자 결합(F4) |
| 추천 정확도 상한 | 중(1 디바이스·≤180일) | 하(클라 보유분) | 상(합산·무기한) |
| 구현 현황 | 운영 중 | 미도입 | v0.4 계획(자식 spec 존재) |

**해석**:

- **A(익명)는 개인화의 충분조건**: 같은 디바이스 재방문 개인화·재입력 제거를 **인증비용 0** 으로 충족(`LearnedPreferenceProfile`). 노래방 단발·단일 디바이스라는 mobruji 의 1순위 흐름에 잘 맞는다. 한계 = 디바이스 경계·TTL.
- **B(브라우저 로컬)는 독립 전략으로 부적합**: mobruji 추천은 **서버측 랭킹·결정성 계약**(`recommendation-algorithm-v1.md §3`)에 의존한다. 선호를 클라이언트에만 두면 매 요청에 전량 동봉해야 하고 서버측 누적 학습·감쇠·warm-up 게이트를 클라이언트로 옮겨야 해 검증·결정성 부담이 커진다. 디바이스 횡단·내구도 못 푼다. 단 "서버에 취향을 안 보내고 싶은" **프라이버시 보수 옵션의 보조 수단** 으로는 검토 여지(§8 Q6 신설).
- **C(회원화)는 디바이스 횡단·장기 내구의 유일 해**: A·B 가 못 푸는 디바이스 경계·장기 내구를 푼다. 단 그 효용은 **개인화 일반이 아니라 "②(횡단·내구)" 에 국한** 되므로, 회원화는 "개인화를 위해 필요" 가 아니라 **"②가 필요한 가치 지점에서만 도입"** 으로 정당화해야 한다 — 이는 `anonymous-to-account-conversion.md` 의 "비회원 1순위 + 가치 지점 전환" 정책과 정확히 합치한다.

→ **전략 권고(영속 전략 축)**: **A 를 개인화 기본값으로 유지** + **C 를 ②(횡단·내구) 가치 지점에서만 점진 도입** + **B 는 독립 채택 안 함**(서버측 학습/결정성과 상충, 프라이버시 보조 옵션 여지만). 도입 *형태*(트리거/인증 옵션)는 §6, 점진 *순서*는 §6-4.

## 6) 도입 옵션 비교 + 권고

> 4개 옵션 비교. 자식 spec 들의 §3·§5 정책 결정과 정합. 각 옵션의 "이탈률 추정" 은 업계 일반 추정 + mobruji 의 첫 추천 도달 시간(P95 ≤ 3분, `first-user-onboarding-flow.md §3 비기능`) 컨텍스트 가정 — 정량 측정은 자식 spec 구현 후 관측성 카운터로 검증.

### 6-1) 옵션 매트릭스

| 옵션 | 설명 | sign-up 트리거 시점 | 가치 (5-1 합 중) | 마찰 (5-2 종합) | 첫 추천 이탈률 영향 | 권고 적합도 |
|---|---|---|---|---|---|---|
| **A. 회원화 안 함** | sessionId only 유지(현 v0.3) | 없음 | 0 (디바이스 횡단 / 영구화 모두 미발현) | 0 | 0 | ⛔ |
| **B. Optional 가치 지점 트리거** | 음역 측정 + 추천까지 무벽 → 좋아요/북마크 클릭 시 모달 | 좋아요/북마크 클릭 시 | **23/25** (전체 발현 가능, 사용자가 가치 본 뒤 선택) | F3 = 낮음(5-15% 이탈 추정 — 가치 본 사용자만 결정) | **거의 0** (첫 추천 흐름 무영향) | ✅ **default** |
| **C. 필수 sign-up first** | 음역 측정 전 sign-up 강제 | 첫 진입 즉시 | 23/25 (이론상) — 실제는 이탈 사용자가 가치 발현 못함 | F3 = 매우 높음(30-50% 이탈 추정) | **30-50%** (첫 추천 도달 전에 절반 손실) | ⛔ |
| **D. OAuth-only magic / 패스워드 없음** | 이메일/패스워드 제외, 카카오/구글 OAuth 만(이메일 인증 / 비밀번호 재설정 비용 제거) | 옵션 B 와 같은 시점 (좋아요/북마크 클릭) | 23/25 | F3 = 낮음(B 와 동일) + F4·F5 일부 절감(비밀번호 해시 / 이메일 메일 발송 인프라 미구현) | **거의 0** | ✅ B 와 조합 권고 |

### 6-2) 권고: **B + D 조합** (default)

**권고**:

1. **트리거 시점 = B** (좋아요/북마크 클릭 시 모달, 그 외 흐름은 비회원 무벽). `anonymous-to-account-conversion.md §3-A·§3-B·§3-C` 정책 그대로 채택.
2. **인증 옵션 = D 우선** (카카오 / 구글 OAuth 만 v0.4 P1. 이메일/패스워드는 v0.4 P2 후순위 또는 v0.5+ 까지 지연). `user-authentication-and-profile.md §8 Q1` 의 옵션 (a) "카카오+구글 우선, 이메일 후순위" 채택.
3. **음역 측정·추천 흐름은 비회원 그대로 동작 유지**(P-0 호환). 첫 추천 도달률 회귀 0 보장.
4. **dismissible banner**(`anonymous-to-account-conversion.md §3-B`) 는 friction 최소이므로 **A/B test 후 도입 결정** — 본 spec 권고에서는 default off, 자식 spec 구현 후 카운터로 효용 검증.

**사유**:

- 가치 매트릭스(23/25)는 트리거에 무관하게 회원화 자체가 결정적 — A 옵션 선택은 가치 0 으로 직결.
- 마찰 매트릭스(F3)는 트리거 정책에 의해 5-50% 격차. B 트리거는 **사용자가 이미 가치를 본 시점**(추천 결과 카드의 좋아요 의도)에서만 sign-up 비용을 청구 — ROI 가장 높음.
- D 옵션(OAuth-only)는 F2 (운영 시크릿 / redirect_uri 관리) 는 B 와 동일하나 F4 (개인정보 / 이용약관) 의 비밀번호 관리 부담 / F5 (백엔드 해시 + 이메일 인프라) 를 v0.5+ 로 지연 — v0.4 P1 출시 속도가 빨라진다. 이메일이 강하게 필요한 사용자 비율(추정 < 10%)을 잃어도 90%+ 사용자는 OAuth 로 커버 가능.

### 6-3) 권고 옵션의 회귀 / risk

- **R1. 익명 학습 미구현 시 V3 가치 발현 안 됨** — `preference-learning-personalization.md` 가 회원화보다 **선행 또는 동시 구현** 필요. 회원화 단독 채택 시 V3 가치 0 → 합계 18/25 로 떨어짐. mitigation: 자식 spec 의 PR 순서를 nmae 가 조정(§10 P-3).
- **R2. OAuth provider 의 사용자 식별자 충돌** — 카카오 sub 와 구글 sub 가 같은 사용자(같은 이메일)인데 다른 `User` 행이 생기는 케이스 — `anonymous-to-account-conversion.md §3-D` 의 머지 알고리즘은 sessionId → userId 만 다룸. **다른 provider 간 동일 사용자 식별 / 연결** 은 v0.4 범위 외(§11 D-5). 사용자가 카카오·구글 둘 다 로그인하면 2개 별 계정으로 운영됨 — 안내 필요.
- **R3. banner 도입 시 사용자 정중체 카피 누락** — fe 가 banner 도입 결정 시 `[[feedback-discord-tone-formal]]` (CLAUDE.md §4) "~합니다 / ~할까요?" 통일. "로그인 안 하면 데이터 사라져요" 같은 줄임 / 비격식 표현 금지.
- **R4. 가치 지점 트리거(좋아요/북마크)가 미구현 상태** — 현재 likes / bookmarks API 는 sessionId 기반이며 익명도 사용 가능. 회원화 도입 시 백엔드에서 `UserAuthGuard` 로 변경하는 시점(`anonymous-to-account-conversion.md §5-2`)에 fe 모달 트리거 정합성을 같은 PR 에서 보장해야 비회원이 401 보는 사고가 없다. mitigation: 자식 spec PR C/D 의 머지 동시화.

### 6-4) 단계적 도입 마일스톤 (익명 → 경량 계정 → 풀 계정)

> §6-2 가 "어떤 형태(트리거/인증 옵션)" 라면, 본 절은 "어떤 순서로 점진 도입" 이다. 각 단계는 **독립 가치·독립 배포** 이며 이전 단계 미완 시에도 비회원 흐름은 무중단(P-0 호환). 회원화를 한 번에 다 켜지 않고 §5-4 의 효용 분리(①같은 디바이스 개인화 / ②횡단·내구)에 맞춰 비용을 **수요가 확인된 단계에서만** 청구한다.

| 단계 | 내용 | 개인화 효용(획득) | 진입 신호(다음 단계로) | 구현 SoT |
|---|---|---|---|---|
| **M0. 익명 baseline**(현행 + 학습) | sessionId(ADR-0013) + `LearnedPreferenceProfile`·피드백 루프 | ① 같은 디바이스 재방문 개인화·재입력 제거 | 항상(기본값). 학습 spec 구현이 개인화 ROI 의 1차 회수 | `preference-learning-personalization.md` / ADR-0013 |
| **M1. 경량 계정**(OAuth-only, 가치 지점 전환) | 좋아요/북마크 클릭 시에만 카카오/구글 OAuth 모달 → 계정 자동 생성 + sessionId 머지. 이메일·비밀번호 없음(=§6-2 의 B+D 조합) | ② 디바이스 횡단 sync + 내구 baseline 복원 | "디바이스 교체/쿠키 삭제로 데이터 잃은 비율" 또는 "좋아요/북마크 시도 후 미전환 이탈률" 이 유의미할 때 | `anonymous-to-account-conversion.md` PR A~E / `user-authentication-and-profile.md` |
| **M2. 풀 계정**(이메일·탈퇴·동기화 완성) | 이메일 회원가입/인증, 계정 탈퇴/삭제, user-scope 동기화 조회 완비 | 장기 누적 정확도 + 명시 선호 영속(`UserProfile`) | OAuth 만으로 커버 안 되는 사용자층/요구(추정 <10%)가 데이터로 확인될 때 | `user-authentication-and-profile.md` / `anonymous-to-account-conversion.md` PR F~I |

- **왜 익명 → 경량 → 풀 순서인가**: 개인화의 1차 효용(M0, =①)은 **인증비용 0** 으로 즉시 회수된다. 내구·동기화 효용(M1, =②)은 OAuth 만으로 대부분 달성되며 이메일 인프라(메일 발송·인증·재설정) 비용을 미룰 수 있다(§6-2 의 D 채택). 풀 계정(M2)은 측정된 수요가 있을 때만 진입해 운영·프라이버시(F4) 비용을 지연·정당화한다.
- **개인화 ROI 우선순위 제언**(R1 정합): 회원화(M1/M2)보다 **M0 학습 spec 구현이 개인화 ROI 회수가 빠르다**(인증비용 0 + 1순위 단일 디바이스 흐름 직접 타격). 로드맵에서 `preference-learning-personalization.md` 구현(PR B~E)을 회원화 인프라보다 **선행 또는 동시** 진행 권고 — §10 P-3 와 동일 결론.
- **프라이버시 default 승계**(모든 단계): 학습 조회(투명성)/초기화/opt-out 3종 통제 + 음역대·취향 원문 비노출(`04-security-policy.md §2·§3`) 유지. 회원가입 강제 금지(익명 완주 보장).

## 7) 테스트 전략

> 본 spec 은 plan 산출이라 직접 테스트 코드는 없다. 자식 spec 구현 PR 시 본 spec 의 권고 가드 회귀 테스트만 명시.

- **회귀 가드 R-1**(비회원 첫 추천 흐름 무중단): `first-user-onboarding-flow.md` 의 5 시나리오 E2E 가 회원화 도입 전/후 동일 통과. 자식 spec PR C(fe 모달) 머지 시 필수 게이트.
- **회귀 가드 R-2**(결정성 보존): `recommendation-algorithm-v1.md §3 결정성` E2E 가 회원/비회원 둘 다 같은 입력 → 같은 결과. 자식 spec PR E(머지) 머지 시 필수 게이트.
- **신규 E2E (자식 spec PR 영역)**: 좋아요 클릭 → 모달 → OAuth 로그인 mock → 머지 성공 → 좋아요 반영 → 디바이스 변경 시뮬 → 같은 계정 로그인 → 좋아요 보임. `anonymous-to-account-conversion.md §7` 에 박제.

## 8) 오픈 질문

| # | 질문 | 선택지 | 본 spec 권고 / 담당 |
|---|---|---|---|
| Q1 | 본 spec 권고(B+D 조합) 채택 여부 | (a) 채택(default) / (b) C(필수) 선택 / (c) A(회원화 보류) / (d) 다른 조합 | (a) default — nmae 가 사용자 결정 받음(§11 D-1) |
| Q2 | 이메일/패스워드 활성화 시점 | (a) v0.4 P1 동시 / (b) v0.4 P2 후순위(default) / (c) v0.5+ 지연 | (b) default — `user-authentication-and-profile.md §8 Q1` 와 단일 결정 |
| Q3 | dismissible banner 도입 | (a) 도입 + A/B test(default 의 default off) / (b) 즉시 도입 / (c) 미도입 | (a) default — 카운터 검증 후 결정 |
| Q4 | OAuth provider 간 동일 사용자 식별·연결 (R2) | (a) v0.4 범위 외(default) / (b) v0.4 P2 별 spec / (c) v0.5+ | (a) default — `anonymous-to-account-conversion.md` 의 머지 알고리즘이 sessionId→userId 만 다룸 정합 |
| Q5 | 회원 sign-out 시 sessionId 잔존 정책 (S6) | (a) sign-out = sessionId 도 revoke(공유 디바이스 보호) / (b) sessionId 유지(편의) / (c) 사용자 모달 선택 | 결정 보류 — §11 D-3 |
| Q6 | 브라우저 로컬 저장(B)을 "서버에 취향 미전송" 프라이버시 보수 옵션으로 도입할지 (§5-4) | (a) 미도입(default — A 익명 식별자로 충분) / (b) 옵트인 보조 수단으로 도입(서버측 학습 비활성 + 클라이언트 랭킹 fallback) / (c) v0.5+ 재검토 | (a) default — 서버측 결정성 계약(`recommendation-algorithm-v1.md §3`)과 상충, 수요 미확인. nmae 가 사용자 결정(§11) 받기 전 채택 보류 |

해소된 질문은 §9 결정 로그로 이동.

## 9) 결정 로그

- **2026-06-03 (plan, 본 PR / directive 2026-06-03)**: 초안 작성 (status=draft).
  - **메타-검토 spec 신설 사유**: 기존 4개 자식 spec(`user-authentication-and-profile.md` / `anonymous-to-account-conversion.md` / `preference-learning-personalization.md` / `first-user-onboarding-flow.md`)이 각각의 SoT 책임으로 작성되어 있어 사용자(@goohong)가 "회원화 전체를 개인화 관점에서 한 번에 평가" 하기 어려운 구조였다. 본 spec 은 자식 spec 의 §3·§5 요구사항을 **개인화 가치 매트릭스(§5-1) + 마찰 비용 매트릭스(§5-2) + 옵션 비교(§6)** 의 단일 축으로 재해석한다. **자식 spec 의 내용을 재명세하지 않음(§1-3 SoT 경계).**
  - **가치 평가 합계 23/25**: 5차원 중 4차원이 결정적 가치(5), 1차원이 명확한 가치(4). 회원화 도입의 가치 증분은 **개인화 시간 지평을 ≤180일 / sessionId 수명 → 영구 + 디바이스 횡단**으로 확장하는 데 있다.
  - **마찰 결정 변수 = 트리거 정책(F3)**: 첫 진입 강제(C) = 30-50% 이탈, 가치 지점 트리거(B) = 5-15% 이탈. 마찰 매트릭스에서 F3 의 격차가 가장 크므로 트리거 정책 선택이 도입 ROI 의 결정 변수.
  - **권고 = B + D 조합**: B(가치 지점 트리거) + D(OAuth-only 우선, 이메일 후순위). 첫 추천 도달 흐름 무영향 + 사용자가 가치 본 시점에서만 sign-up 비용 청구 + v0.4 P1 출시 속도 개선.
  - **자식 spec 영향**: §10 표 참조. 본 spec 권고 채택 시 자식 spec §8 의 Q1·Q3·Q4 가 닫힐 수 있다(자식 spec 별 PR — 본 PR 에서 자식 직접 편집 안 함).
  - **R1 risk 박제**: 익명 학습(`preference-learning-personalization.md`) 미구현 시 V3 가치 0 → 합계 18/25 로 떨어짐. nmae 가 자식 spec PR 순서 조정(익명 학습 선행 또는 동시).
  - **`06-domain-model.md §7 D4`("회원가입 필수 vs 익명 시작") 방향**: 본 spec §6-2 권고로 **익명 1순위 + 가치 지점 트리거**가 방향성 박제(§7 표는 canonical, 본 PR 에서 미편집 — feedback_domain_model_section_7 룰). nmae 가 사용자 결정(§11 D-1) 받은 뒤 표 항목 갱신 책임.
  - **directive 명시 항목 2종 보강 (§5-4 / §6-4)**: directive 검토 항목의 "대안 비교(회원화 vs 비회원 로컬 저장 vs 익명 식별자)" 와 "단계적 도입 마일스톤(익명→경량 계정→풀 계정)" 을 각각 §5-4·§6-4 로 명시 추가. **핵심 분석 = 개인화 효용의 2분리**: ① 같은 디바이스 재방문 개인화(A 익명/B 로컬로 인증 비용 0 달성) / ② 디바이스 횡단 + 데이터 내구(회원화의 유일 고유 가치). 결론: **A 를 개인화 기본값 유지**, C(회원화)는 ② 가치 지점에서만 점진 도입, B(브라우저 로컬)는 서버측 결정성 계약과 상충해 독립 채택 안 함(§8 Q6 보조 옵션 여지만). 마일스톤 M0(익명 baseline) → M1(경량 OAuth-only 계정, ② 전환) → M2(풀 계정) 순서로, **M0 익명 학습 spec 구현이 회원화 인프라보다 선행/동시**(R1 mitigation).

## 10) 자식 spec 영향 분석 (권고 채택 시)

> 본 spec 권고(B+D)가 채택되면 자식 spec 의 §8 오픈 질문 일부가 닫힌다. **자식 spec 직접 편집은 본 PR 에서 하지 않음** — 중복·드리프트 risk + rev 사이클 단위 분리. 자식 spec 갱신은 본 spec 머지 후 별 PR(plan 후속 사이클).

| # | 자식 spec | 영향 섹션 | 갱신 내용 | 담당 |
|---|---|---|---|---|
| P-1 | `user-authentication-and-profile.md` | §8 Q1 | 옵션 (a) "카카오+구글 우선, 이메일 후순위" 채택 박제 — 본 spec §9 cross-ref | plan 후속 사이클 |
| P-2 | `anonymous-to-account-conversion.md` | §3-B, §3-C, §8 Q1 | 트리거 정책 B 채택 박제. OAuth-only 우선 권고 — 본 spec §9 cross-ref | plan 후속 사이클 |
| P-3 | `preference-learning-personalization.md` | §6 작업 분할 | 회원화(`user-authentication-and-profile.md` / `anonymous-to-account-conversion.md`) PR 보다 **선행 또는 동시 구현** 필요(R1 mitigation). PR 순서 cross-ref 추가 | plan 후속 사이클 |
| P-4 | `first-user-onboarding-flow.md` | §3 비기능 | 회원화 도입 후에도 5 시나리오 E2E 회귀 가드 명시(P-0 호환) — 본 spec §3 비기능 cross-ref | plan 후속 사이클 |
| P-5 | `06-domain-model.md §7 D4` | 표 행 | 방향성 박제(익명 1순위 + 가치 지점 트리거). §7 표 자체 canonical — nmae 가 사용자 결정 후 갱신 | nmae (사용자 결정 받은 뒤) |

## 11) 사용자 결정 필요 항목 (nmae 가 사용자에게 ask)

> sub-agent 는 `AskUserQuestion` 금지(sub-agent 룰 §1-2). 아래 항목은 본 spec 본문에 박제해 두고 **nmae 가 본 PR 머지 후 사용자(@goohong)에게 직접 ask** → 답을 §9 결정 로그에 추가 → 자식 spec §8 갱신 PR dispatch.

| # | 결정 항목 | 선택지 | 본 spec default | 영향 자식 spec |
|---|---|---|---|---|
| **D-1** | 본 spec 권고(B+D 조합) 채택 여부 | (a) 채택(B+D) / (b) C 필수 sign-up / (c) A 회원화 보류 / (d) 다른 조합 | (a) | 전체 |
| **D-2** | OAuth provider 추가(카카오/구글 외) | (a) 카카오+구글만(v0.4) / (b) 애플 추가(iOS 사용자) / (c) 네이버 추가(국내) | (a) v0.4 | `user-authentication-and-profile.md` |
| **D-3** | 회원 sign-out 시 sessionId 정책 | (a) sessionId 도 revoke(공유 디바이스 보호) / (b) sessionId 유지(편의) / (c) 사용자 모달 선택 | 결정 보류 | `anonymous-to-account-conversion.md` (별 spec 신설 후보) |
| **D-4** | dismissible banner 즉시 도입 여부 | (a) 도입 후 A/B test / (b) 즉시 도입 / (c) 미도입 | (a) | `anonymous-to-account-conversion.md` §3-B |
| **D-5** | OAuth provider 간 동일 사용자 연결(같은 이메일이 카카오/구글 둘 다 로그인) | (a) v0.4 범위 외 / (b) v0.4 P2 별 spec / (c) v0.5+ | (a) | `anonymous-to-account-conversion.md` 후속 spec |

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 — 본 PR 은 docs only. 본 spec 권고 채택 시 **자식 spec 의 구현 PR 단계에서** 보호 영역 변경(`backend/build.gradle` Spring Security 의존 / `application.yml` OAuth client secret / Flyway migration `create_user_profile.sql`). 자식 spec §6 참조.

## 12) 참고

- `docs/features/user-authentication-and-profile.md` — 인증 메커니즘 + 영속 프로필 SoT
- `docs/features/anonymous-to-account-conversion.md` — 비회원/회원 정책 + 머지 알고리즘 SoT
- `docs/features/preference-learning-personalization.md` — 익명 sessionId 단위 implicit 학습 SoT
- `docs/features/first-user-onboarding-flow.md` — 3 페르소나 진입 경로 + 첫 추천 도달
- `docs/features/recommendation-algorithm-v1.md` — 추천 점수 산식 SoT (본 spec 권고는 산식 변경 없음 — 회귀 가드)
- `docs/ai-harness/06-domain-model.md §4 / §5-7 / §5-8 / §7 D4` — 도메인 용어 + `User`/`UserProfile` 엔티티 + D4 오픈 이슈
- `docs/decisions/0011-session-bound-history-authentication.md` — sessionId 기반 인증 (회원 인증과 별 트랙 공존)
- `docs/decisions/0013-sessionid-ttl-rotation.md §D-4` — sessionId → user_id 머지 (자동 머지 1회 수행)
- `CLAUDE.md §4` 공통 행동 룰 + `04-security-policy.md` (시크릿 외부화 / sessionId 원문 비노출)
