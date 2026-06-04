/**
 * 브랜드 wordmark + 로고 마크 (ui-ux-redesign 단계 4 PR 8, #1689).
 *
 * 마이크 + 네온 글로우 로고 마크와 "모부르지"/"mobruji" wordmark 를 한 lockup 으로
 * 묶어 헤더 등 브랜드 노출 지점에 일관 적용한다. 색/폰트/사이즈는 design token
 * (`tokens.css`) 을 그대로 따른다.
 *
 * 인라인 SVG + 텍스트로 렌더하는 이유:
 *  - `public/icons/*.svg` 를 `<img>` 로 부르면 외부 리소스라 CSS 변수(--brand-500 등)
 *    를 못 받아 다크 모드 자동 swap / 테마 분기가 불가능하다. 컴포넌트는 토큰을
 *    그대로 상속받도록 인라인으로 그리고, public SVG 는 favicon/OG 등 정적 용도로
 *    공존한다 (PR 본문 참조).
 *
 * a11y:
 *  - wordmark 는 실제 텍스트라 스크린리더가 그대로 읽는다. 로고 마크 SVG 는
 *    `aria-hidden` 으로 중복 낭독을 막는다.
 *
 * 의존성:
 *  - 외부 아이콘 라이브러리 회피 (fe 외부 lib 회피 패턴). SVG 인라인.
 */

type BrandLang = "ko" | "en";
type BrandSize = "sm" | "md" | "lg";
type BrandTheme = "auto" | "light" | "dark";

type BrandWordmarkProps = {
  lang?: BrandLang;
  size?: BrandSize;
  theme?: BrandTheme;
  className?: string;
};

const WORDMARK_TEXT: Record<BrandLang, string> = {
  ko: "모부르지",
  en: "mobruji",
};

const SIZE_STYLE: Record<BrandSize, { mark: string; text: string; gap: string }> = {
  sm: { mark: "h-5 w-5", text: "text-base", gap: "gap-1.5" },
  md: { mark: "h-7 w-7", text: "text-2xl", gap: "gap-2" },
  lg: { mark: "h-10 w-10", text: "text-4xl", gap: "gap-2.5" },
};

/*
 * theme="auto" 는 브랜드 컬러(--brand-500) 를 그대로 쓴다 — indigo 는 light/dark
 * 양쪽 배경에서 모두 가독성이 유지된다(ADR-0018 §1). light/dark 는 표면 대비를
 * 위해 잉크 톤을 고정한다 (어두운 표면=밝은 잉크).
 */
const THEME_COLOR: Record<BrandTheme, string> = {
  auto: "var(--brand-500)",
  light: "var(--text-primary)",
  dark: "#fafafa",
};

export function BrandWordmark({
  lang = "ko",
  size = "md",
  theme = "auto",
  className,
}: BrandWordmarkProps) {
  const sizeStyle = SIZE_STYLE[size];

  return (
    <span
      data-testid="brand-wordmark"
      data-lang={lang}
      data-size={size}
      data-theme={theme}
      className={[
        "inline-flex select-none items-center",
        sizeStyle.gap,
        className ?? "",
      ]
        .filter(Boolean)
        .join(" ")}
      style={{ color: THEME_COLOR[theme] }}
    >
      <BrandLogoMark className={sizeStyle.mark} />
      <span
        className={[
          sizeStyle.text,
          "font-extrabold leading-none tracking-tight",
        ].join(" ")}
        style={{ fontFamily: "var(--font-display)" }}
      >
        {WORDMARK_TEXT[lang]}
      </span>
    </span>
  );
}

/**
 * 마이크 + 네온 글로우 로고 마크. stroke 는 `currentColor` 라 부모 wordmark 의
 * 테마 색을 그대로 상속받는다. 네온 글로우는 SVG `<filter>` gaussian blur.
 */
function BrandLogoMark({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 64 64"
      fill="none"
      stroke="currentColor"
      strokeWidth="4"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <defs>
        <filter
          id="brand-logo-mark-glow"
          x="-40%"
          y="-40%"
          width="180%"
          height="180%"
        >
          <feGaussianBlur stdDeviation="1.8" result="blur" />
          <feMerge>
            <feMergeNode in="blur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
      <g filter="url(#brand-logo-mark-glow)">
        <rect x="24" y="10" width="16" height="28" rx="8" />
        <path d="M18 30a14 14 0 0 0 28 0" />
        <line x1="32" y1="44" x2="32" y2="52" />
        <line x1="24" y1="54" x2="40" y2="54" />
      </g>
    </svg>
  );
}
