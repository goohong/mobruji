/**
 * 다크모드 회귀 가드 — light-only Tailwind 색상 hardcode 잔존을 차단.
 *
 * 배경 (#1310):
 *  - PR #1236 의 토글 무반응 fix 이후에도 사용자가 "다크모드 아직 해결 안 됨"
 *    보고. evidence 수집 결과 일부 페이지 (예: `offline/page.tsx`) 의 텍스트가
 *    `text-gray-600` hardcode 로 light 전용 색을 갖고 있어 다크모드에서 어두운
 *    배경 위에 거의 안 보이는 사고. ADR-0018 zinc 표준화도 위반.
 *  - 코드 review 만으로는 ADR-0018 swap 시리즈 PR 다수 (14건+) 회귀를 잡기
 *    어려워 mechanical grep test 로 라이트 모드 hardcode 잔존을 차단.
 *
 * 룰:
 *  - `web/app/**`, `web/components/**` 의 .tsx 파일에서 `text-*` / `bg-*` /
 *    `border-*` / `ring-*` / `fill-*` / `stroke-*` Tailwind utility 가
 *    회색조 palette (zinc / gray / slate / neutral / stone) 의 light 톤
 *    (50-300 surface, 500-900 텍스트) 을 hardcode 하면서 같은 element 에
 *    `dark:` 분기가 없으면 fail.
 *  - 토큰 (`text-[var(--text-secondary)]`) 사용은 자동 dark swap 이므로
 *    검사 대상 아님. tokens.css 의 `:where(html.dark)` selector 가 cover.
 *
 * 예외 (allow-list, 의도된 light hardcode):
 *  - SongDetailModal backdrop `bg-zinc-900/60` — modal scrim 패턴은 light/dark
 *    동일 검정 반투명이 표준. 의도된 hardcode.
 *  - 테스트 파일 (`*.test.tsx`) — assertion 문자열 검사.
 *
 * 신규 light hardcode 추가 시:
 *  1) 토큰 (`var(--text-*)` 등) 으로 swap, 또는
 *  2) `dark:` prefix 페어 추가, 또는
 *  3) 의도된 예외라면 본 test 의 `ALLOWED_LIGHT_ONLY` 에 등록 + 사유 주석.
 */

import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.resolve(dirname, "..");

/**
 * 의도된 light hardcode 예외 — `<상대경로>:<패턴 일부>` 형식. 매칭은 substring.
 *
 * 신규 등록 시 사유 주석 의무 (다음 reviewer 가 의도 파악할 수 있게).
 */
const ALLOWED_LIGHT_ONLY: readonly { file: string; pattern: string; reason: string }[] = [
  {
    file: "app/recommend/components/SongDetailModal.tsx",
    pattern: "bg-zinc-900/60",
    reason:
      "Modal backdrop scrim — light/dark 동일 검정 반투명이 표준 modal 패턴. 의도된 hardcode.",
  },
];

/** 회색조 palette light hardcode 패턴. 5 utility prefix × 5 palette × 톤 범위. */
const LIGHT_HARDCODE_PATTERN =
  /\b(?:text|bg|border|ring|fill|stroke|placeholder|from|to|via)-(?:zinc|gray|slate|neutral|stone)-(?:50|100|200|300|400|500|600|700|800|900|950)\b/g;

/** className 안에 dark variant 가 같이 있는지 — element 단위 검사 단순화: 같은 className 문자열에 `dark:` 포함이면 OK. */
function hasDarkSiblingInSameClassName(className: string): boolean {
  return /\bdark:/.test(className);
}

/**
 * 한 파일에서 className 으로 흘러갈 수 있는 후보 문자열을 광역 추출.
 *
 * 1) inline `className="..."` / `className={'...'}` / `className={"..."}`
 * 2) `className={[ "...", "..." ].join(...)}` — bracket array literal
 * 3) **변수 분리 패턴 (#1310 fix)** — `const X = "..."` / `Record<*, string>`
 *    값. Button/Chip/Card 처럼 base + variant 를 `const` 에 분리해 `[...].join()`
 *    으로 합치는 경우, inline className 검사만으론 변수 안의 light-only hardcode
 *    를 놓친다 (사용자 보고 회귀의 직접 원인). 변수 string literal 중 회색조
 *    palette utility 가 1개 이상 포함된 것을 후보로 보고 dark 페어 검사.
 *
 * 보수적 접근 — false-positive 회피를 위해 모든 string literal 이 아니라
 * "Tailwind utility 같은 토큰이 들어간" 문자열만 검사 (대상 확장이 의미 토큰
 * 문자열에 비명시적 영향을 주지 않게).
 *
 * 주석 안 backtick 문자열은 false-positive 원인이라 사전 strip — block /
 * line comment 안의 `…` template literal 토큰은 검사 대상 아님.
 */
function stripComments(source: string): string {
  // /* ... */ 와 // ... 둘 다 제거. 단순 lexer 없이 정규식 — string 안 `/*` 같은
  // edge case 까진 안 다룸 (test 자기 보호이므로 보수적 OK).
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:\\])\/\/[^\n]*$/gm, "$1");
}

