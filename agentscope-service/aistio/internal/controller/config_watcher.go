package controller

import (
	"context"

	toolscache "k8s.io/client-go/tools/cache"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
	"github.com/spring-ai-alibaba/aistio/internal/adapter"
)

// ConfigPushWatcher drives ASDP config push from each replica's informer cache.
//
// It is intentionally NOT leader-election gated. The ASDP gRPC server runs on
// every replica, so data plane connections are spread across replicas; config
// push must therefore be triggered locally on whichever replica owns a given
// connection, not only on the leader. The leader-gated reconcilers continue to
// own status writes; this watcher only reads desired config from the cache and
// pushes it (which also keeps each replica's local SnapshotStore fresh so that
// PushFullSync on (re)connect works).
type ConfigPushWatcher struct {
	Client client.Client
	Cache  cache.Cache
	Dist   ConfigDistributor
}

// NeedLeaderElection returns false so this runnable starts on all replicas.
func (w *ConfigPushWatcher) NeedLeaderElection() bool { return false }

// Start registers informer handlers and blocks until the context is cancelled.
func (w *ConfigPushWatcher) Start(ctx context.Context) error {
	logger := log.FromContext(ctx).WithName("config-push-watcher")

	agentInf, err := w.Cache.GetInformer(ctx, &v1alpha1.Agent{})
	if err != nil {
		return err
	}
	if _, err := agentInf.AddEventHandler(toolscache.ResourceEventHandlerFuncs{
		AddFunc:    func(obj interface{}) { w.onAgent(ctx, obj) },
		UpdateFunc: func(_, obj interface{}) { w.onAgent(ctx, obj) },
		DeleteFunc: func(obj interface{}) { w.onAgentDelete(obj) },
	}); err != nil {
		return err
	}

	mcInf, err := w.Cache.GetInformer(ctx, &v1alpha1.ModelConfig{})
	if err != nil {
		return err
	}
	if _, err := mcInf.AddEventHandler(toolscache.ResourceEventHandlerFuncs{
		AddFunc:    func(obj interface{}) { w.onModelConfig(ctx, obj) },
		UpdateFunc: func(_, obj interface{}) { w.onModelConfig(ctx, obj) },
	}); err != nil {
		return err
	}

	mcpInf, err := w.Cache.GetInformer(ctx, &v1alpha1.MCPServer{})
	if err != nil {
		return err
	}
	if _, err := mcpInf.AddEventHandler(toolscache.ResourceEventHandlerFuncs{
		AddFunc:    func(obj interface{}) { w.onMCPServer(ctx, obj) },
		UpdateFunc: func(_, obj interface{}) { w.onMCPServer(ctx, obj) },
	}); err != nil {
		return err
	}

	logger.Info("config push watcher started on this replica")
	<-ctx.Done()
	return nil
}

func (w *ConfigPushWatcher) onAgent(ctx context.Context, obj interface{}) {
	agent, ok := obj.(*v1alpha1.Agent)
	if !ok || agent.Spec.Type != v1alpha1.AgentTypeDeclarative || agent.Spec.Declarative == nil {
		return
	}
	logger := log.FromContext(ctx)

	// Push the complete agent runtime config (systemMessage/model/tools/
	// subagents/teamTemplates) so hot-reload matches the startup ConfigMap.
	if err := w.Dist.PushConfig(agent.Namespace, agent.Name, DistConfigAgent,
		adapter.RenderAgentConfig(agent, nil)); err != nil {
		logger.Error(err, "push agent config failed", "agent", agent.Name)
	}

	// Skills are delivered as a dedicated config type so the data plane can
	// (re)load skill bundles independently of the core agent config.
	if skills := adapter.RenderSkills(agent); len(skills) > 0 {
		if err := w.Dist.PushConfig(agent.Namespace, agent.Name, DistConfigSkill, skills); err != nil {
			logger.Error(err, "push skill config failed", "agent", agent.Name)
		}
	}
}

func (w *ConfigPushWatcher) onAgentDelete(obj interface{}) {
	agent, ok := obj.(*v1alpha1.Agent)
	if !ok {
		tomb, ok2 := obj.(toolscache.DeletedFinalStateUnknown)
		if !ok2 {
			return
		}
		agent, ok = tomb.Obj.(*v1alpha1.Agent)
		if !ok {
			return
		}
	}
	w.Dist.ForgetAgent(agent.Namespace, agent.Name)
}

func (w *ConfigPushWatcher) onModelConfig(ctx context.Context, obj interface{}) {
	mc, ok := obj.(*v1alpha1.ModelConfig)
	if !ok {
		return
	}
	var agents v1alpha1.AgentList
	if err := w.Client.List(ctx, &agents, client.InNamespace(mc.Namespace)); err != nil {
		return
	}
	for i := range agents.Items {
		a := &agents.Items[i]
		if a.Spec.Declarative != nil && a.Spec.Declarative.AgentConfig.ModelConfigRef == mc.Name {
			if err := w.Dist.PushConfig(a.Namespace, a.Name, DistConfigModel, mc.Spec); err != nil {
				log.FromContext(ctx).Error(err, "push model config failed", "agent", a.Name)
			}
		}
	}
}

func (w *ConfigPushWatcher) onMCPServer(ctx context.Context, obj interface{}) {
	mcp, ok := obj.(*v1alpha1.MCPServer)
	if !ok {
		return
	}
	var agents v1alpha1.AgentList
	if err := w.Client.List(ctx, &agents, client.InNamespace(mcp.Namespace)); err != nil {
		return
	}
	for i := range agents.Items {
		a := &agents.Items[i]
		if referencesServer(*a, mcp.Name) {
			if err := w.Dist.PushConfig(a.Namespace, a.Name, DistConfigTool, mcp.Spec); err != nil {
				log.FromContext(ctx).Error(err, "push tool config failed", "agent", a.Name)
			}
		}
	}
}
