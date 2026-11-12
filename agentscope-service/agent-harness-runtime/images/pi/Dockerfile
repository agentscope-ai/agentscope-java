# Build context: repository root. One Node process, including the selected PI kernel.
FROM node:22-bookworm-slim AS build
RUN corepack enable && corepack prepare pnpm@10.28.2 --activate
WORKDIR /src
COPY managed-agents/agent-harness-runtime/typescript/ ./
RUN pnpm install --frozen-lockfile --ignore-scripts && pnpm -r build
RUN pnpm --filter @managed-agents/brain-pi deploy --legacy --prod /out
FROM node:22-bookworm-slim
WORKDIR /app
COPY --from=build --chown=node:node /out/ ./
RUN mkdir -p /workspace && chown node:node /workspace
ENV PORT=8082 MANAGED_AGENTS_HARNESS_TYPE=pi
USER node
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=3s CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8082)+'/readyz').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node","dist/index.js"]
