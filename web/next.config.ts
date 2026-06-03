import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Phase 4 NCP dev 배포 (docs/features/ncp-dev-deployment.md §5-2):
  // standalone build → .next/standalone 안에 최소 node_modules + server.js 자동 생성.
  // docker runner stage 가 build 결과만 복사하면 되어 이미지 크기가 작아진다.
  output: "standalone",
  experimental: {
    // PR7 (#1687) — 라우트 전환 시 Next 가 navigation 을 document.startViewTransition
    // 으로 감싸 native View Transitions API 를 켠다. 지원 브라우저는 기본 root
    // crossfade + view-transition-name 공유 요소 morph, 미지원 브라우저(현 Firefox)는
    // 즉시 전환으로 graceful fallback. React <ViewTransition> 컴포넌트는 쓰지 않고
    // CSS view-transition-name 속성만으로 hero morph 를 구현한다 (component 런타임/타입
    // 미노출 회피).
    viewTransition: true,
  },
};

export default nextConfig;
