/**
 * zustand-persist custom rule 단위 테스트 (#1108).
 *
 * ESLint RuleTester 를 vitest 와 통합 — RuleTester 의 describe/it 훅을 vitest 로
 * 주입해 각 케이스가 vitest 테스트로 등록되게 한다.
 */

import { RuleTester } from "eslint";
import { afterAll, describe, it } from "vitest";

import zustandPersist from "./zustand-persist.mjs";

// RuleTester.afterAll 은 eslint 타입 선언에 없어 cast 로 주입.
(RuleTester as unknown as { afterAll: typeof afterAll }).afterAll = afterAll;
RuleTester.describe = describe;
RuleTester.it = it;
RuleTester.itOnly = it.only;

const ruleTester = new RuleTester({
  languageOptions: {
    ecmaVersion: 2022,
    sourceType: "module",
  },
});

ruleTester.run(
  "persist-version-required",
  zustandPersist.rules["persist-version-required"],
  {
    valid: [
      // version 명시 — 통과.
      {
        code: `persist(creator, { name: "s", version: 1, migrate: (p) => p });`,
      },
      // 문자열 키로 version 명시 — 통과.
      { code: `persist(creator, { "version": 2, name: "s" });`,
      },
      // persist 가 아닌 다른 호출 — 무시.
      { code: `wrap(creator, { name: "s" });` },
      // 옵션이 동적 객체(정적 분석 불가) — 무시.
      { code: `persist(creator, options);` },
    ],
    invalid: [
      // version 누락 — error.
      {
        code: `persist(creator, { name: "s", storage });`,
        errors: [{ messageId: "missingVersion" }],
      },
      // 옵션 인자 자체가 없음 — error.
      {
        code: `persist(creator);`,
        errors: [{ messageId: "missingOptions" }],
      },
      // computed key 는 version 으로 인정하지 않음 — error.
      {
        code: `persist(creator, { [versionKey]: 1, name: "s" });`,
        errors: [{ messageId: "missingVersion" }],
      },
    ],
  },
);

ruleTester.run(
  "persist-migrate-recommended",
  zustandPersist.rules["persist-migrate-recommended"],
  {
    valid: [
      // migrate 명시 — 통과.
      {
        code: `persist(creator, { name: "s", version: 1, migrate: (p) => p });`,
      },
      // persist 아님 — 무시.
      { code: `compose(creator, { name: "s" });` },
    ],
    invalid: [
      // migrate 누락 — warn(report).
      {
        code: `persist(creator, { name: "s", version: 1 });`,
        errors: [{ messageId: "missingMigrate" }],
      },
    ],
  },
);
