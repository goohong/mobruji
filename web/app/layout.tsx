import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { ServiceWorkerRegistrar } from "./ServiceWorkerRegistrar";
import { BottomNav } from "@/components/nav/BottomNav";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { THEME_INIT_SCRIPT } from "@/lib/theme";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
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
      { url: "/icons/icon-192.svg", sizes: "192x192", type: "image/svg+xml" },
      { url: "/icons/icon-512.svg", sizes: "512x512", type: "image/svg+xml" },
    ],
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
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
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
        모바일 BottomNav가 fixed로 깔리므로 main 콘텐츠가 가려지지 않도록 body에
        하단 padding을 둔다. 데스크탑(md:)에선 nav를 숨기므로 padding도 제거.
        nav 높이(h-14=56px) + safe-area 여유로 pb-20 (= 80px) 사용.
      */}
      <body className="min-h-full flex flex-col pb-20 md:pb-0">
        <Providers>{children}</Providers>
        <ThemeToggle />
        <BottomNav />
        <ServiceWorkerRegistrar />
      </body>
    </html>
  );
}
