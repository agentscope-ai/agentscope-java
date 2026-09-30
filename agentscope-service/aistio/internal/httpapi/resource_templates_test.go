// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package httpapi

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestWorkflowTemplateImportRebindsAndChecksExport(t *testing.T) {
	s, st, _ := managementTestServer(t)
	ctx := t.Context()
	target, err := st.Access().PutNamespace(ctx, &model.Namespace{Tenant: "default", Name: "support", DisplayName: "Support", Kind: "shared", Owner: "bob"}, 0, "bob")
	if err != nil {
		t.Fatal(err)
	}
	local, err := st.AgentCatalog().CreateAgent(ctx, &model.Agent{Tenant: "default", Namespace: "support", AgentKey: "local-worker", DisplayName: "Local worker", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	sourceAgent := uuid.NewString()
	spec := json.RawMessage(fmt.Sprintf(`{"nodes":[{"key":"worker","type":"agent","agentId":%q,"runtimeCandidate":{"binding":{"agentId":%q,"bindingId":%q,"kind":"managed","managedOwnerRef":"source-owner","managedDefinitionRef":"source-secret"}}}]}`, sourceAgent, sourceAgent, uuid.NewString()))
	d, err := st.Orchestration().CreateDefinition(ctx, &model.OrchestrationDefinition{Tenant: "default", Namespace: "engineering", Name: "Reusable triage", DraftSpec: spec})
	if err != nil {
		t.Fatal(err)
	}
	revision, err := st.Orchestration().CreateRevision(ctx, &model.OrchestrationRevision{DefinitionID: d.ID, Tenant: d.Tenant, Namespace: d.Namespace, Spec: spec, ExpectedDefinitionVersion: d.Version})
	if err != nil {
		t.Fatal(err)
	}
	source, _ := st.Access().GetNamespace(ctx, "default", "engineering")
	source.Resources = map[string]model.ResourcePolicy{"workflow:" + d.ID.String(): {Mode: "inherit", ExportTo: []string{target.Name}}}
	source, err = st.Access().PutNamespace(ctx, source, source.Version, "alice")
	if err != nil {
		t.Fatal(err)
	}
	base := "/api/v1/namespaces/support"
	w := accessRequest(s, "bob", "GET", base+"/shared-templates", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), d.ID.String()) || strings.Contains(w.Body.String(), "source-secret") {
		t.Fatal(w.Code, w.Body.String())
	}
	input := map[string]any{"sourceNamespace": source.Name, "id": d.ID.String(), "revisionId": revision.ID.String(), "sourceVersion": source.Version, "name": "Local triage", "bindings": map[string]string{}}
	post := func() (int, string) {
		b, _ := json.Marshal(input)
		w := accessRequest(s, "bob", "POST", base+"/import-template", string(b))
		return w.Code, w.Body.String()
	}
	if code, body := post(); code != 400 {
		t.Fatal("missing local binding accepted", code, body)
	}
	input["bindings"] = map[string]string{"agent:" + sourceAgent: "agent:" + local.ID.String()}
	code, body := post()
	if code != 201 {
		t.Fatal(code, body)
	}
	if strings.Contains(body, sourceAgent) || strings.Contains(body, "source-secret") || strings.Contains(body, "runtimeCandidate") || !strings.Contains(body, local.ID.String()) {
		t.Fatal("source bindings survived", body)
	}
	source.Resources = map[string]model.ResourcePolicy{}
	if _, err = st.Access().PutNamespace(ctx, source, source.Version, "alice"); err != nil {
		t.Fatal(err)
	}
	if code, body = post(); code != 404 {
		t.Fatal("revoked export accepted", code, body)
	}
}

func TestTaskResourceAuthorizationUsesCurrentGrants(t *testing.T) {
	s, st, _ := managementTestServer(t)
	a, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "runtime-worker", DisplayName: "Worker", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	n, _ := st.Access().GetNamespace(t.Context(), "default", "engineering")
	n.Resources = map[string]model.ResourcePolicy{"agent:" + a.ID.String(): {Mode: "restricted", Groups: map[string][]string{"workers": {"use"}}}}
	n.Groups = map[string]model.AccessGroup{"workers": {Name: "Workers", Members: []string{"bob"}, Roles: []string{"member"}}}
	n, err = st.Access().PutNamespace(t.Context(), n, n.Version, "alice")
	if err != nil {
		t.Fatal(err)
	}
	task := &model.AgentTask{Tenant: "default", Namespace: "engineering", AgentRef: a.ID.String(), AccountableHumanRef: "bob"}
	if err = s.authorizeTaskResources(t.Context(), task, model.RuntimeBindingCandidate{}); err != nil {
		t.Fatal(err)
	}
	g := n.Groups["workers"]
	g.Members = nil
	n.Groups["workers"] = g
	if _, err = st.Access().PutNamespace(t.Context(), n, n.Version, "alice"); err != nil {
		t.Fatal(err)
	}
	if err = s.authorizeTaskResources(t.Context(), task, model.RuntimeBindingCandidate{}); err == nil {
		t.Fatal("revoked group still permitted runtime execution")
	}
}

func TestExistingChatCannotContinueAfterResourceUseRevoked(t *testing.T) {
	s, st, _ := managementTestServer(t)
	a, err := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "chat-worker", DisplayName: "Worker", Status: model.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(t.Context(), &store.Session{Tenant: "default", Namespace: "engineering", AgentName: "worker", AgentID: a.ID, SessionID: "existing-worker-chat"})
	if err != nil {
		t.Fatal(err)
	}
	chat, err := st.Chats().Create(t.Context(), &model.Chat{Tenant: "default", Namespace: "engineering", AgentID: a.ID, SessionID: session.ID, CreatorRef: "bob", Status: model.ChatActive, Title: "Existing conversation"})
	if err != nil {
		t.Fatal(err)
	}
	n, _ := st.Access().GetNamespace(t.Context(), "default", "engineering")
	n.Resources = map[string]model.ResourcePolicy{"agent:" + a.ID.String(): {Mode: "restricted"}}
	if _, err = st.Access().PutNamespace(t.Context(), n, n.Version, "alice"); err != nil {
		t.Fatal(err)
	}
	w := accessRequest(s, "bob", "POST", "/api/v1/chats/"+chat.ID.String()+"/turns?namespace=engineering", `{"message":"continue"}`)
	if w.Code != 403 {
		t.Fatal("chat bypassed resource revocation", w.Code, w.Body.String())
	}
	w = accessRequest(s, "bob", "POST", "/api/v1/sessions/"+session.ID.String()+"/user-message?namespace=engineering", `{"content":"continue"}`)
	if w.Code != 403 {
		t.Fatal("session endpoint bypassed resource revocation", w.Code, w.Body.String())
	}

}
