package discovery

import (
	"context"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// AdoptRequest contains parameters for adopting an existing Deployment.
type AdoptRequest struct {
	DeploymentName string
	Namespace      string
	AgentName      string // optional override; defaults to deployment name
	Runtime        string // optional; will be probed if empty
}

// AdoptResult contains the outcome of the adopt operation.
type AdoptResult struct {
	AgentName      string `json:"agentName"`
	DeploymentName string `json:"deploymentName"`
	Namespace      string `json:"namespace"`
	LabelsApplied  bool   `json:"labelsApplied"`
}

// Adopter implements the CLI adopt workflow: labels a Deployment so
// DiscoveryController picks it up.
type Adopter struct {
	client client.Client
}

// NewAdopter creates an Adopter instance.
func NewAdopter(c client.Client) *Adopter {
	return &Adopter{client: c}
}

// Adopt labels the specified Deployment with agentscope.io/managed=true
// and optional metadata annotations, then waits for DiscoveryController
// to create the Agent CRD.
func (a *Adopter) Adopt(ctx context.Context, req AdoptRequest) (*AdoptResult, error) {
	// Validate deployment exists
	var dep appsv1.Deployment
	if err := a.client.Get(ctx, types.NamespacedName{
		Name:      req.DeploymentName,
		Namespace: req.Namespace,
	}, &dep); err != nil {
		return nil, fmt.Errorf("deployment %s/%s not found: %w", req.Namespace, req.DeploymentName, err)
	}

	// Apply labels
	if dep.Labels == nil {
		dep.Labels = make(map[string]string)
	}
	dep.Labels[LabelManaged] = "true"

	agentName := req.AgentName
	if agentName == "" {
		agentName = req.DeploymentName
	}
	dep.Labels[LabelAgentName] = agentName

	// Apply annotations
	if dep.Annotations == nil {
		dep.Annotations = make(map[string]string)
	}
	if req.Runtime != "" {
		dep.Annotations[AnnoRuntime] = req.Runtime
	}

	// Update the Deployment
	if err := a.client.Update(ctx, &dep); err != nil {
		return nil, fmt.Errorf("updating deployment labels: %w", err)
	}

	return &AdoptResult{
		AgentName:      agentName,
		DeploymentName: req.DeploymentName,
		Namespace:      req.Namespace,
		LabelsApplied:  true,
	}, nil
}

// Unadopt removes the agentscope.io/managed label from a Deployment.
func (a *Adopter) Unadopt(ctx context.Context, deploymentName, namespace string) error {
	var dep appsv1.Deployment
	if err := a.client.Get(ctx, types.NamespacedName{
		Name:      deploymentName,
		Namespace: namespace,
	}, &dep); err != nil {
		return err
	}

	if dep.Labels != nil {
		delete(dep.Labels, LabelManaged)
		delete(dep.Labels, LabelAgentName)
	}

	return a.client.Update(ctx, &dep)
}
