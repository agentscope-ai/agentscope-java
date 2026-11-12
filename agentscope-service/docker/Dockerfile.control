# Build from the monorepo root; no generated console files are required in Git.
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS console
WORKDIR /src/console
COPY managed-agents/console/package*.json ./
RUN npm ci
COPY managed-agents/console/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM golang:1.26 AS build
ARG VERSION=2.1.0-BETA1
ARG GIT_COMMIT=unknown
ARG TARGETOS=linux
ARG TARGETARCH
WORKDIR /src
COPY managed-agents/control-plane/go.mod managed-agents/control-plane/go.sum ./
RUN go mod download
COPY managed-agents/control-plane/ ./
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w -X gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/version.Version=$VERSION -X main.gitCommit=$GIT_COMMIT" -o /out/control-plane ./cmd/control-plane
RUN mkdir -p /out/data/workspaces /out/data/artifacts

FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/control-plane /control-plane
COPY --from=console /src/control-plane/ui /ui
COPY --from=build --chown=65532:65532 /out/data /data
ENV CONTROL_PLANE_STATIC_DIR=/ui CONTROL_PLANE_WORKSPACE_ROOT=/data/workspaces
USER 65532:65532
EXPOSE 8081
ENTRYPOINT ["/control-plane"]
