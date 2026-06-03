/**
 * SongCard 내부 helpers 회귀 가드 (closes #512).
 *
 * 두 helper는 카드 표면 표시 분기의 핵심이라 작은 회귀가 곧 잘못된 라벨로 이어진다.
 * UI 렌더 테스트는 `SongCard.test.tsx`에 있고, 본 파일은 순수 함수 분기만 다룬다.
 */

import { describe, expect, it } from "vitest";

import { formatMusicalKey, getSongHue, resolveDifficulty } from "./SongCard";
import type { SongResponse } from "@/lib/api/recommendation";

function buildSong(overrides: Partial<SongResponse>): SongResponse {
  return {
    id: 1,
    title: "곡",
    artist: "가수",
    releaseYear: null,
    keyOriginal: "C_MAJOR",
    bpm: null,
    mood: null,
    language: null,
    genre: null,
    tjNumber: null,
    kyNumber: null,
    metadataSource: "MANUAL_SEED",
    ...overrides,
  };
}

describe("formatMusicalKey", () => {
  it("SHARP enum 토큰을 '#' 으로 치환하고 첫 글자만 대문자로 만든다", () => {
    expect(formatMusicalKey("C_SHARP_MAJOR")).toBe("C# Major");
  });

  it("UNKNOWN 은 한글이 아닌 'Unknown' 으로 고정 표기한다", () => {
    expect(formatMusicalKey("UNKNOWN")).toBe("Unknown");
  });

  it("일반 MAJOR/MINOR enum 도 첫 글자만 대문자로 변환한다", () => {
    expect(formatMusicalKey("A_MINOR")).toBe("A Minor");
    expect(formatMusicalKey("G_MAJOR")).toBe("G Major");
  });
});

describe("resolveDifficulty", () => {
  it("BE 가 difficulty 를 내려주면 그 값을 그대로 사용한다", () => {
    const song = buildSong({ difficulty: "HARD", lowMidi: 50, highMidi: 60 });
    expect(resolveDifficulty(song)).toBe("HARD");
  });

  it("difficulty 가 없으면 lowMidi/highMidi 로 derive 한다 (HARD 분류)", () => {
    const song = buildSong({ lowMidi: 60, highMidi: 80 });
    expect(resolveDifficulty(song)).toBe("HARD");
  });

  it("difficulty 가 없고 음역이 낮으면 EASY 로 derive 한다", () => {
    const song = buildSong({ lowMidi: 55, highMidi: 65 });
    expect(resolveDifficulty(song)).toBe("EASY");
  });

  it("difficulty 도 음역도 없으면 null 을 돌려준다 (legacy 응답 안전)", () => {
    const song = buildSong({});
    expect(resolveDifficulty(song)).toBeNull();
  });
});

// closes #1683 — 좌측 accent stripe hue 결정성. 같은 songId → 항상 같은 hue 라야
// 곡 색이 렌더마다 흔들리지 않는다.
describe("getSongHue", () => {
  it("같은 songId 는 항상 같은 hue 를 돌려준다 (결정성)", () => {
    expect(getSongHue(12345)).toBe(getSongHue(12345));
    expect(getSongHue(7)).toBe(getSongHue(7));
  });

  it("hue 는 항상 0 이상 360 미만 정수다", () => {
    for (const id of [0, 1, 9, 42, 12345, 987654]) {
      const hue = getSongHue(id);
      expect(Number.isInteger(hue)).toBe(true);
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThan(360);
    }
  });

  it("char code 합 % 360 으로 산출한다 (분산 확인)", () => {
    // "12" → '1'(49) + '2'(50) = 99
    expect(getSongHue(12)).toBe(99);
    // 서로 다른 id 는 (일반적으로) 다른 hue — 회귀 시 상수 반환을 잡는다.
    expect(getSongHue(12)).not.toBe(getSongHue(99));
  });
});
