/**
 * PII 마스킹 + safeLog 래퍼.
 *
 * 배경 (PR #129 / closes #123 #128):
 *   - rev 사이클 9에서 `console.error(error)` 패턴이 응답 객체를 그대로 dump해서
 *     `sessionId`, `voiceRangeId` 등 식별자를 콘솔/외부 로거에 누출할 위험이
 *     지적됨.
 *   - CLAUDE.md §4 보안: "사용자 음역대·기호 데이터는 로그/코멘트/스크린샷에
 *     원문 노출 금지." → 식별자류 키는 마스킹, 음역대 raw MIDI는 메타데이터로
 *     판단해 마스킹하지 않는다.
 *
 * 정책:
 *   - 객체/배열은 깊은 복제로 마스킹 (원본 mutation 금지).
 *   - 마스킹 대상 키: `sessionId`, `voiceRangeId`, `userId`, `email`, `phone`,
 *     `token`, `accessToken`, `refreshToken`, `requestId`.
 *     → 문자열은 마지막 4자리만 노출(`****abcd`), 4자 이하/숫자는 `****`.
 *   - **민감하지 않은 키** (마스킹 안 함): `lowMidi`, `highMidi`, `voiceRangeLow`,
 *     `voiceRangeHigh`, BPM, score 등 음악 메타데이터.
 *   - 순환 참조는 WeakSet으로 끊고 `"[Circular]"`로 표기.
 *   - Error 객체는 `name`/`message`만 보존, stack은 운영 환경에서 축약.
 *
 * 사용:
 *   ```ts
 *   import { safeLog } from "@/lib/logging";
 *   safeLog.error("[recommend] mutation failed", error);
 *   ```
 *
 * 직접 `console.error`를 호출하지 말 것. ESLint(`no-console`)로 강제한다.
 */

/**
 * 마스킹 대상 키 화이트리스트 (정확 매칭, 대소문자 구분).
 *
 * 키 이름에 "Id"/"id"가 포함된다고 무조건 마스킹하지는 않는다. 곡 `id`나
 * `requestId`는 누출되면 추적 가능하지만 식별자 매핑이 어려운 정수라 안전하다.
 * 단, `requestId`는 추천 결과 추적용 외부 키라 보수적으로 포함.
 */
const SENSITIVE_KEYS: ReadonlySet<string> = new Set([
  "sessionId",
  "voiceRangeId",
  "userId",
  "email",
  "phone",
  "token",
  "accessToken",
  "refreshToken",
  "requestId",
]);

/**
 * Stack trace 최대 라인 수 (운영 환경).
 *
 * 개발 환경에서는 풀 스택을 보여줘야 디버깅이 가능하지만, 운영에서는 외부 로거가
 * 받기 전에 PII가 섞일 가능성이 있어 축약. 본 PR 범위는 콘솔까지만이라
 * 추후 Sentry 등 도입 시 별도로 sanitize 한다.
 */
const PROD_STACK_LINE_LIMIT = 3;

const MASK_SUFFIX_LEN = 4;

function isProd(): boolean {
  return process.env.NODE_ENV === "production";
}

function maskString(value: string): string {
  if (value.length <= MASK_SUFFIX_LEN) {
    return "****";
  }
  return `****${value.slice(-MASK_SUFFIX_LEN)}`;
}

function maskScalar(value: unknown): unknown {
  if (typeof value === "string") {
    return maskString(value);
  }
  // 숫자 ID 등은 길이/내용에 의미가 있을 수 있어 일괄 `****`.
  return "****";
}

/**
 * Error 객체를 일반 객체로 평탄화. stack은 운영 환경에서 축약.
 *
 * Next.js의 error.tsx가 받는 `Error & { digest?: string }` 같은 확장 필드도
 * 보존하기 위해 own enumerable property를 함께 흡수한다.
 */
function flattenError(
  error: Error,
  seen: WeakSet<object>,
): Record<string, unknown> {
  const flat: Record<string, unknown> = {
    name: error.name,
    message: error.message,
  };

  if (error.stack) {
    if (isProd()) {
      const lines = error.stack.split("\n").slice(0, PROD_STACK_LINE_LIMIT);
      flat.stack = `${lines.join("\n")}\n  ... (truncated in production)`;
    } else {
      flat.stack = error.stack;
    }
  }

  // Error 객체의 추가 속성(digest, cause, ApiError.status/body 등).
  for (const key of Object.keys(error)) {
    flat[key] = maskValue((error as unknown as Record<string, unknown>)[key], seen);
  }

  return flat;
}

function maskValue(value: unknown, seen: WeakSet<object>): unknown {
  if (value === null || value === undefined) {
    return value;
  }

  const valueType = typeof value;
  if (
    valueType === "string"
    || valueType === "number"
    || valueType === "boolean"
    || valueType === "bigint"
  ) {
    return value;
  }

  if (valueType === "function" || valueType === "symbol") {
    // 로그에 함수/심볼은 노출하지 않는다.
    return `[${valueType}]`;
  }

  if (value instanceof Error) {
    if (seen.has(value)) return "[Circular]";
    seen.add(value);
    return flattenError(value, seen);
  }

  if (Array.isArray(value)) {
    if (seen.has(value)) return "[Circular]";
    seen.add(value);
    return value.map((item) => maskValue(item, seen));
  }

  if (valueType === "object") {
    const obj = value as Record<string, unknown>;
    if (seen.has(obj)) return "[Circular]";
    seen.add(obj);

    const masked: Record<string, unknown> = {};
    for (const key of Object.keys(obj)) {
      if (SENSITIVE_KEYS.has(key)) {
        masked[key] = maskScalar(obj[key]);
      } else {
        masked[key] = maskValue(obj[key], seen);
      }
    }
    return masked;
  }

  return value;
}

/**
 * 임의의 값을 PII 마스킹해서 반환. 원본은 변경하지 않는다.
 *
 * - primitive(문자열/숫자/불린/bigint/null/undefined)는 그대로 반환.
 * - Error는 `{ name, message, stack, ...extra }` 형태로 평탄화.
 * - 객체/배열은 깊은 복제 후 `SENSITIVE_KEYS`에 해당하는 키만 마스킹.
 * - 순환 참조는 `"[Circular]"`로 끊는다.
 */
export function maskPII(value: unknown): unknown {
  return maskValue(value, new WeakSet());
}

type LogArgs = readonly unknown[];

function emit(
  level: "error" | "warn" | "info" | "debug",
  message: string,
  args: LogArgs,
): void {
  const maskedArgs = args.map((arg) => maskPII(arg));
  // eslint-disable-next-line no-console -- 본 모듈만 console.* 직접 사용 허용.
  console[level](message, ...maskedArgs);
}

/**
 * 콘솔 로깅 래퍼.
 *
 * 모든 추가 인자는 `maskPII`로 마스킹된 뒤 `console.<level>`에 전달된다.
 * 첫 번째 인자(`message`)는 운영자가 의도적으로 작성한 문자열이라 마스킹하지
 * 않는다. **자유 텍스트에 PII를 직접 끼우지 말 것.**
 */
export const safeLog = {
  error(message: string, ...args: LogArgs): void {
    emit("error", message, args);
  },
  warn(message: string, ...args: LogArgs): void {
    emit("warn", message, args);
  },
  info(message: string, ...args: LogArgs): void {
    emit("info", message, args);
  },
  debug(message: string, ...args: LogArgs): void {
    emit("debug", message, args);
  },
} as const;
