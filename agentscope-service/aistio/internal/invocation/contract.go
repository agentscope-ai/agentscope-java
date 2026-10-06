package invocation

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/google/uuid"
	"github.com/santhosh-tekuri/jsonschema/v5"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// Contract is an immutable published service configuration, never a public trace.
// Runtime credentials and physical instance selection remain execution concerns.
type Contract struct {
	Policies    map[string]*model.AgentRuntimePolicy `json:"policies,omitempty"`
	Definitions map[string]json.RawMessage           `json:"definitions,omitempty"`
	Teams       map[string]*model.CollaborationTeam  `json:"teams,omitempty"`

	Endpoint  model.Endpoint                          `json:"endpoint"`
	Agents    []string                                `json:"agents"`
	Revisions map[string]*model.OrchestrationRevision `json:"revisions,omitempty"`
}

func ReadContract(raw json.RawMessage) (*Contract, error) {
	var c Contract
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, err
	}
	if c.Definitions == nil {
		c.Definitions = map[string]json.RawMessage{}
	}
	if c.Endpoint.ID == uuid.Nil {
		return nil, fmt.Errorf("service contract has no endpoint")
	}
	return &c, nil
}
func BoundEndpoint(ctx context.Context, st store.Store, inv *model.EndpointInvocation) (*model.Endpoint, error) {
	c, err := ReadContract(inv.Contract)
	if err != nil {
		return nil, err
	}
	return &c.Endpoint, nil
}
func schema(raw json.RawMessage) (*jsonschema.Schema, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	c := jsonschema.NewCompiler()
	// Service contracts cannot cause server-side network requests through $ref.
	c.LoadURL = func(string) (io.ReadCloser, error) {
		return nil, fmt.Errorf("external schema references are not supported; use local $defs")
	}
	if err := c.AddResource("https://agentscope.invalid/contract.json", strings.NewReader(string(raw))); err != nil {
		return nil, err
	}
	return c.Compile("https://agentscope.invalid/contract.json")
}
func CheckSchema(raw json.RawMessage) error { _, err := schema(raw); return err }
func Validate(raw json.RawMessage, value any) error {
	s, err := schema(raw)
	if err != nil {
		return err
	}
	if s == nil {
		return nil
	}
	return s.Validate(value)
}
func ValidateResult(ep *model.Endpoint, result json.RawMessage) error {
	var v any
	if len(result) > 0 {
		if err := json.Unmarshal(result, &v); err != nil {
			return err
		}
	}
	return Validate(ep.OutputSchema, v)
}
func Terminal(s model.EndpointInvocationStatus) bool {
	return s == model.EndpointInvocationCompleted || s == model.EndpointInvocationPartialSucceeded || s == model.EndpointInvocationFailed || s == model.EndpointInvocationCancelled || s == model.EndpointInvocationTimedOut
}
