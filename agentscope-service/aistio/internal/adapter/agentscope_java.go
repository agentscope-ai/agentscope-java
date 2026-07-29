package adapter

import (
	"encoding/json"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

const (
	RuntimeAgentScopeJava = "agentscope-java"
	defaultJavaImage      = "registry.cn-hangzhou.aliyuncs.com/agentscope/runtime-java:latest"
	defaultJavaPort       = int32(8080)
)

// AgentScopeJavaAdapter implements DataPlaneAdapter for the AgentScope Java runtime.
type AgentScopeJavaAdapter struct{}

func (a *AgentScopeJavaAdapter) RuntimeName() string {
	return RuntimeAgentScopeJava
}

func (a *AgentScopeJavaAdapter) DefaultPort() int32 {
	return defaultJavaPort
}

func (a *AgentScopeJavaAdapter) HealthProbe() *corev1.Probe {
	return &corev1.Probe{
		ProbeHandler: corev1.ProbeHandler{
			HTTPGet: &corev1.HTTPGetAction{
				Path: "/agentscope/health",
				Port: intstr.FromInt32(defaultJavaPort),
			},
		},
		InitialDelaySeconds: 10,
		PeriodSeconds:       10,
		TimeoutSeconds:      3,
	}
}

func (a *AgentScopeJavaAdapter) SupportsFeature(feature string) bool {
	supported := map[string]bool{
		"session-reporting":   true,
		"hot-reload":          true,
		"context-compression": true,
		"sandbox-request":     true,
	}
	return supported[feature]
}

func (a *AgentScopeJavaAdapter) BuildDeployment(agent *v1alpha1.Agent) (*appsv1.Deployment, error) {
	replicas := int32(1)
	image := defaultJavaImage

	if agent.Spec.Type == v1alpha1.AgentTypeDeclarative && agent.Spec.Declarative != nil {
		if agent.Spec.Declarative.Replicas != nil {
			replicas = *agent.Spec.Declarative.Replicas
		}
	} else if agent.Spec.Type == v1alpha1.AgentTypeBYO && agent.Spec.BYO != nil {
		if agent.Spec.BYO.Image != "" {
			image = agent.Spec.BYO.Image
		}
		if agent.Spec.BYO.Replicas != nil {
			replicas = *agent.Spec.BYO.Replicas
		}
	}

	labels := map[string]string{
		"app":                      agent.Name,
		"agentscope.io/managed":    "true",
		"agentscope.io/agent-name": agent.Name,
		"agentscope.io/runtime":    RuntimeAgentScopeJava,
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
				ObjectMeta: metav1.ObjectMeta{
					Labels: labels,
				},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{
						{
							Name:  "agent",
							Image: image,
							Ports: []corev1.ContainerPort{
								{
									Name:          "http",
									ContainerPort: defaultJavaPort,
									Protocol:      corev1.ProtocolTCP,
								},
							},
							LivenessProbe:  a.HealthProbe(),
							ReadinessProbe: a.HealthProbe(),
							VolumeMounts: []corev1.VolumeMount{
								{
									Name:      "agent-config",
									MountPath: "/app/config",
									ReadOnly:  true,
								},
							},
						},
					},
					Volumes: []corev1.Volume{
						{
							Name: "agent-config",
							VolumeSource: corev1.VolumeSource{
								ConfigMap: &corev1.ConfigMapVolumeSource{
									LocalObjectReference: corev1.LocalObjectReference{
										Name: fmt.Sprintf("%s-config", agent.Name),
									},
								},
							},
						},
					},
				},
			},
		},
	}

	if agent.Spec.Type == v1alpha1.AgentTypeDeclarative && agent.Spec.Declarative != nil {
		a.applyDeclarativeConfig(dep, agent)
	} else if agent.Spec.Type == v1alpha1.AgentTypeBYO && agent.Spec.BYO != nil {
		a.applyBYOConfig(dep, agent)
	}

	return dep, nil
}

func (a *AgentScopeJavaAdapter) applyDeclarativeConfig(dep *appsv1.Deployment, agent *v1alpha1.Agent) {
	decl := agent.Spec.Declarative
	container := &dep.Spec.Template.Spec.Containers[0]

	if decl.Resources != nil {
		container.Resources = buildResourceRequirements(decl.Resources)
	}

	for _, env := range decl.Env {
		container.Env = append(container.Env, corev1.EnvVar{
			Name:  env.Name,
			Value: env.Value,
		})
	}

	container.Env = append(container.Env, corev1.EnvVar{
		Name:  "AGENTSCOPE_CP_ENDPOINT",
		Value: "http://aistiod.aistio-system.svc:8080",
	})
	container.Env = append(container.Env, corev1.EnvVar{
		Name:  "AGENTSCOPE_CP_GRPC",
		Value: "aistiod.aistio-system.svc:15010",
	})
}

