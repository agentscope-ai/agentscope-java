# Control Plane

VERSION ?= 2.1.0-BETA1
IMG ?= sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope/as-controlplane:$(VERSION)
PREFIX ?= /usr/local
CONTROLLER_GEN ?= $(shell command -v controller-gen 2>/dev/null || printf '%s/bin/controller-gen' "$$(go env GOPATH)")
HELM_CHART ?= helm/controlplane

.PHONY: all build build-runtime-cli install-runtime-cli test run generate manifests fmt vet ci verify sync-helm helm-lint helm-template docker-build docker-push clean install-tools proto

ci: vet test

verify: generate manifests sync-helm
	git diff --exit-code -- api config helm

# sync-helm mirrors the generated CRDs and RBAC ClusterRole into the Helm chart
# so the chart is always a faithful, single-source-of-truth deployment artifact.
# Never edit helm/.../crds or helm/.../templates/clusterrole.yaml by hand.
sync-helm: manifests
	rm -rf $(HELM_CHART)/crds
	mkdir -p $(HELM_CHART)/crds
	cp config/crd/*.yaml $(HELM_CHART)/crds/
	printf '{{- if .Values.rbac.create }}\n' > $(HELM_CHART)/templates/clusterrole.yaml
	cat config/rbac/role.yaml >> $(HELM_CHART)/templates/clusterrole.yaml
	printf '{{- end }}\n' >> $(HELM_CHART)/templates/clusterrole.yaml

helm-lint:
	helm lint $(HELM_CHART)

helm-template:
	helm template controlplane $(HELM_CHART) --namespace controlplane-system

all: build

build:
	go build -o bin/service-controlplane ./cmd/service-controlplane
	go build -o bin/agentscope-runtime-host ./cmd/agentscope-runtime-host
	go build -o bin/as ./cmd/as

build-runtime-cli:
	go build -o bin/agentscope-runtime-host ./cmd/agentscope-runtime-host
	go build -o bin/as ./cmd/as

install-runtime-cli: build-runtime-cli
	mkdir -p $(DESTDIR)$(PREFIX)/bin
	install -m 0755 bin/as $(DESTDIR)$(PREFIX)/bin/as
	install -m 0755 bin/agentscope-runtime-host $(DESTDIR)$(PREFIX)/bin/agentscope-runtime-host

run: build
	./bin/service-controlplane

test:
	go test ./... -coverprofile cover.out

ENVTEST_K8S_VERSION ?= 1.32.x
ENVTEST_K8S_INSTALLED_VERSION ?= 1.32.0
LOCALBIN ?= $(shell pwd)/bin
ENVTEST ?= $(shell which setup-envtest 2>/dev/null || echo "$(LOCALBIN)/setup-envtest")

.PHONY: envtest
envtest: ## Download setup-envtest if necessary.
	@test -x $(ENVTEST) || GOBIN=$(LOCALBIN) go install sigs.k8s.io/controller-runtime/tools/setup-envtest@latest

.PHONY: test-integration
test-integration: envtest ## Run envtest integration tests.
	@set -e; assets=""; \
	if ! assets="$$($(ENVTEST) use -i $(ENVTEST_K8S_INSTALLED_VERSION) --bin-dir $(LOCALBIN) -p path 2>/dev/null)"; then \
		for attempt in 1 2 3; do \
			if assets="$$($(ENVTEST) use $(ENVTEST_K8S_VERSION) --bin-dir $(LOCALBIN) -p path)"; then break; fi; \
		done; \
	fi; \
	test -x "$$assets/etcd"; \
	test -x "$$assets/kube-apiserver"; \
	KUBEBUILDER_ASSETS="$$assets" go test ./internal/controller/... -v -count=1

fmt:
	go fmt ./...

vet:
	go vet ./...

generate:
	$(CONTROLLER_GEN) object paths="./api/..."

manifests:
	$(CONTROLLER_GEN) crd rbac:roleName=controlplane-controller-role webhook paths="./..." \
		output:crd:artifacts:config=config/crd \
		output:rbac:artifacts:config=config/rbac \
		output:webhook:artifacts:config=config/webhook

proto:
	protoc --proto_path=. --go_out=. --go_opt=paths=source_relative --go-grpc_out=. --go-grpc_opt=paths=source_relative internal/asdp/asdp.proto

proto-verify: proto
	git diff --exit-code -- internal/asdp/*.pb.go

docker-build:
	docker buildx build --platform linux/amd64,linux/arm64 -f ../docker/Dockerfile.control --build-arg VERSION=$(VERSION) --build-arg GIT_COMMIT=$(shell git rev-parse HEAD) -t $(IMG) ../..

docker-push:
	docker buildx build --platform linux/amd64,linux/arm64 -f ../docker/Dockerfile.control --build-arg VERSION=$(VERSION) --build-arg GIT_COMMIT=$(shell git rev-parse HEAD) -t $(IMG) --push ../..

install-yaml: manifests sync-helm
	@echo "Generating install/install.yaml from Helm chart..."
	helm template controlplane $(HELM_CHART) \
		--namespace controlplane-system \
		--set leaderElection.enabled=true \
		--set image.tag=$(shell echo $(IMG) | cut -d: -f2) \
		> install/install.yaml
	@echo "Prepending CRDs..."
	@for f in config/crd/*.yaml; do cat "$$f"; echo "---"; done | cat - install/install.yaml > install/install.yaml.tmp
	@mv install/install.yaml.tmp install/install.yaml
	@echo "Generated install/install.yaml"

clean:
	rm -rf bin/ cover.out

install-tools:
	go install sigs.k8s.io/controller-tools/cmd/controller-gen@latest
