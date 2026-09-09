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
package product

import (
	"context"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestChannelWindowUsesMostSpecificGroupRule(t *testing.T) {
	n := &model.Namespace{Groups: map[string]model.AccessGroup{"operators": {Members: []string{"alice"}}}}
	settings := ChannelWorkSettings{Routes: []ChannelWorkRoute{
		{AccountID: "org", PeerKind: "GROUP", PeerID: "room"},
		{AccountID: "org", PeerKind: "GROUP", PeerID: "room", ThreadID: "sensitive", RestrictGroups: true, AllowedGroups: []string{"operators"}},
	}}
	in := ChannelInbound{AccountID: "org", PeerKind: "GROUP", PeerID: "room", ThreadID: "sensitive"}
	if !settings.allowsWindow(n, "alice", in) {
		t.Fatal("group member rejected")
	}
	if settings.allowsWindow(n, "bob", in) {
		t.Fatal("thread restriction bypassed by broad rule")
	}
	delete(n.Groups, "operators")
	if settings.allowsWindow(n, "alice", in) {
		t.Fatal("removed group still admitted")
	}
	in.ThreadID = "general"
	if !settings.allowsWindow(n, "bob", in) {
		t.Fatal("unrestricted room denied")
	}
}

func TestMCPVaultDependenciesIncludeNestedOAuth(t *testing.T) {
	refs := ResourceVaultRefs(map[string]any{"servers": []any{map[string]any{"auth": map[string]any{"oauthVaultId": "credential"}}}})
	if len(refs) != 1 || refs[0] != "vault:credential" {
		t.Fatal(refs)
	}
}

func TestResourceSQLFilterPrecedesPaginationAndCount(t *testing.T) {
	s := resourceTestServer(t)
	owner := shortID("resource-owner-")
	for _, item := range []struct {
		id, name string
		updated  int
	}{{"hidden", "Hidden secret", 2}, {"visible", "Visible credential", 1}} {
		resourceSQL(t, s, `INSERT INTO vaults(vault_id,owner_id,display_name,created_at,updated_at) VALUES($1,$2,$3,1,$4)`, owner+item.id, owner, item.name, item.updated)
	}
	t.Cleanup(func() { s.db.Pool.Exec(context.Background(), `DELETE FROM vaults WHERE owner_id=$1`, owner) })
	router := gin.New()
	router.Use(func(c *gin.Context) { SetResourceOwner(c, owner); SetResourceFilter(c, []string{owner + "visible"}) })
	router.GET("/vaults", s.listVaults)
	out := httptest.NewRecorder()
	router.ServeHTTP(out, httptest.NewRequest("GET", "/vaults?limit=1", nil))
	if out.Code != 200 || !strings.Contains(out.Body.String(), "Visible credential") || strings.Contains(out.Body.String(), "Hidden secret") {
		t.Fatal(out.Code, out.Body.String())
	}
	if out.Header().Get("X-Total-Count") != "1" {
		t.Fatal("count reveals restricted resources", out.Header())
	}
}

func TestChannelTargetAndWindowRevocationGuardIntakeAndDelivery(t *testing.T) {
	f := channelSetup(t)
	f.s.channelWork.AuthorizeTarget = func(context.Context, *model.Namespace, string, string, string) error {
		return fmt.Errorf("resource use denied")
	}
	denied := f.send(t, f.in)
	if denied.IssueID != nil || !strings.Contains(denied.Reply, "使用权限") {
		t.Fatal("target policy did not block intake", denied)
	}
	f.s.channelWork.AuthorizeTarget = nil
	n := f.n
	n.Groups = map[string]model.AccessGroup{"reception": {Name: "Reception", Members: []string{f.user}, Roles: []string{"member"}}}
	var err error
	n, err = f.s.channelWork.Store.Access().PutNamespace(t.Context(), n, n.Version, f.user)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := f.s.loadChannelWorkSettings(t.Context(), f.ch.ChannelID)
	if err != nil {
		t.Fatal(err)
	}
	cfg.Routes = []ChannelWorkRoute{{AccountID: f.in.AccountID, PeerKind: f.in.PeerKind, PeerID: f.in.PeerID, ChannelTarget: cfg.DefaultTarget, RestrictGroups: true, AllowedGroups: []string{"reception"}}}
	resourceSQL(t, f.s, `UPDATE channel_work_settings SET config=$2 WHERE channel_id=$1`, f.ch.ChannelID, mustJSON(cfg))
	f.in.MessageID = uuid.NewString()
	accepted := f.send(t, f.in)
	if accepted.IssueID == nil {
		t.Fatal(accepted)
	}
	group := n.Groups["reception"]
	group.Members = nil
	n.Groups["reception"] = group
	if _, err = f.s.channelWork.Store.Access().PutNamespace(t.Context(), n, n.Version, f.user); err != nil {
		t.Fatal(err)
	}
	resourceSQL(t, f.s, `UPDATE channel_deliveries SET next_attempt=now()+interval '1 hour' WHERE channel_id=$1 AND issue_id IS DISTINCT FROM $2`, f.ch.ChannelID, accepted.IssueID)
	claim := f.request(t, "POST", "/api/internal/channels/deliveries/claim", map[string]any{}, "")
	if claim.Code != 204 {
		t.Fatal("queued content delivered after window access revoked", claim.Code, claim.Body.String())
	}
	var count int
	if err = f.s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM channel_deliveries WHERE channel_id=$1 AND issue_id=$2 AND state='cancelled'`, f.ch.ChannelID, accepted.IssueID).Scan(&count); err != nil || count == 0 {
		t.Fatal("revoked delivery not cancelled", count, err)
	}
	f.in.MessageID = uuid.NewString()
	denied = f.send(t, f.in)
	if denied.IssueID != nil {
		t.Fatal("revoked window accepted new task", denied)
	}
}
