# syntax=docker/dockerfile:1.6
# Mobruji web (Next.js 16, React 19, Node 22).
# Phase 4 NCP dev 배포용 — docs/features/ncp-dev-deployment.md §5-2.
#
# 빌드:
#   docker build --build-arg GIT_SHA=$(git rev-parse --short HEAD) -t mobruji/web:dev web/
# 실행 (compose):
#   docker compose -f docker-compose.dev.yml up -d web

#######################################
# Stage 1 — deps (npm ci 캐시 분리)
#######################################
FROM node:22-alpine AS deps

WORKDIR /app

# package.json + lock 만 먼저 복사 → deps 레이어 캐시.
COPY package.json package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

#######################################
# Stage 2 — builder
#######################################
FROM node:22-alpine AS builder

WORKDIR /app

ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# 빌드 시점에 NEXT_PUBLIC_* 가 정적 inline 된다. dev 환경 기본값을
# build-arg 로 받고, runtime override 가 필요한 값은 server-side env 로.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}

RUN npm run build

#######################################
# Stage 3 — runner
#######################################
FROM node:22-alpine AS runner

# 보안: non-root user.
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 --ingroup nodejs nextjs

# 헬스체크용 wget 은 alpine 기본 포함. curl 추가하지 않아 이미지 크기 유지.

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# standalone 출력만 복사 (.next/standalone 안에 최소 node_modules + server.js).
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}

USER nextjs:nodejs

EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 \
    CMD wget --spider --quiet http://localhost:3000/ || exit 1

ENTRYPOINT ["node", "server.js"]
