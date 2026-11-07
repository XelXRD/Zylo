# One Dockerfile, three targets: api (zylo-core), worker, web.
FROM node:22-slim AS base
RUN corepack enable
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/api/package.json apps/api/
COPY apps/worker/package.json apps/worker/
COPY apps/web/package.json apps/web/
RUN pnpm install --frozen-lockfile
COPY . .

# ponytail: api and worker ship full node_modules and run TypeScript via tsx; slim images if size matters
FROM base AS api
WORKDIR /app/apps/api
RUN DATABASE_URL=postgresql://unused@localhost/unused pnpm exec prisma generate
EXPOSE 4000
CMD ["pnpm", "start"]

FROM base AS worker
WORKDIR /app/apps/worker
CMD ["pnpm", "start"]

FROM base AS web-build
WORKDIR /app/apps/web
# Baked in at build time: the web app proxies /api/* to this address.
ENV API_URL=http://zylo-core:4000 NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

FROM node:22-slim AS web
WORKDIR /app
ENV NODE_ENV=production HOSTNAME=0.0.0.0 PORT=3000 NEXT_TELEMETRY_DISABLED=1
COPY --from=web-build /app/apps/web/.next/standalone ./
COPY --from=web-build /app/apps/web/.next/static ./apps/web/.next/static
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
