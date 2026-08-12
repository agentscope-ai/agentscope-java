FROM --platform=$BUILDPLATFORM golang:1.26 AS builder

ARG TARGETARCH
ARG TARGETOS=linux

WORKDIR /workspace
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -a \
    -ldflags="-s -w" -o aistiod ./cmd/aistiod
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -a \
    -ldflags="-s -w" -o aistioctl ./cmd/aistioctl
# Distroless has no shell, so the writable workspace root is staged here.
RUN mkdir -p /staging/workspaces

FROM gcr.io/distroless/static:nonroot
WORKDIR /
COPY --from=builder /workspace/aistiod .
COPY --from=builder /workspace/aistioctl .
# Console SPA served by aistiod on the same port as the APIs.
COPY --from=builder /workspace/ui /ui
COPY --from=builder --chown=65532:65532 /staging/workspaces /data/workspaces
ENV AISTIO_STATIC_DIR=/ui \
    AISTIO_WORKSPACE_ROOT=/data/workspaces
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/aistiod"]
