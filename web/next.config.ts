import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Phase 4 NCP dev 배포 (docs/features/ncp-dev-deployment.md §5-2):
  // standalone build → .next/standalone 안에 최소 node_modules + server.js 자동 생성.
  // docker runner stage 가 build 결과만 복사하면 되어 이미지 크기가 작아진다.
  output: "standalone",
};

export default nextConfig;
