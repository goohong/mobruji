/**
 * zustand `persist` 미들웨어 옵션 가드 — localStorage 스키마 회귀 방지.
 *
 * 회귀 사고 #1105: `web/store/session.ts` 의 `persist({ name, storage })` 에
 * `version` / `migrate` 가 없어, 영속 상태의 shape 가 바뀌어도 zustand 가 옛
 * localStorage 값을 그대로 hydrate → 구형 seed 사용자 100% 회귀.
 *
 * 두 rule 로 분리(개별 severity 제어):
 *   - `persist-version-required` (error): 옵션 객체에 `version` 키 강제.
 *   - `persist-migrate-recommended` (warn): `migrate` 함수 권장.
 *
 * 한계(의도된 heuristic): callee 이름이 `persist` 인 CallExpression 만 검사한다.
 * zustand 표준 import (`import { persist } from "zustand/middleware"`) 를 전제로
 * 하며, 별칭(alias) import 나 동적 옵션 객체는 정적 분석 대상 밖이다.
 */

/** 옵션 ObjectExpression 이 (computed 아닌) 주어진 키를 갖는지. */
function hasOptionKey(optionsNode, keyName) {
  return optionsNode.properties.some((property) => {
    if (property.type !== "Property" || property.computed) {
      return false;
    }
    const { key } = property;
    if (key.type === "Identifier") {
      return key.name === keyName;
    }
    if (key.type === "Literal") {
      return key.value === keyName;
    }
    return false;
  });
}

/** 검사 대상(`persist(...)` 호출 + 정적 옵션 객체)이면 옵션 노드를 반환, 아니면 null. */
function persistOptionsObject(node) {
  if (node.callee.type !== "Identifier" || node.callee.name !== "persist") {
    return null;
  }
  const options = node.arguments[1];
  if (!options || options.type !== "ObjectExpression") {
    return null;
  }
  return options;
}

/** @type {import("eslint").Rule.RuleModule} */
const versionRequired = {
  meta: {
    type: "problem",
    docs: {
      description:
        "zustand persist 옵션에 version 키를 강제한다 (localStorage 스키마 회귀 가드, #1105).",
    },
    schema: [],
    messages: {
      missingOptions:
        "zustand persist 두 번째 인자(옵션 객체)가 필요합니다. 최소 { name, version } 을 명시하세요 (#1105 회귀 가드).",
      missingVersion:
        "zustand persist 옵션에 `version` 키가 필요합니다 (localStorage 스키마 회귀 가드, #1105). 옵션 객체에 `version: <정수>` 를 추가하세요.",
    },
  },
  create(context) {
    return {
      CallExpression(node) {
        if (node.callee.type !== "Identifier" || node.callee.name !== "persist") {
          return;
        }
        const options = node.arguments[1];
        if (!options) {
          context.report({ node, messageId: "missingOptions" });
          return;
        }
        if (options.type !== "ObjectExpression") {
          return;
        }
        if (!hasOptionKey(options, "version")) {
          context.report({ node: options, messageId: "missingVersion" });
        }
      },
    };
  },
};

/** @type {import("eslint").Rule.RuleModule} */
const migrateRecommended = {
  meta: {
    type: "suggestion",
    docs: {
      description:
        "zustand persist 옵션에 migrate 함수를 권장한다 (version bump 시 영속 상태 변환).",
    },
    schema: [],
    messages: {
      missingMigrate:
        "zustand persist 옵션에 `migrate` 함수를 두는 것을 권장합니다 — version bump 시 기존 영속 상태를 변환할 hook 입니다 (#1105).",
    },
  },
  create(context) {
    return {
      CallExpression(node) {
        const options = persistOptionsObject(node);
        if (options === null) {
          return;
        }
        if (!hasOptionKey(options, "migrate")) {
          context.report({ node: options, messageId: "missingMigrate" });
        }
      },
    };
  },
};

const plugin = {
  meta: { name: "zustand-persist" },
  rules: {
    "persist-version-required": versionRequired,
    "persist-migrate-recommended": migrateRecommended,
  },
};

export default plugin;
