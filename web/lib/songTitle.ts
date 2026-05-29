/**
 * 곡 표시 제목 정렬 헬퍼 (이슈 #1284 — 사용자 directive 2026-05-29).
 *
 * 한국 곡(`language === "ko"` 또는 title 에 한글 포함)이 영어 표기로만 들어와 있는
 * 경우 표시에 어색함이 있어, **한국어 표기를 primary 로 노출**하기 위한 single SoT.
 *
 * 현재 BE `SongResponse` 는 한국어 별칭(`titleKo`) 필드를 제공하지 않으므로
 * (`backend/src/main/resources/songs-seed.json` evidence: "Spring Day" 가 `ko` 곡으로
 * 등록), fe 단독으로 영어 title 을 한국어로 번역하지는 못한다.
 *
 * 본 헬퍼가 책임지는 것:
 *   1. title 이 "영어 (한글)" / "영어 - 한글" / "영어 — 한글" 같은 dual-name 형태이면
 *      → 한국 곡에 한해 한글 부분을 앞으로 swap.
 *   2. title 이 단일 표기(전부 한글 또는 전부 영어) 면 그대로 반환.
 *   3. 한국 곡 판정: `language === "ko"` (case-insensitive) 우선, 없으면 title 에
 *      한글 포함 여부 fallback.
 *
 * 본 헬퍼는 후속(한국어 별칭 BE 필드 도입 / 한글-영문 토글 UI) 의 단일 진입점이다 —
 * 표시 site 들이 모두 `formatSongDisplayTitle(song)` 만 호출하도록 유지하면 그때
 * 본 함수 안에서만 분기를 늘리면 된다.
 */

export type SongDisplayMeta = {
  title: string;
  language?: string | null;
};

/**
 * Unicode Hangul Syllables (AC00–D7A3) + Jamo (1100–11FF) + Compat Jamo (3130–318F)
 * 중 syllables 가 가장 일반적이라 충분 — 검출 목적이므로 false-positive 가 보수적이다.
 */
const HANGUL_PATTERN = /[가-힣ᄀ-ᇿ㄰-㆏]/;

/**
 * "English (한글)" / "English - 한글" / "English — 한글" / "English – 한글" 분리.
 *
 * 한국 곡에서 사용자에게 한글이 보이는 것이 의도. 단, 두 토큰 모두 비어 있지
 * 않을 때만 swap — 빈 토큰이 나오면 원본을 그대로 둔다 (안전).
 *
 * unnamed capture group 사용 이유: tsconfig `target: ES2017` 환경에서 named
 * capturing group (`(?<name>...)`) 은 ES2018 syntax 라 TS1503 으로 차단된다.
 */
const DUAL_NAME_PATTERN = /^([^()\-—–]+?)\s*[(\-—–]\s*([^()]+?)[)]?\s*$/u;

function hasHangul(text: string): boolean {
  return HANGUL_PATTERN.test(text);
}

/**
 * 한국 곡 여부. language metadata 가 있으면 그것이 SoT, 없으면 title 의 한글 포함
 * 여부로 fallback.
 *
 * 노출 이유: 카드 UI / 정렬 / 라벨 분기 등에서 동일한 판정을 재사용해야 하는
 * call site 가 생길 수 있으므로 single SoT 로 export.
 */
export function isKoreanSong(song: SongDisplayMeta): boolean {
  const language = song.language?.trim().toLowerCase() ?? "";
  if (language === "ko" || language === "kor" || language === "korean") {
    return true;
  }
  if (language.length > 0) {
    // 명시적으로 다른 언어가 박혀 있으면 한국 곡 아님.
    return false;
  }
  return hasHangul(song.title);
}

/**
 * 표시용 제목.
 *
 * - 비-한국 곡: 원본 그대로.
 * - 한국 곡:
 *     · title 에 한글이 포함된 단일 표기 → 그대로.
 *     · "English (한글)" / "English - 한글" 패턴 → 한글 부분을 앞으로 swap.
 *     · title 이 전부 영어 (한글 부재) → 그대로 (BE 한국어 별칭 부재, 후속 PR 대상).
 *
 * 빈 문자열 / whitespace-only 입력은 원본을 그대로 돌려준다 (방어).
 */
export function formatSongDisplayTitle(song: SongDisplayMeta): string {
  const raw = song.title ?? "";
  if (raw.trim().length === 0) {
    return raw;
  }
  if (!isKoreanSong(song)) {
    return raw;
  }
  // 단일 표기에 한글이 이미 들어 있으면 swap 불필요.
  if (hasHangul(raw) && !DUAL_NAME_PATTERN.test(raw)) {
    return raw;
  }
  const match = DUAL_NAME_PATTERN.exec(raw);
  if (!match) {
    return raw;
  }
  const first = (match[1] ?? "").trim();
  const second = (match[2] ?? "").trim();
  if (first.length === 0 || second.length === 0) {
    return raw;
  }
  const firstHasHangul = hasHangul(first);
  const secondHasHangul = hasHangul(second);
  // 한국 곡인데 한글 토큰이 뒤에 있으면 앞으로 swap.
  if (!firstHasHangul && secondHasHangul) {
    return `${second} (${first})`;
  }
  // 한국 곡인데 두 토큰 모두 한글 없음 — swap 의미 없음 (BE 한국어 별칭 부재).
  return raw;
}
