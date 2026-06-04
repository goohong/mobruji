import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import localFont from "next/font/local";
import "./globals.css";
import { Providers } from "./providers";
import { ServiceWorkerRegistrar } from "./ServiceWorkerRegistrar";
import { BottomNav } from "@/components/nav/BottomNav";
import { DesktopNav } from "@/components/nav/DesktopNav";
import { RouteTransition } from "@/components/layout/RouteTransition";
import { HomeLink } from "@/components/nav/HomeLink";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { THEME_INIT_SCRIPT } from "@/lib/theme";

/*
 * Pretendard Variable — 한글 본문 폰트 (ui-ux-redesign 단계 4 PR 9, #1692).
 *
 * 기존 globals.css 의 수동 `@font-face` 를 next/font/local 로 이관한다. next 가
 * 빌드 시 woff2 를 `/_next/static/media` 로 해시 복사해 자체 호스팅(외부 요청 0)
 * 하고, 폰트 메트릭을 읽어 CLS 를 줄이는 size-adjust fallback face 를 자동 생성한다.
 *
 *  - display: "swap" — 폰트 도착 전 fallback 으로 즉시 paint (FOIT 회피, FOUT 허용).
 *  - adjustFontFallback(local 기본 "Arial") — next 가 woff2 메트릭으로 size-adjust 된
 *    fallback `@font-face` 를 만들어 swap 시 reflow(CLS) 를 최소화한다.
 *  - preload: false — 한글 전체 글리프 + variable axis 라 ~2MB. 모든 라우트에서
 *    eager preload 하면 LCP 를 해치므로, swap + size-adjust fallback 으로 충분히
 *    부드럽게 교체되도록 두고 critical path 에서 뺀다.
 *  - weight: "45 920" — Pretendard v1.3 variable weight 축 전체 범위.
 *
 * `tokens.css` 의 `--font-family-*` stack 이 `var(--font-pretendard)` 를 1순위로
 * 가리키며, Latin 은 그다음 `var(--font-geist-sans)` → system 으로 graceful fallback.
 */
const pretendard = localFont({
  src: "../public/fonts/PretendardVariable.woff2",
  variable: "--font-pretendard",
  display: "swap",
  weight: "45 920",
  preload: false,
  fallback: ["system-ui", "-apple-system", "Segoe UI", "sans-serif"],
});

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
  display: "swap",
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "mobruji — 노래방 추천",
  description: "음역대 기반으로 부르기 좋은 노래방 곡을 추천해주는 서비스",
  manifest: "/manifest.json",
  applicationName: "모부르지",
  appleWebApp: {
    capable: true,
    title: "모부르지",
    statusBarStyle: "black-translucent",
  },
  icons: {
    icon: [
      { url: "/icons/favicon-512.svg", sizes: "512x512", type: "image/svg+xml" },
      { url: "/icons/icon-192.svg", sizes: "192x192", type: "image/svg+xml" },
      { url: "/icons/icon-512.svg", sizes: "512x512", type: "image/svg+xml" },
    ],
    shortcut: "/icons/favicon-512.svg",
    apple: [{ url: "/icons/icon-192.svg", sizes: "192x192" }],
  },
};

/**
 * PWA status bar / browser chrome 색을 라이트/다크에 분기.
 *
 * mobruji 는 모바일 우선이며 OS 다크 모드 설정을 그대로 따라간다 (#296).
 * iOS Safari 와 PWA 모드에서 status bar 가 시스템 모드와 어긋나면 인지 부조화가
 * 크므로 prefers-color-scheme 별로 themeColor 를 분기한다.
 * 값은 globals.css 의 `--background` 변수와 동일 — light=#ffffff, dark=#0a0a0a.
 */
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0a0a" },
  ],
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    // THEME_INIT_SCRIPT 가 hydration 직전에 root html 의 className 에 dark
    // 클래스를 동적으로 추가/제거한다. server render className 과 hydration 시점
    // className 이 다르므로 React 가 mismatch 경고를 띄우거나 일부 환경에서
    // className 을 server 버전으로 덮어써 다크 클래스가 사라지는 회귀가 있다
    // (이슈 #1170). suppressHydrationWarning 으로 이 element 한정 mismatch 경고를
    // silence 한다 — next-themes 등 표준 패턴.
    <html
      lang="ko"
      suppressHydrationWarning
      className={`${pretendard.variable} ${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <head>
        {/*
         * 이슈 #319: hydration 전에 `<html class="dark">` 를 결정해 라이트↔다크
         * FOUC 를 방지한다. localStorage 모드 / system prefers-color-scheme 로
         * 분기. 본 스크립트는 layout.tsx 외엔 어떤 컴포넌트도 의존하지 않는다.
         */}
        <script
          dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }}
        />
      </head>
      {/*
        모바일 BottomNav(하단 fixed) / 데스크톱 DesktopNav(상단 fixed) 가 본문을 가리지
        않도록 body 에 뷰포트별 padding 을 둔다.
         - 모바일 하단: BottomNav 실제 높이 = pt-1(4px) + 탭 h-14(56px) + env(safe-area).
           고정 pb-20(80px) 만 두면 home indicator(safe-area ≈ 34px) 기기에서 nav가
           80px를 넘어 본문·CTA를 가린다 (#1718). safe-area를 padding에 더해 80px 버퍼가
           항상 nav 위에 남도록 calc 로 보정한다. 데스크톱(md:)에선 BottomNav 를 숨기므로 제거(md:pb-0).
         - 데스크톱 상단: 상단 DesktopNav(h-14=56px) 만큼 md:pt-14. 모바일엔 상단 헤더가
           없으므로 기본 pt-0 유지.
      */}
      <body className="min-h-full flex flex-col pb-[calc(5rem_+_env(safe-area-inset-bottom))] md:pb-0 md:pt-14">
        {/* 데스크톱 상단 헤더 nav — DOM 상 ThemeToggle 앞에 둬서 동일 z-30 에서
            ThemeToggle(우상단 floating)이 헤더 위로 그려지게 한다 (closes #1717). */}
        <DesktopNav />
        <Providers>
          <RouteTransition>{children}</RouteTransition>
        </Providers>
        <HomeLink />
        <ThemeToggle />
        <BottomNav />
        <ServiceWorkerRegistrar />
      </body>
    </html>
  );
}
