/**
 * `maskPII` + `safeLog` 단위 테스트.
 *
 * 커버리지:
 *   1. SENSITIVE_KEYS 마스킹 (sessionId/voiceRangeId/userId/email/phone/token)
 *   2. 음악 메타데이터(lowMidi/highMidi/voiceRangeLow 등)는 마스킹하지 않음
 *   3. 중첩 객체/배열 deep mask + 원본 mutation 없음
 *   4. null/undefined/primitive 통과
 *   5. Error 객체 평탄화 (name/message/stack/digest 보존)
 *   6. 순환 참조는 `[Circular]`로 끊기
 *   7. safeLog.* 가 console.* 를 마스킹된 인자로 호출
 *   8. 짧은 식별자(4자 이하)는 전체 `****`
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { maskPII, safeLog } from "./logging";

describe("maskPII", () => {
  it("SENSITIVE_KEYS 의 문자열 값을 마지막 4자리만 노출하도록 마스킹한다", () => {
    const input = {
      sessionId: "session-abcdef-12345678",
      voiceRangeId: "vr-xyzqwerty",
      userId: "u-998877",
      email: "ppiyaki0304@gmail.com",
      phone: "010-1234-5678",
      token: "eyJhbGciOiJIUzI1NiJ9.payload.sig",
      accessToken: "AT-aaaa-bbbb",
      refreshToken: "RT-cccc-dddd",
      requestId: "req-9999",
    };

    const masked = maskPII(input) as Record<string, string>;

    expect(masked.sessionId).toBe("****5678");
    expect(masked.voiceRangeId).toBe("****erty");
    expect(masked.userId).toBe("****8877");
    expect(masked.email).toBe("****.com");
    expect(masked.phone).toBe("****5678");
    expect(masked.token).toBe("****.sig");
    expect(masked.accessToken).toBe("****bbbb");
    expect(masked.refreshToken).toBe("****dddd");
    expect(masked.requestId).toBe("****9999");
  });

  it("음악 메타데이터(lowMidi/highMidi/voiceRangeLow/voiceRangeHigh/bpm/score)는 마스킹하지 않는다", () => {
    const input = {
      lowMidi: 48,
      highMidi: 72,
      voiceRangeLow: 50,
      voiceRangeHigh: 70,
      bpm: 128,
      score: 0.87,
      title: "사랑 했지만",
      sessionId: "session-deadbeef",
    };

    const masked = maskPII(input) as typeof input;

    expect(masked.lowMidi).toBe(48);
    expect(masked.highMidi).toBe(72);
    expect(masked.voiceRangeLow).toBe(50);
    expect(masked.voiceRangeHigh).toBe(70);
    expect(masked.bpm).toBe(128);
    expect(masked.score).toBe(0.87);
    expect(masked.title).toBe("사랑 했지만");
    expect(masked.sessionId).toBe("****beef");
  });

  it("중첩 객체/배열을 deep mask 하고 원본은 mutate 하지 않는다", () => {
    const input = {
      meta: {
        sessionId: "session-keep-aaaa",
        nested: { userId: "user-keep-bbbb", safe: "ok" },
      },
      recommendations: [
        { song: { id: 1, title: "A" }, requestId: "req-cccc-1111" },
        { song: { id: 2, title: "B" }, requestId: "req-cccc-2222" },
      ],
    };

    const masked = maskPII(input) as typeof input;

    expect(masked.meta.sessionId).toBe("****aaaa");
    expect(masked.meta.nested.userId).toBe("****bbbb");
    expect(masked.meta.nested.safe).toBe("ok");
    expect(masked.recommendations[0].requestId).toBe("****1111");
    expect(masked.recommendations[1].requestId).toBe("****2222");
    expect(masked.recommendations[0].song.title).toBe("A");

    // 원본은 그대로.
    expect(input.meta.sessionId).toBe("session-keep-aaaa");
    expect(input.recommendations[0].requestId).toBe("req-cccc-1111");
  });

  it("primitive / null / undefined 는 그대로 통과한다", () => {
    expect(maskPII(null)).toBeNull();
    expect(maskPII(undefined)).toBeUndefined();
    expect(maskPII("hello")).toBe("hello");
    expect(maskPII(42)).toBe(42);
    expect(maskPII(true)).toBe(true);
    expect(maskPII(0)).toBe(0);
  });

  it("Error 객체를 name/message/stack 으로 평탄화하고 digest 같은 추가 필드를 보존한다", () => {
    const err = Object.assign(new Error("boom"), {
      digest: "abc123",
      // ApiError 유사: body 안에 sessionId 들어가 있는 경우 마스킹.
      body: { sessionId: "session-inside-error-zzzz", message: "Bad request" },
      status: 400,
    });

    const masked = maskPII(err) as Record<string, unknown>;

    expect(masked.name).toBe("Error");
    expect(masked.message).toBe("boom");
    expect(typeof masked.stack).toBe("string");
    expect(masked.digest).toBe("abc123");
    expect(masked.status).toBe(400);
    expect((masked.body as Record<string, string>).sessionId).toBe("****zzzz");
    expect((masked.body as Record<string, string>).message).toBe("Bad request");
  });

  it("순환 참조는 [Circular] 로 끊는다", () => {
    type Node = { name: string; self?: Node; sessionId: string };
    const node: Node = { name: "n1", sessionId: "session-self-7777" };
    node.self = node;

    const masked = maskPII(node) as Node & { self: unknown };

    expect(masked.name).toBe("n1");
    expect(masked.sessionId).toBe("****7777");
    expect(masked.self).toBe("[Circular]");
  });

  it("4자 이하 식별자는 전체를 `****` 로 가린다", () => {
    const masked = maskPII({ sessionId: "ab12", userId: "" }) as Record<
      string,
      string
    >;
    expect(masked.sessionId).toBe("****");
    expect(masked.userId).toBe("****");
  });

  it("함수/심볼 값은 [function]/[symbol] 로 대체한다", () => {
    const masked = maskPII({
      onClick: () => undefined,
      tag: Symbol("x"),
      ok: 1,
    }) as Record<string, unknown>;
    expect(masked.onClick).toBe("[function]");
    expect(masked.tag).toBe("[symbol]");
    expect(masked.ok).toBe(1);
  });
});

describe("safeLog", () => {
  const errorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);
  const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
  const infoSpy = vi.spyOn(console, "info").mockImplementation(() => undefined);
  const debugSpy = vi.spyOn(console, "debug").mockImplementation(() => undefined);

  beforeEach(() => {
    errorSpy.mockClear();
    warnSpy.mockClear();
    infoSpy.mockClear();
    debugSpy.mockClear();
  });

  afterEach(() => {
    errorSpy.mockClear();
    warnSpy.mockClear();
  });

  it("safeLog.error 는 console.error 를 호출하고 추가 인자를 마스킹한다", () => {
    safeLog.error("[recommend] mutation failed", {
      sessionId: "session-payload-9999",
      voiceRangeId: "vr-aaaa-bbbb",
      score: 0.42,
    });

    expect(errorSpy).toHaveBeenCalledTimes(1);
    const [message, payload] = errorSpy.mock.calls[0];
    expect(message).toBe("[recommend] mutation failed");
    expect(payload).toEqual({
      sessionId: "****9999",
      voiceRangeId: "****bbbb",
      score: 0.42,
    });
  });

  it("safeLog.warn / info / debug 도 동일하게 마스킹된다", () => {
    safeLog.warn("warn", { email: "test@example.com" });
    safeLog.info("info", { phone: "010-9999-1234" });
    safeLog.debug("debug", { token: "tk-abcdefghij" });

    expect(warnSpy).toHaveBeenCalledWith("warn", { email: "****.com" });
    expect(infoSpy).toHaveBeenCalledWith("info", { phone: "****1234" });
    expect(debugSpy).toHaveBeenCalledWith("debug", { token: "****ghij" });
  });

  it("message 문자열 자체는 마스킹하지 않는다 (운영자가 의도해 작성한 텍스트)", () => {
    safeLog.error("contains sessionId word but message stays as-is");
    expect(errorSpy).toHaveBeenCalledWith(
      "contains sessionId word but message stays as-is",
    );
  });
});
