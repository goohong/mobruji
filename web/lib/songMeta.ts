/**
 * 곡 메타 표시 헬퍼 (이슈 #1715 디테일 폴리시).
 *
 * BE Song 응답의 일부 필드는 내부 코드값(예: `language="ko"`, `mood="UPBEAT"`,
 * `metadataSource="MANUAL_SEED"`)이라 사용자에게 그대로 노출하면 노이즈가 된다.
 * 사용자 친화 표시를 위해:
 *   - `language`: ISO 639-1 코드를 한국어 라벨로 매핑. 매핑할 수 없으면 `null` → 호출 측이 숨김.
 *   - `mood`: Mood enum(SCREAMING_SNAKE)을 한국어 라벨로 매핑(#1764). 매핑할 수 없으면 `null` → 셀 생략.
 *   - `metadataSource`: 내부 출처(데이터 적재 경로)라 사용자에게 보여 줄 의미가 없어 상세에서 제거.
 *     (라벨이 필요하면 호출 측이 직접 정의 — 본 모듈은 노출 정책상 매핑하지 않는다.)
 */

/** ISO 639-1 언어 코드 → 한국어 라벨. 매핑에 없으면 노출하지 않는다(노이즈 회피). */
const LANGUAGE_LABELS: Readonly<Record<string, string>> = {
  ko: "한국어",
  en: "영어",
  ja: "일본어",
  zh: "중국어",
  es: "스페인어",
  fr: "프랑스어",
  de: "독일어",
  it: "이탈리아어",
  pt: "포르투갈어",
  ru: "러시아어",
};

/**
 * 언어 코드를 한국어 라벨로 변환한다. 빈 값이거나 매핑에 없는 코드는 `null` 을 돌려준다 —
 * 호출 측은 `null` 일 때 "언어" 메타 셀 자체를 그리지 않아 내부 코드 원문 노출을 막는다.
 */
export function formatLanguageLabel(
  language: string | null | undefined,
): string | null {
  if (!language) {
    return null;
  }
  const normalized = language.trim().toLowerCase();
  return LANGUAGE_LABELS[normalized] ?? null;
}

/**
 * BE Mood enum(SCREAMING_SNAKE) → 한국어 라벨 (closes #1764).
 *
 * 상세 등에서 `mood` 를 그대로 그리면 "UPBEAT" 같은 내부 코드값이 사용자에게 노출된다.
 * 필터 칩(RecommendFilters)·히스토리(MOOD_LABELS)와 같은 톤의 한국어 라벨로 변환한다.
 */
const MOOD_LABELS: Readonly<Record<string, string>> = {
  UPBEAT: "신나는",
  CALM: "잔잔한",
  EMOTIONAL: "감성적인",
  POWERFUL: "파워풀한",
  GROOVY: "그루비한",
  NOSTALGIC: "추억의",
};

/**
 * Mood 코드를 한국어 라벨로 변환한다. 빈 값/미매핑 코드는 `null` → 호출 측이 칩 자체를
 * 그리지 않아 내부 코드 원문 노출을 막는다.
 */
export function formatMoodLabel(
  mood: string | null | undefined,
): string | null {
  if (!mood) {
    return null;
  }
  return MOOD_LABELS[mood.trim().toUpperCase()] ?? null;
}