func (a *AgentScopeJavaAdapter) applyBYOConfig(dep *appsv1.Deployment, agent *v1alpha1.Agent) {
	byo := agent.Spec.BYO
	container := &dep.Spec.Template.Spec.Containers[0]

	if byo.Command != nil {
		container.Command = byo.Command
	}
	if byo.Args != nil {
		container.Args = byo.Args
	}
	if byo.Resources != nil {
		container.Resources = buildResourceRequirements(byo.Resources)
	}
	for _, env := range byo.Env {
		container.Env = append(container.Env, corev1.EnvVar{
			Name:  env.Name,
			Value: env.Value,
		})
	}
}

func (a *AgentScopeJavaAdapter) BuildConfigMap(agent *v1alpha1.Agent, tools []ToolConfig) (*corev1.ConfigMap, error) {
	if agent.Spec.Declarative == nil {
		return nil, fmt.Errorf("ConfigMap building requires Declarative spec")
	}

	// RenderAgentConfig is the shared source of truth (also used by the ASDP
	// hot-reload push) and covers systemMessage/model/tools/subagents/teamTemplates.
	config := RenderAgentConfig(agent, tools)
	config["runtime"] = RuntimeAgentScopeJava
	// Skills are delivered as their own config type over ASDP; for the startup
	// ConfigMap they are embedded alongside the rest of the agent config.
	if skills := RenderSkills(agent); len(skills) > 0 {
		config["skills"] = skills
	}

	configJSON, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("marshaling agent config: %w", err)
	}

	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("%s-config", agent.Name),
			Namespace: agent.Namespace,
			Labels: map[string]string{
				"agentscope.io/agent-name": agent.Name,
			},
			OwnerReferences: []metav1.OwnerReference{
				*metav1.NewControllerRef(agent, v1alpha1.GroupVersion.WithKind("Agent")),
			},
		},
		Data: map[string]string{
			"agent-config.json": string(configJSON),
		},
	}

	return cm, nil
}

func (a *AgentScopeJavaAdapter) BuildService(agent *v1alpha1.Agent) (*corev1.Service, error) {
	svc := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      agent.Name,
			Namespace: agent.Namespace,
			Labels: map[string]string{
				"agentscope.io/agent-name": agent.Name,
			},
			OwnerReferences: []metav1.OwnerReference{
				*metav1.NewControllerRef(agent, v1alpha1.GroupVersion.WithKind("Agent")),
			},
		},
		Spec: corev1.ServiceSpec{
			Selector: map[string]string{"app": agent.Name},
			Ports: []corev1.ServicePort{
				{
					Name:       "http",
					Port:       defaultJavaPort,
					TargetPort: intstr.FromInt32(defaultJavaPort),
					Protocol:   corev1.ProtocolTCP,
				},
			},
			Type: corev1.ServiceTypeClusterIP,
		},
	}
	return svc, nil
}

func buildResourceRequirements(r *v1alpha1.ResourceRequirements) corev1.ResourceRequirements {
	req := corev1.ResourceRequirements{}
	if r.Requests.CPU != "" || r.Requests.Memory != "" {
		req.Requests = corev1.ResourceList{}
		if r.Requests.CPU != "" {
			req.Requests[corev1.ResourceCPU] = mustParseQuantity(r.Requests.CPU)
		}
		if r.Requests.Memory != "" {
			req.Requests[corev1.ResourceMemory] = mustParseQuantity(r.Requests.Memory)
		}
	}
	if r.Limits.CPU != "" || r.Limits.Memory != "" {
		req.Limits = corev1.ResourceList{}
		if r.Limits.CPU != "" {
			req.Limits[corev1.ResourceCPU] = mustParseQuantity(r.Limits.CPU)
		}
		if r.Limits.Memory != "" {
			req.Limits[corev1.ResourceMemory] = mustParseQuantity(r.Limits.Memory)
		}
	}
	return req
}

func mustParseQuantity(s string) resource.Quantity {
	q, _ := resource.ParseQuantity(s)
	return q
}
