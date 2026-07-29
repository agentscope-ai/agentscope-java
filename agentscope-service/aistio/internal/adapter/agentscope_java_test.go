package adapter

import (
	"encoding/json"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func metaObjectMeta(name, namespace string) metav1.ObjectMeta {
	return metav1.ObjectMeta{Name: name, Namespace: namespace}
}

func declarativeAgent() *v1alpha1.Agent {
	replicas := int32(3)
	return &v1alpha1.Agent{
		ObjectMeta: metaObjectMeta("chatbot", "default"),
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeDeclarative,
			Runtime: RuntimeAgentScopeJava,
			Declarative: &v1alpha1.DeclarativeSpec{
				Replicas: &replicas,
				AgentConfig: v1alpha1.AgentConfig{
					SystemMessage:  "you are helpful",
					ModelConfigRef: "chatbot-model",
					Stream:         true,
					MaxTurns:       10,
				},
			},
		},
	}
}

func TestBuildDeployment_Declarative(t *testing.T) {
	a := &AgentScopeJavaAdapter{}
	dep, err := a.BuildDeployment(declarativeAgent())
	if err != nil {
		t.Fatalf("BuildDeployment: %v", err)
	}
	if got := *dep.Spec.Replicas; got != 3 {
		t.Errorf("replicas = %d, want 3", got)
	}
	if dep.Name != "chatbot" || dep.Namespace != "default" {
		t.Errorf("unexpected name/namespace: %s/%s", dep.Namespace, dep.Name)
	}
	if len(dep.OwnerReferences) != 1 || dep.OwnerReferences[0].Kind != "Agent" {
		t.Errorf("expected an Agent ownerReference, got %+v", dep.OwnerReferences)
	}
	c := dep.Spec.Template.Spec.Containers[0]
	if c.Image != defaultJavaImage {
		t.Errorf("declarative image = %q, want default", c.Image)
	}
	// The control plane endpoints must be injected.
	var hasCP bool
	for _, e := range c.Env {
		if e.Name == "AGENTSCOPE_CP_ENDPOINT" {
			hasCP = true
		}
	}
	if !hasCP {
		t.Error("expected AGENTSCOPE_CP_ENDPOINT env to be injected")
	}
}

func TestBuildDeployment_BYOImage(t *testing.T) {
	a := &AgentScopeJavaAdapter{}
	replicas := int32(2)
	agent := &v1alpha1.Agent{
		ObjectMeta: metaObjectMeta("custom", "ns1"),
		Spec: v1alpha1.AgentSpec{
			Type:    v1alpha1.AgentTypeBYO,
			Runtime: RuntimeAgentScopeJava,
			BYO: &v1alpha1.BYOSpec{
				Image:    "example.com/my-agent:1.2.3",
				Replicas: &replicas,
				Command:  []string{"/bin/agent"},
			},
		},
	}
	dep, err := a.BuildDeployment(agent)
	if err != nil {
		t.Fatalf("BuildDeployment: %v", err)
	}
	c := dep.Spec.Template.Spec.Containers[0]
	if c.Image != "example.com/my-agent:1.2.3" {
		t.Errorf("BYO image = %q, want custom image", c.Image)
	}
	if *dep.Spec.Replicas != 2 {
		t.Errorf("replicas = %d, want 2", *dep.Spec.Replicas)
	}
	if len(c.Command) != 1 || c.Command[0] != "/bin/agent" {
		t.Errorf("command = %v, want [/bin/agent]", c.Command)
	}
}

func TestBuildConfigMap(t *testing.T) {
	a := &AgentScopeJavaAdapter{}
	cm, err := a.BuildConfigMap(declarativeAgent(), []ToolConfig{
		{Name: "search", MCPServer: "tavily", URL: "http://tavily", ToolNames: []string{"search"}},
	})
	if err != nil {
		t.Fatalf("BuildConfigMap: %v", err)
	}
	if cm.Name != "chatbot-config" {
		t.Errorf("configmap name = %q", cm.Name)
	}
	raw, ok := cm.Data["agent-config.json"]
	if !ok {
		t.Fatal("missing agent-config.json")
	}
	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		t.Fatalf("config json invalid: %v", err)
	}
	if parsed["systemMessage"] != "you are helpful" {
		t.Errorf("systemMessage = %v", parsed["systemMessage"])
	}
	if parsed["modelConfigRef"] != "chatbot-model" {
		t.Errorf("modelConfigRef = %v", parsed["modelConfigRef"])
	}
	if _, ok := parsed["tools"]; !ok {
		t.Error("expected tools in config")
	}
}

func TestBuildConfigMap_RequiresDeclarative(t *testing.T) {
	a := &AgentScopeJavaAdapter{}
	agent := &v1alpha1.Agent{
		ObjectMeta: metaObjectMeta("byo", "default"),
		Spec:       v1alpha1.AgentSpec{Type: v1alpha1.AgentTypeBYO},
	}
	if _, err := a.BuildConfigMap(agent, nil); err == nil {
		t.Error("expected error building ConfigMap for non-declarative agent")
	}
}

func TestBuildService(t *testing.T) {
	a := &AgentScopeJavaAdapter{}
	svc, err := a.BuildService(declarativeAgent())
	if err != nil {
		t.Fatalf("BuildService: %v", err)
	}
	if svc.Spec.Ports[0].Port != defaultJavaPort {
		t.Errorf("service port = %d, want %d", svc.Spec.Ports[0].Port, defaultJavaPort)
	}
	if svc.Spec.Selector["app"] != "chatbot" {
		t.Errorf("selector = %v", svc.Spec.Selector)
	}
}

func TestRegistry(t *testing.T) {
	if !IsRegistered(RuntimeAgentScopeJava) {
		t.Error("expected agentscope-java to be registered")
	}
	if IsRegistered("does-not-exist") {
		t.Error("unexpected registration for unknown runtime")
	}
	if _, err := Get(RuntimeAgentScopeJava); err != nil {
		t.Errorf("Get(java): %v", err)
	}
	if _, err := Get("nope"); err == nil {
		t.Error("expected error for unknown runtime")
	}
}
