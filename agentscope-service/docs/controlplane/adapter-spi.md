# 数据面适配器 SPI 文档

## 概述

`DataPlaneAdapter` 接口定义了控制面如何为特定数据面运行时构建 Kubernetes 资源。不同运行时（agentscope-java、agentscope-go、langchain 等）有不同的镜像、端口、配置格式和健康探针，通过实现该接口即可接入控制面。

这一设计类似于 Istio 的数据面抽象：控制面只与接口交互，不关心具体运行时实现。

## 接口定义

接口定义位于 `internal/adapter/adapter.go`：

```go
package adapter

import (
    appsv1 "k8s.io/api/apps/v1"
    corev1 "k8s.io/api/core/v1"

    "github.com/agentscope/agentscope-go/control-plane/api/v1alpha1"
)

// ToolConfig holds resolved tool configuration for building ConfigMaps.
type ToolConfig struct {
    Name            string
    MCPServer       string
    URL             string
    ToolNames       []string
    RequireApproval []string
}

// DataPlaneAdapter builds Kubernetes resources for a specific data plane runtime.
type DataPlaneAdapter interface {
    // RuntimeName returns the runtime identifier (e.g. "agentscope-java").
    RuntimeName() string

    // BuildDeployment constructs a Kubernetes Deployment from the Agent CRD spec.
    BuildDeployment(agent *v1alpha1.Agent) (*appsv1.Deployment, error)

    // BuildConfigMap translates Agent spec into a data plane consumable config format.
    BuildConfigMap(agent *v1alpha1.Agent, tools []ToolConfig) (*corev1.ConfigMap, error)

    // BuildService constructs the Kubernetes Service for the agent.
    BuildService(agent *v1alpha1.Agent) (*corev1.Service, error)

    // HealthProbe returns the data plane health check probe configuration.
    HealthProbe() *corev1.Probe

    // DefaultPort returns the default container port for this runtime.
    DefaultPort() int32

    // SupportsFeature queries whether the runtime supports a specific feature.
    SupportsFeature(feature string) bool
}
```

### 方法说明

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `RuntimeName()` | `string` | 返回运行时标识符，需与 Agent CRD 的 `spec.runtime` 字段匹配 |
| `BuildDeployment(agent)` | `*appsv1.Deployment, error` | 根据 Agent CRD spec 构造 Deployment，包含镜像、端口、探针、环境变量、资源限制等 |
| `BuildConfigMap(agent, tools)` | `*corev1.ConfigMap, error` | 将 Agent spec 和 Tool 配置转换为数据面可消费的配置格式（JSON/YAML），仅 Declarative 模式使用 |
| `BuildService(agent)` | `*corev1.Service, error` | 构造 Service，暴露数据面端口 |
| `HealthProbe()` | `*corev1.Probe` | 返回健康检查探针配置，用于 liveness 和 readiness 探针 |
| `DefaultPort()` | `int32` | 返回运行时默认容器端口 |
| `SupportsFeature(feature)` | `bool` | 查询运行时是否支持特定能力 |

### 已知 Feature 列表

| Feature 标识 | 说明 |
|------|------|
| `session-reporting` | 数据面支持上报会话列表（contractLevel >= 2） |
| `hot-reload` | 支持配置热加载 |
| `context-compression` | 支持上下文压缩指令 |
| `sandbox-request` | 支持向控制面申请 sandbox |

---

## 注册适配器

适配器通过全局注册表管理。注册表位于 `internal/adapter/registry.go`：

```go
// Register adds an adapter to the registry.
func Register(adapter DataPlaneAdapter)

// Get returns the adapter for the given runtime name.
func Get(runtime string) (DataPlaneAdapter, error)

// IsRegistered reports whether an adapter is registered for the runtime.
func IsRegistered(runtime string) bool

// List returns all registered runtime names.
func List() []string
```

注册通常在 adapter 包的 `init()` 函数中完成：

```go
func init() {
    Register(&AgentScopeJavaAdapter{})
}
```

控制面启动时自动加载所有 `init()` 注册的适配器。`AgentController` 在 reconcile 时调用 `adapter.Get(runtime)` 获取对应适配器来构建 K8s 资源。

