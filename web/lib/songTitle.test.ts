/**
 * 곡 표시 제목 정렬 헬퍼 회귀 가드 (이슈 #1284).
 *
 * - 한국 곡 / 비-한국 곡 분기
 * - 단일 표기 vs dual-name (English (한글)) 분기
 * - language 부재 시 한글 포함 fallback
 * - 방어: 빈 입력 / whitespace-only
 *
 * sub-agent.md §1-3 검증 의무: 정렬 룰이 회귀하지 않도록 표시 site 와 같이
 * 보호 (`SongCard`, `SongDetailContent`, `songs/[id]/page` 모두 본 헬퍼만 호출).
 */

import { describe, expect, it } from "vitest";

import { formatSongDisplayTitle, isKoreanSong } from "./songTitle";

describe("isKoreanSong", () => {
  it("language === ko → true", () => {
    // given
    const song = { title: "Spring Day", language: "ko" };
    // when / then
    expect(isKoreanSong(song)).toBe(true);
  });

  it("language === KOR (대문자) 도 true (case-insensitive)", () => {
    expect(isKoreanSong({ title: "Spring Day", language: "KOR" })).toBe(true);
    expect(isKoreanSong({ title: "Spring Day", language: "Korean" })).toBe(true);
  });

  it("language === en → false (한글 포함 여부 무시)", () => {
    // 명시 metadata 가 SoT — 한글 포함이라도 비한국 곡으로 본다.
    expect(isKoreanSong({ title: "Some 어떤", language: "en" })).toBe(false);
  });

  it("language null + title 한글 포함 → true (fallback)", () => {
    expect(isKoreanSong({ title: "벚꽃 엔딩", language: null })).toBe(true);
  });

  it("language null + 영어 title → false (fallback)", () => {
    expect(isKoreanSong({ title: "Dynamite", language: null })).toBe(false);
  });

  it("language undefined 도 fallback 으로 처리", () => {
    expect(isKoreanSong({ title: "벚꽃 엔딩" })).toBe(true);
    expect(isKoreanSong({ title: "Dynamite" })).toBe(false);
  });
});

describe("formatSongDisplayTitle", () => {
  describe("비-한국 곡 — 원본 그대로", () => {
    it("영어 곡 title 그대로", () => {
      expect(
        formatSongDisplayTitle({ title: "Dynamite", language: "en" }),
      ).toBe("Dynamite");
    });

    it("일본 곡 (language jp) title 그대로 — 한글 포함이어도 보존", () => {
      // 일본 곡 metadata 가 명시되면 swap 하지 않음.
      expect(
        formatSongDisplayTitle({ title: "Title (제목)", language: "jp" }),
      ).toBe("Title (제목)");
    });
  });

  describe("한국 곡 단일 표기 — 원본 그대로", () => {
    it("한글 단일 title 그대로", () => {
      expect(
        formatSongDisplayTitle({ title: "벚꽃 엔딩", language: "ko" }),
      ).toBe("벚꽃 엔딩");
    });

    it("영어 단일 title 도 그대로 — BE 한국어 별칭 부재로 swap 불가", () => {
      // 사용자 directive: 본 PR 은 정렬만, BE 한국어 별칭 backfill 은 follow-up.
      expect(
        formatSongDisplayTitle({ title: "Spring Day", language: "ko" }),
      ).toBe("Spring Day");
    });
  });

  describe("한국 곡 dual-name 패턴 — 한글 우선 swap", () => {
    it("'English (한글)' → '한글 (English)' swap", () => {
      expect(
        formatSongDisplayTitle({
          title: "Spring Day (봄날)",
          language: "ko",
        }),
      ).toBe("봄날 (Spring Day)");
    });

    it("'English - 한글' → '한글 (English)' swap (구분자 hyphen)", () => {
      expect(
        formatSongDisplayTitle({
          title: "Cheer Up - 치얼 업",
          language: "ko",
        }),
      ).toBe("치얼 업 (Cheer Up)");
    });

    it("'English — 한글' → '한글 (English)' swap (em-dash)", () => {
      expect(
        formatSongDisplayTitle({
          title: "Some — 썸",
          language: "ko",
        }),
      ).toBe("썸 (Some)");
    });

    it("'한글 (English)' 은 이미 한글 우선 → 그대로", () => {
      // 한글이 이미 앞에 있으면 dual-name 패턴이라도 swap 하지 않음 (no-op).
      expect(
        formatSongDisplayTitle({
          title: "봄날 (Spring Day)",
          language: "ko",
        }),
      ).toBe("봄날 (Spring Day)");
    });

    it("두 토큰 모두 한글 없음 → 그대로 (swap 불가)", () => {
      expect(
        formatSongDisplayTitle({
          title: "Foo (Bar)",
          language: "ko",
        }),
      ).toBe("Foo (Bar)");
    });
  });

  describe("language fallback (metadata 부재)", () => {
    it("language 없는 dual-name 도 한글 포함이면 swap", () => {
      expect(
        formatSongDisplayTitle({ title: "Spring Day (봄날)" }),
      ).toBe("봄날 (Spring Day)");
    });

    it("language null + 영어 only 곡 → 비한국 곡 판정 → 원본", () => {
      expect(formatSongDisplayTitle({ title: "Dynamite" })).toBe("Dynamite");
    });
  });

  describe("방어 케이스", () => {
    it("빈 title → 빈 문자열 원본 유지", () => {
      expect(formatSongDisplayTitle({ title: "", language: "ko" })).toBe("");
    });

    it("whitespace-only → 원본 그대로", () => {
      expect(formatSongDisplayTitle({ title: "   ", language: "ko" })).toBe(
        "   ",
      );
    });

    it("dual-name 인데 토큰 비어 있음 → 원본", () => {
      // "English ()" 같은 비정상 입력은 swap 시도하지 않음.
      expect(
        formatSongDisplayTitle({ title: "Spring Day ()", language: "ko" }),
      ).toBe("Spring Day ()");
    });
  });
});