function extractClassNames(source: string): string[] {
  const stripped = stripComments(source);
  const results: string[] = [];

  // 1) `className="..."` / `className={'...'}` / `className={"..."}`
  const stringRegex =
    /className\s*=\s*(?:\{?\s*)?["'`]([^"'`]+)["'`]/g;
  for (const match of stripped.matchAll(stringRegex)) {
    results.push(match[1]);
  }

  // 2) `className={[ "...", "..." ].join(...)}` — bracket array literal
  const arrayRegex = /className\s*=\s*\{\s*\[([^\]]+)\]/g;
  for (const match of stripped.matchAll(arrayRegex)) {
    const inner = match[1];
    for (const strMatch of inner.matchAll(/["'`]([^"'`]+)["'`]/g)) {
      results.push(strMatch[1]);
    }
  }

  // 3) 변수 / Record 값 안의 Tailwind utility 문자열 후보.
  //    (className 에 직접 inject 안 되더라도, 컴포넌트가 합쳐 쓰는 경우 회귀 발생.)
  //    Tailwind 단서 — `text-` `bg-` `border-` `ring-` `fill-` `flex` `rounded-`
  //    `transition` `inline-flex` 등 빈도 높은 prefix 1개 이상 포함하면 후보.
  const TAILWIND_HINT_RE =
    /(?:\b(?:text|bg|border|ring|fill|stroke|placeholder|from|to|via|rounded|flex|inline-flex|transition|hover|focus|focus-visible|dark|disabled|h-\d|w-\d|p-\d|px-\d|py-\d|gap-\d|font-)\b|-zinc-|-gray-|-slate-|-neutral-|-stone-)/;
  // 문자열 리터럴 후보: "..." / '...' / `...` (template literal 단순 형태만).
  // JSX className 안 / array.join 안 의 것은 이미 위에서 잡았으므로 중복 무해.
  const literalRegex = /["'`]([^"'`\n]{4,400})["'`]/g;
  for (const match of stripped.matchAll(literalRegex)) {
    const literal = match[1];
    if (!TAILWIND_HINT_RE.test(literal)) {
      continue;
    }
    // 회색조 palette 가 없으면 light hardcode 위반 후보 자체가 안 됨 — 빨리 skip.
    if (!/-(?:zinc|gray|slate|neutral|stone)-/.test(literal)) {
      continue;
    }
    results.push(literal);
  }

  return results;
}

/** 디렉토리 재귀 walk — .tsx 파일만 (단, *.test.tsx 제외). */
function walkTsx(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      // node_modules / .next 등은 web/app, web/components 하위에 없음 — 안전.
      out.push(...walkTsx(full));
    } else if (entry.endsWith(".tsx") && !entry.endsWith(".test.tsx")) {
      out.push(full);
    }
  }
  return out;
}

/**
 * 예외 일치 — `entry.pattern` 안에 hit token 이 포함되면 매칭.
 *
 * 예: hit=`bg-zinc-900` (정규식 결과는 opacity suffix `/60` 미포함) 가
 * `entry.pattern="bg-zinc-900/60"` 에 substring 으로 포함. 반대 방향
 * (`hit.includes(pattern)`) 은 opacity suffix 때문에 false 가 되므로 의도와
 * 어긋남.
 */
function isAllowed(relPath: string, hit: string): boolean {
  return ALLOWED_LIGHT_ONLY.some(
    (entry) => relPath.endsWith(entry.file) && entry.pattern.includes(hit),
  );
}

describe("다크모드 회귀 가드 — light-only Tailwind hardcode 차단 (#1310)", () => {
  const targets = [
    path.join(WEB_ROOT, "app"),
    path.join(WEB_ROOT, "components"),
  ];

  const offenders: { file: string; className: string; hit: string }[] = [];

  for (const root of targets) {
    for (const file of walkTsx(root)) {
      const source = readFileSync(file, "utf-8");
      const classNames = extractClassNames(source);
      for (const cn of classNames) {
        const matches = cn.match(LIGHT_HARDCODE_PATTERN);
        if (!matches) {
          continue;
        }
        if (hasDarkSiblingInSameClassName(cn)) {
          continue;
        }
        const relPath = path.relative(WEB_ROOT, file);
        for (const hit of matches) {
          if (isAllowed(relPath, hit)) {
            continue;
          }
          offenders.push({ file: relPath, className: cn, hit });
        }
      }
    }
  }

  it("web/app 및 web/components 의 .tsx 파일은 light-only hardcode 가 없어야 한다", () => {
    if (offenders.length > 0) {
      const lines = offenders.map(
        ({ file, hit, className }) =>
          `  - ${file}: "${hit}" (className snippet: "${className.slice(0, 120)}...")`,
      );
      const message = [
        "다크모드 회귀 — 다음 위치에서 light-only hardcode 가 발견되었습니다:",
        ...lines,
        "",
        "수정 방법:",
        "  1) tokens.css 의 `var(--text-*)` / `var(--bg-*)` 등 의미 토큰으로 swap, 또는",
        "  2) 같은 element 에 `dark:` prefix 페어 추가, 또는",
        "  3) 의도된 예외라면 web/lib/darkModeCoverage.test.ts 의",
        "     ALLOWED_LIGHT_ONLY 배열에 사유와 함께 등록.",
      ].join("\n");
      throw new Error(message);
    }
    expect(offenders).toEqual([]);
  });
});
