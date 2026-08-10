# Aistio

IMG ?= aistio:latest
CONTROLLER_GEN ?= $(shell which controller-gen)
HELM_CHART ?= helm/aistio

.PHONY: all build test run generate manifests fmt vet ci verify sync-helm helm-lint helm-template docker-build docker-push clean install-tools proto

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
	helm template aistio $(HELM_CHART) --namespace aistio-system

all: build

build:
	go build -o bin/aistiod ./cmd/aistiod

run: build
	./bin/aistiod

test:
	go test ./... -coverprofile cover.out

ENVTEST_K8S_VERSION ?= 1.32.x
LOCALBIN ?= $(shell pwd)/bin
ENVTEST ?= $(shell which setup-envtest 2>/dev/null || echo "$(LOCALBIN)/setup-envtest")

.PHONY: envtest
envtest: ## Download setup-envtest if necessary.
	@test -x $(ENVTEST) || go install sigs.k8s.io/controller-runtime/tools/setup-envtest@latest

.PHONY: test-integration
test-integration: envtest ## Run envtest integration tests.
	KUBEBUILDER_ASSETS="$$($(ENVTEST) use $(ENVTEST_K8S_VERSION) --bin-dir $(LOCALBIN) -p path)" go test ./internal/controller/... -v -count=1

fmt:
	go fmt ./...

vet:
	go vet ./...

generate:
	$(CONTROLLER_GEN) object paths="./api/..."

manifests:
	$(CONTROLLER_GEN) crd rbac:roleName=aistio-controller-role webhook paths="./..." \
		output:crd:artifacts:config=config/crd \
		output:rbac:artifacts:config=config/rbac \
		output:webhook:artifacts:config=config/webhook

proto:
	protoc --proto_path=. --go_out=. --go_opt=paths=source_relative --go-grpc_out=. --go-grpc_opt=paths=source_relative internal/asdp/asdp.proto

proto-verify: proto
	git diff --exit-code -- internal/asdp/*.pb.go

docker-build:
	docker buildx build --platform linux/amd64,linux/arm64 -t $(IMG) .

docker-push:
	docker buildx build --platform linux/amd64,linux/arm64 -t $(IMG) --push .

install-yaml: manifests sync-helm
	@echo "Generating install/install.yaml from Helm chart..."
	helm template aistio $(HELM_CHART) \
		--namespace aistio-system \
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