---

## 内置适配器：agentscope-java

项目内置了 AgentScope Java 运行时适配器（`internal/adapter/agentscope_java.go`），可作为实现自定义适配器的参考。

**核心参数：**

| 参数 | 值 |
|------|------|
| RuntimeName | `agentscope-java` |
| DefaultPort | `8080` |
| 默认镜像 | `registry.cn-hangzhou.aliyuncs.com/agentscope/runtime-java:latest` |
| 健康探针路径 | `/agentscope/health`（HTTPGet） |
| 支持的 Feature | `session-reporting`、`hot-reload`、`context-compression`、`sandbox-request` |

**Deployment 构建逻辑：**

- Declarative 模式：使用默认镜像，从 `spec.declarative` 读取副本数、资源限制、环境变量
- BYO 模式：使用用户指定的镜像，从 `spec.byo` 读取副本数、命令、参数、资源限制
- 自动挂载 ConfigMap（`<agent-name>-config`）到 `/app/config`
- 自动设置 liveness/readiness 探针
- 设置 OwnerReference 指向 Agent CRD

**ConfigMap 构建逻辑（Declarative 模式）：**

- 将 Agent spec 序列化为 `agent-config.json`
- 包含 `name`、`runtime`、`stream`、`maxTurns`、`systemMessage`、`modelConfigRef` 等
- 合并 `ToolConfig` 列表

**Service 构建逻辑：**

- ClusterIP 类型
- 端口映射为默认端口（8080）
- selector 指向 `app: <agent-name>`

---

## 示例：实现自定义适配器

以下演示如何为一个假想的 `langchain-python` 运行时实现适配器：

```go
package adapter

import (
    "encoding/json"
    "fmt"

    appsv1 "k8s.io/api/apps/v1"
    corev1 "k8s.io/api/core/v1"
    metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
    "k8s.io/apimachinery/pkg/util/intstr"

    "github.com/agentscope/agentscope-go/control-plane/api/v1alpha1"
)

const (
    RuntimeLangChain     = "langchain-python"
    defaultPythonImage   = "registry.example.com/langchain/runtime:latest"
    defaultPythonPort    = int32(8000)
)

type LangChainAdapter struct{}

func (a *LangChainAdapter) RuntimeName() string {
    return RuntimeLangChain
}

func (a *LangChainAdapter) DefaultPort() int32 {
    return defaultPythonPort
}

func (a *LangChainAdapter) HealthProbe() *corev1.Probe {
    return &corev1.Probe{
        ProbeHandler: corev1.ProbeHandler{
            HTTPGet: &corev1.HTTPGetAction{
                Path: "/agentscope/health",
                Port: intstr.FromInt32(defaultPythonPort),
            },
        },
        InitialDelaySeconds: 15,
        PeriodSeconds:       10,
    }
}

func (a *LangChainAdapter) SupportsFeature(feature string) bool {
    supported := map[string]bool{
        "session-reporting": true,
        "hot-reload":        false,
    }
    return supported[feature]
}

func (a *LangChainAdapter) BuildDeployment(agent *v1alpha1.Agent) (*appsv1.Deployment, error) {
    replicas := int32(1)
    labels := map[string]string{
        "app":                      agent.Name,
        "agentscope.io/managed":    "true",
        "agentscope.io/agent-name": agent.Name,
        "agentscope.io/runtime":    RuntimeLangChain,
    }

    dep := &appsv1.Deployment{
        ObjectMeta: metav1.ObjectMeta{
            Name:      agent.Name,
            Namespace: agent.Namespace,
            Labels:    labels,
            OwnerReferences: []metav1.OwnerReference{
                *metav1.NewControllerRef(agent, v1alpha1.GroupVersion.WithKind("Agent")),
            },
        },
        Spec: appsv1.DeploymentSpec{
            Replicas: &replicas,
            Selector: &metav1.LabelSelector{
                MatchLabels: map[string]string{"app": agent.Name},
            },
            Template: corev1.PodTemplateSpec{
                ObjectMeta: metav1.ObjectMeta{Labels: labels},
                Spec: corev1.PodSpec{
                    Containers: []corev1.Container{{
                        Name:           "agent",
                        Image:          defaultPythonImage,
                        Ports:          []corev1.ContainerPort{{
                            Name: "http", ContainerPort: defaultPythonPort,
                        }},
                        LivenessProbe:  a.HealthProbe(),
                        ReadinessProbe: a.HealthProbe(),
                    }},
                },
            },
        },
    }
    return dep, nil
}

func (a *LangChainAdapter) BuildConfigMap(agent *v1alpha1.Agent, tools []ToolConfig) (*corev1.ConfigMap, error) {
    // LangChain 使用 YAML 配置格式
    config := map[string]interface{}{
        "agent_name": agent.Name,
        "runtime":    RuntimeLangChain,
    }
    data, err := json.MarshalIndent(config, "", "  ")
    if err != nil {
        return nil, fmt.Errorf("marshaling config: %w", err)
    }

    return &corev1.ConfigMap{
        ObjectMeta: metav1.ObjectMeta{
            Name:      fmt.Sprintf("%s-config", agent.Name),
            Namespace: agent.Namespace,
            OwnerReferences: []metav1.OwnerReference{
                *metav1.NewControllerRef(agent, v1alpha1.GroupVersion.WithKind("Agent")),
            },
        },
        Data: map[string]string{"agent-config.json": string(data)},
    }, nil
}

func (a *LangChainAdapter) BuildService(agent *v1alpha1.Agent) (*corev1.Service, error) {
    return &corev1.Service{
        ObjectMeta: metav1.ObjectMeta{
            Name:      agent.Name,
            Namespace: agent.Namespace,
            OwnerReferences: []metav1.OwnerReference{
                *metav1.NewControllerRef(agent, v1alpha1.GroupVersion.WithKind("Agent")),
            },
        },
        Spec: corev1.ServiceSpec{
            Selector: map[string]string{"app": agent.Name},
            Ports: []corev1.ServicePort{{
                Name: "http", Port: defaultPythonPort,
                TargetPort: intstr.FromInt32(defaultPythonPort),
            }},
            Type: corev1.ServiceTypeClusterIP,
        },
    }, nil
}

// 注册适配器
func init() {
    Register(&LangChainAdapter{})
}
```

