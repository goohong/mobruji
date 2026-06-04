/**
 * P-D 시퀀스 헬퍼 단위 가드 (이슈 #1601).
 *
 * - nextStage: 워밍업→고조→마무리 진행, 마지막은 null.
 * - stageMeta: 단계별 라벨/설명.
 */

import { describe, expect, it } from "vitest";

import { nextStage, SEQUENCE_STAGE_ORDER, stageMeta } from "./sequence";

describe("SEQUENCE_STAGE_ORDER", () => {
  it("워밍업→고조→마무리 순서를 BE ordinal 정합으로 둔다", () => {
    expect(SEQUENCE_STAGE_ORDER).toEqual(["WARMUP", "PEAK", "CLOSING"]);
  });
});

describe("nextStage", () => {
  it("워밍업→고조→마무리 순서로 진행한다", () => {
    expect(nextStage("WARMUP")).toBe("PEAK");
    expect(nextStage("PEAK")).toBe("CLOSING");
  });

  it("마지막 단계(마무리)에서는 null 을 돌려준다", () => {
    expect(nextStage("CLOSING")).toBeNull();
  });
});

describe("stageMeta", () => {
  it("단계별 한국어 라벨을 돌려준다", () => {
    expect(stageMeta("WARMUP").label).toBe("워밍업");
    expect(stageMeta("PEAK").label).toBe("고조");
    expect(stageMeta("CLOSING").label).toBe("마무리");
  });
});
