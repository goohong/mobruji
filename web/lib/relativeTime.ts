/**
 * 한국어 상대 시간 포맷터 (closes #134).
 *
 * 사용 컨텍스트: 추천 히스토리 카드의 "받은 시간" 표시.
 *   - "방금 전" (1분 미만)
 *   - "N분 전" (1시간 미만)
 *   - "N시간 전" (24시간 미만)
 *   - "어제 H시" (24~48시간)
 *   - "M월 D일" (그 이전)
 *
 * 결정 배경:
 *   - Intl.RelativeTimeFormat 도 검토했지만 "어제 3시" 같은 합성 표현은 직접 짜야 했고,
 *     단위가 적어 라이브러리 의존성을 추가할 가치가 약함.
 *   - 시간대는 사용자 브라우저 로컬 타임존을 기준으로 한다 — store 에는 UTC ISO 로
 *     저장되므로, 표시 시점에 `Date` 객체로 변환해 toLocaleString 류 메서드의
 *     기본 동작에 맡긴다.
 *
 * @param input - ISO8601 또는 Date 객체. 잘못된 입력은 "알 수 없음"을 반환.
 * @param now - 비교 기준 시각. 테스트/SSR에서 결정성 확보를 위해 주입 가능. 기본은 Date.now().
 */
export function formatRelativeKorean(
  input: string | Date,
  now: Date = new Date(),
): string {
  const target = input instanceof Date ? input : new Date(input);
  if (Number.isNaN(target.getTime())) {
    return "알 수 없음";
  }

  const diffMs = now.getTime() - target.getTime();

  // 미래 시각(시계가 뒤로 간 경우 등)은 "방금 전"으로 둔다 — 음수 분/시간 표시는 부자연스럽다.
  if (diffMs < 0) {
    return "방금 전";
  }

  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHour = Math.floor(diffMin / 60);

  if (diffMin < 1) {
    return "방금 전";
  }
  if (diffMin < 60) {
    return `${diffMin}분 전`;
  }
  if (diffHour < 24) {
    return `${diffHour}시간 전`;
  }

  // 24~48시간: 같은 "어제" 칭호를 쓰되 시각을 함께 보여줘 분간하기 쉽게 한다.
  // 캘린더 기준 어제와 24시간 전이 다를 수 있지만, v1 정확도로는 "diff 24~48h"로 충분.
  if (diffHour < 48) {
    const hour = target.getHours();
    return `어제 ${hour}시`;
  }

  const month = target.getMonth() + 1;
  const day = target.getDate();
  return `${month}월 ${day}일`;
}