### 注册要点

1. 在 adapter 包下新建文件（如 `langchain_python.go`）
2. 实现 `DataPlaneAdapter` 接口的全部方法
3. 在 `init()` 函数中调用 `Register()` 完成注册
4. 确保适配器包被导入（在 `cmd/agentscoped/main.go` 或 adapter 包的 `init()` 中引入）

### 构建 Deployment 的关键约定

- **Labels**：必须包含 `agentscope.io/managed: "true"` 和 `agentscope.io/agent-name: <name>`
- **OwnerReference**：必须设置，指向 Agent CRD，确保级联删除
- **探针**：推荐使用契约 API 的 `/agentscope/health` 路径
- **ConfigMap 命名**：使用 `<agent-name>-config` 格式

---

## 测试适配器

参考内置适配器的测试文件 `internal/adapter/agentscope_java_test.go`，为自定义适配器编写单测：

```go
func TestLangChainAdapter_BuildDeployment(t *testing.T) {
    adapter := &LangChainAdapter{}

    agent := &v1alpha1.Agent{
        ObjectMeta: metav1.ObjectMeta{
            Name:      "test-agent",
            Namespace: "default",
        },
        Spec: v1alpha1.AgentSpec{
            Runtime: "langchain-python",
            Type:    v1alpha1.AgentTypeDeclarative,
        },
    }

    dep, err := adapter.BuildDeployment(agent)
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    if dep.Name != "test-agent" {
        t.Errorf("expected name test-agent, got %s", dep.Name)
    }
    if dep.Spec.Template.Spec.Containers[0].Ports[0].ContainerPort != 8000 {
        t.Errorf("expected port 8000")
    }
}
```

运行测试：

```bash
cd control-plane
go test ./internal/adapter/... -v
```
