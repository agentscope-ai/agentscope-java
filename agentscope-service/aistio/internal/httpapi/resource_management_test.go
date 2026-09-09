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

package httpapi

import (
	"encoding/json"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"strings"
	"testing"
)

func TestResourcePolicyEnforcementAndGroups(t *testing.T) {
	s, st, _ := managementTestServer(t)
	agent, e := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "worker", DisplayName: "Worker", Status: model.AgentActive})
	if e != nil {
		t.Fatal(e)
	}
	path := "/api/v1/namespaces/engineering/resources/agent/" + agent.ID.String() + "/access"
	body := `{"version":1,"policy":{"mode":"restricted","users":{"bob":["discover"]}}}`
	if w := accessRequest(s, "bob", "PUT", path, body); w.Code != 404 {
		t.Fatal("member changed policy", w.Code)
	}
	if w := accessRequest(s, "alice", "PUT", path, body); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := accessRequest(s, "alice", "PUT", path, body); w.Code != 409 {
		t.Fatal("stale policy accepted", w.Code)
	}
	w := accessRequest(s, "bob", "POST", "/api/v1/issues?namespace=engineering", `{"title":"restricted invocation","assigneeType":"agent","assigneeRef":"`+agent.ID.String()+`"}`)
	if w.Code != 403 && w.Code != 404 {
		t.Fatal("restricted invocation accepted", w.Code, w.Body.String())
	}
	w = accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering/groups", `{"version":2,"groups":{"builders":{"name":"Builders","members":["carol"],"roles":["developer"]}}}`)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	n, _ := st.Access().GetNamespace(t.Context(), "default", "engineering")
	if !model.NamespaceAllows(n.Roles("carol"), "configure") {
		t.Fatal("group role not effective")
	}
	w = accessRequest(s, "carol", "PUT", "/api/v1/namespaces/engineering/groups", `{"version":3,"groups":{"builders":{"name":"Builders","members":["carol"],"roles":["auditor"]}}}`)
	if w.Code != 403 {
		t.Fatal("namespace admin self-granted auditor", w.Code, w.Body.String())
	}
	w = accessRequest(s, "bob", "POST", "/api/v1/namespaces/engineering/requests", `{"version":3,"resource":"agent:`+agent.ID.String()+`","action":"use","reason":"Need to run this worker"}`)
	if w.Code != 201 {
		t.Fatal(w.Code, w.Body.String())
	}
	var response struct {
		Request model.AccessRequest `json:"request"`
	}
	if e = json.Unmarshal(w.Body.Bytes(), &response); e != nil {
		t.Fatal(e)
	}
	w = accessRequest(s, "alice", "POST", "/api/v1/namespaces/engineering/requests/"+response.Request.ID+"/review", `{"version":4,"approve":true}`)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	n, _ = st.Access().GetNamespace(t.Context(), "default", "engineering")
	if !n.Decide("bob", "agent:"+agent.ID.String(), "use").Allowed {
		t.Fatal("approved grant missing")
	}
	audits, e := st.Access().ListNamespaceAudit(t.Context(), "default", "engineering", 50, 0)
	if e != nil || len(audits) != 5 {
		t.Fatal("grant audit missing", len(audits), e)
	}
}
func TestResourceDependencyDelegation(t *testing.T) {
	n := &model.Namespace{Name: "engineering", Owner: "alice", Kind: "shared", Members: map[string][]string{"bob": {"member"}}, Resources: map[string]model.ResourcePolicy{"vault:secret": {Mode: "restricted", Consumers: []string{"managed-agent:worker"}}}}
	items := resourceMap([]model.ResourceDescriptor{{Kind: "agent", ID: "root", Dependencies: []string{"managed-agent:worker"}}, {Kind: "managed-agent", ID: "worker", Dependencies: []string{"vault:secret"}}, {Kind: "vault", ID: "secret"}})
	if e := checkResourceGraph(n, items, "bob", "agent:root"); e != nil {
		t.Fatal(e)
	}
	if n.Decide("bob", "vault:secret", "inspect").Allowed {
		t.Fatal("runtime delegation disclosed vault")
	}
	if e := checkResourceGraph(n, items, "bob", "vault:secret"); e == nil {
		t.Fatal("direct dependency use allowed")
	}
	p := n.Resources["vault:secret"]
	p.Consumers = nil
	n.Resources["vault:secret"] = p
	if e := checkResourceGraph(n, items, "bob", "agent:root"); e == nil {
		t.Fatal("revoked delegation accepted")
	}
	p.Consumers = []string{"managed-agent:worker"}
	n.Resources["vault:secret"] = p
	r := items["vault:secret"]
	r.Dependencies = []string{"agent:root"}
	items[r.Key()] = r
	if e := checkResourceGraph(n, items, "bob", "agent:root"); e == nil || !strings.Contains(e.Error(), "cycle") {
		t.Fatal("cycle not detected", e)
	}
}
func TestResourceListingFiltersBeforeLimit(t *testing.T) {
	s, st, _ := managementTestServer(t)
	a, _ := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "a-private", DisplayName: "Private", Status: model.AgentActive})
	b, _ := st.AgentCatalog().CreateAgent(t.Context(), &model.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "b-public", DisplayName: "Public", Status: model.AgentActive})
	n, _ := st.Access().GetNamespace(t.Context(), "default", "engineering")
	n.Resources = map[string]model.ResourcePolicy{"agent:" + a.ID.String(): {Mode: "restricted"}}
	if _, e := st.Access().PutNamespace(t.Context(), n, n.Version, "alice"); e != nil {
		t.Fatal(e)
	}
	w := accessRequest(s, "bob", "GET", "/api/v1/agents?namespace=engineering&limit=1", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), b.ID.String()) || strings.Contains(w.Body.String(), a.ID.String()) {
		t.Fatal("resource filter after limit", w.Code, w.Body.String())
	}
}

func TestResourcePublishDoesNotRequireRootUse(t *testing.T) {
	n := &model.Namespace{Owner: "alice", Members: map[string][]string{"bob": {"member"}}, Resources: map[string]model.ResourcePolicy{"workflow:flow": {Mode: "restricted", Users: map[string][]string{"bob": {"publish"}}}}}
	items := resourceMap([]model.ResourceDescriptor{{Kind: "workflow", ID: "flow", Dependencies: []string{"agent:worker"}}, {Kind: "agent", ID: "worker"}})
	if err := checkResourceGraphAction(n, items, "bob", "workflow:flow", "publish"); err != nil {
		t.Fatal(err)
	}
	if err := checkResourceGraph(n, items, "bob", "workflow:flow"); err == nil {
		t.Fatal("publish granted execution")
	}
}
func TestManagedDefinitionAliasCannotBypassCatalogRestriction(t *testing.T) {
	n := &model.Namespace{Owner: "alice", Members: map[string][]string{"bob": {"developer"}}, Resources: map[string]model.ResourcePolicy{"agent:worker": {Mode: "restricted", Users: map[string][]string{"bob": {"use"}}}}}
	items := []model.ResourceDescriptor{{Kind: "agent", ID: "worker", Dependencies: []string{"managed-agent:definition"}}, {Kind: "managed-agent", ID: "definition"}}
	if productResourceAllowed(n, items, "bob", "managed-agent:definition", "inspect") || productResourceAllowed(n, items, "bob", "managed-agent:definition", "edit") {
		t.Fatal("legacy definition bypassed restricted catalog Agent")
	}
	if !productResourceAllowed(n, items, "bob", "managed-agent:definition", "use") {
		t.Fatal("authorized runtime use denied")
	}
}
