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

package product

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/gin-gonic/gin"
)

type weixinFixture struct {
	t                       *testing.T
	s                       *Server
	router                  *gin.Engine
	owner, channel, account string
	provider                *httptest.Server
	mu                      sync.Mutex
	response                func(http.ResponseWriter, *http.Request)
	calls                   atomic.Int32
}

func newWeixinFixture(t *testing.T) *weixinFixture {
	t.Helper()
	f := &weixinFixture{t: t, s: resourceTestServer(t), owner: shortID("wx_owner_"), channel: shortID("wx_ch_"), account: shortID("wx_account_")}
	f.s.cfg.InternalToken = "test-internal"
	f.provider = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		if r.Header.Get("X-Builder-Internal-Token") != "test-internal" {
			t.Error("missing internal authentication")
		}
		if r.Method != "POST" || !strings.HasPrefix(r.URL.Path, "/api/internal/channel-providers/weixin/login/") {
			t.Error("invalid scheduler operation")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("invalid request")
		}
		if strings.HasSuffix(r.URL.Path, "/start") {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(gin.H{"session": weixinSession{"private-qr", "https://ilinkai.weixin.qq.com"}, "qrcodeImage": "data:image/png;base64,cXJjb2Rl"})
			return
		}
		if body["qrcode"] != "private-qr" || body["pollingBaseUrl"] != "https://ilinkai.weixin.qq.com" {
			t.Error("portable session not restored")
		}
		f.mu.Lock()
		handler := f.response
		f.mu.Unlock()
		if handler != nil {
			handler(w, r)
			return
		}
		json.NewEncoder(w).Encode(f.confirmed())
	}))
	t.Cleanup(f.provider.Close)
	f.s.cfg.SchedulerURL = f.provider.URL
	f.router = f.makeRouter(f.s)
	f.request("POST", "/api/channels", gin.H{"channelId": f.channel, "type": "weixin", "defaultAgentId": "agent", "disabled": false}, 200)
	t.Cleanup(func() {
		resourceSQL(t, f.s, `DELETE FROM vault_credentials WHERE vault_id IN (SELECT vault_id FROM vaults WHERE owner_id=$1)`, f.owner)
		resourceSQL(t, f.s, `DELETE FROM channels WHERE owner_id=$1`, f.owner)
		resourceSQL(t, f.s, `DELETE FROM vaults WHERE owner_id=$1`, f.owner)
	})
	return f
}
func (f *weixinFixture) confirmed() gin.H {
	return gin.H{"status": "confirmed", "session": weixinSession{"private-qr", "https://ilinkai.weixin.qq.com"}, "botToken": "runtime-secret", "accountId": f.account, "userId": "provider-user", "baseUrl": "https://ilinkai.weixin.qq.com"}
}
func (f *weixinFixture) makeRouter(s *Server) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(s.internalMiddleware())
	r.Use(func(c *gin.Context) {
		user := c.GetHeader("X-Test-User")
		if user == "" {
			user = "alice"
		}
		c.Set(ctxUserID, user)
		owner := c.GetHeader("X-Test-Owner")
		if owner == "" {
			owner = f.owner
		}
		SetResourceOwner(c, owner)
	})
	s.registerChannels(r)
	s.registerInternal(r)
	return r
}
func (f *weixinFixture) call(method, path string, body any, headers map[string]string) *httptest.ResponseRecorder {
	f.t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(mustJSON(body)))
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	return w
}
func (f *weixinFixture) request(method, path string, body any, status int) map[string]any {
	f.t.Helper()
	w := f.call(method, path, body, nil)
	if w.Code != status {
		f.t.Fatalf("%s %s: got %d want %d: %s", method, path, w.Code, status, w.Body.String())
	}
	if strings.Contains(path, "/weixin") && w.Header().Get("Cache-Control") != "no-store" {
		f.t.Fatal("login response permits caching")
	}
	for _, secret := range []string{"runtime-secret", "private-qr", "credentialRef", "ciphertext", "verify-secret", "private-error"} {
		if strings.Contains(w.Body.String(), secret) {
			f.t.Fatalf("public response contains %s", secret)
		}
	}
	result := map[string]any{}
	if w.Body.Len() > 0 {
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			f.t.Fatal(err)
		}
	}
	return result
}
func (f *weixinFixture) root() string { return "/api/channels/" + f.channel + "/weixin" }
func (f *weixinFixture) start() string {
	f.t.Helper()
	resourceSQL(f.t, f.s, `UPDATE weixin_connections SET next_start_at=0 WHERE channel_id=$1`, f.channel)
	out := f.request("POST", f.root()+"/link-flows", nil, 200)
	if out["status"] != "WAITING_SCAN" || out["qrcodeImage"] == nil {
		f.t.Fatalf("invalid challenge: %v", out)
	}
	return f.root() + "/link-flows/" + out["flowId"].(string)
}
func (f *weixinFixture) ready(path string) {
	resourceSQL(f.t, f.s, `UPDATE weixin_link_flows SET next_operation_at=0 WHERE flow_id=$1`, path[strings.LastIndex(path, "/")+1:])
}
func (f *weixinFixture) authorize() string {
	path := f.start()
	f.ready(path)
	out := f.request("POST", path+"/poll", nil, 200)
	if out["status"] != "AUTHORIZED" {
		f.t.Fatalf("not authorized: %v", out)
	}
	return path
}
func (f *weixinFixture) connection() weixinConnection {
	conn, err := scanWeixinConnection(f.s.db.Pool.QueryRow(context.Background(), weixinConnectionSelect+` WHERE channel_id=$1`, f.channel))
	if err != nil {
		f.t.Fatal(err)
	}
	return conn
}
func (f *weixinFixture) credential(revision int64, status int) *httptest.ResponseRecorder {
	f.t.Helper()
	w := f.call("POST", "/api/internal/channels/"+f.channel+"/credential", gin.H{"revision": revision}, map[string]string{"X-Builder-Internal-Token": "test-internal"})
	if w.Code != status {
		f.t.Fatalf("credential: %d want %d: %s", w.Code, status, w.Body.String())
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		f.t.Fatal("credential permits caching")
	}
	return w
}

func TestWeixinLoginPersistsAcrossReplicaAndCompletesAtomically(t *testing.T) {
	f := newWeixinFixture(t)
	ch, err := f.s.loadChannel(t.Context(), f.channel)
	if err != nil || !ch.Disabled {
		t.Fatal("draft must stay disabled")
	}
	f.request("POST", "/api/channels/"+f.channel+"/enable", nil, 409)
	path := f.start()
	id := path[strings.LastIndex(path, "/")+1:]
	var ciphertext []byte
	if err = f.s.db.Pool.QueryRow(t.Context(), `SELECT session_ciphertext FROM weixin_link_flows WHERE flow_id=$1`, id).Scan(&ciphertext); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(ciphertext), "private-qr") {
		t.Fatal("plaintext provider session")
	}
	// A second process has no in-memory flow and can continue with only DB, key and configuration.
	second := &Server{db: f.s.db, vaultKey: f.s.vaultKey, cfg: f.s.cfg}
	f.router = f.makeRouter(second)
	f.ready(path)
	f.request("POST", path+"/poll", nil, 200)
	var pending []byte
	if err = f.s.db.Pool.QueryRow(t.Context(), `SELECT credential_ciphertext FROM weixin_link_flows WHERE flow_id=$1`, id).Scan(&pending); err != nil || len(pending) == 0 {
		t.Fatal("no encrypted temporary credential")
	}
	if strings.Contains(string(pending), "runtime-secret") {
		t.Fatal("plaintext temporary token")
	}
	f.request("POST", path+"/complete", nil, 200)
	f.request("POST", path+"/complete", nil, 200)
	conn := f.connection()
	if conn.Revision != 1 || conn.CredentialID == "" {
		t.Fatal("completion not idempotent")
	}
	ch, _ = f.s.loadChannel(t.Context(), f.channel)
	if ch.Disabled || strings.Contains(deref(ch.PropertiesJSON), "runtime-secret") {
		t.Fatal("invalid installed channel")
	}
	var temporary int
	if err = f.s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM weixin_link_flows WHERE flow_id=$1 AND (session_ciphertext IS NOT NULL OR credential_ciphertext IS NOT NULL OR result_ciphertext IS NOT NULL)`, id).Scan(&temporary); err != nil || temporary != 0 {
		t.Fatal("temporary ciphertext retained")
	}
	f.request("GET", "/api/channels/"+f.channel, nil, 200)
	if !strings.Contains(f.credential(1, 200).Body.String(), "runtime-secret") {
		t.Fatal("runtime cannot resolve installed token")
	}
	w := f.call("GET", "/api/internal/channels/config", nil, map[string]string{"X-Builder-Internal-Token": "test-internal"})
	if w.Code != 200 || strings.Contains(w.Body.String(), "runtime-secret") || !strings.Contains(w.Body.String(), "credentialRevision") {
		t.Fatal("invalid runtime configuration")
	}
	f.request("POST", f.root()+"/disconnect", nil, 200)
	f.credential(1, 404)
	f.request("POST", path+"/complete", nil, 409)
}

func TestWeixinOwnershipRateLimitAndVerification(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.start()
	for _, header := range []map[string]string{{"X-Test-Owner": "other"}, {"X-Test-User": "bob"}} {
		w := f.call("POST", path+"/poll", nil, header)
		if w.Code != 404 {
			t.Fatalf("flow crossed owner/initiator: %d", w.Code)
		}
	}
	f.request("POST", f.root()+"/link-flows", nil, 429)
	f.request("POST", path+"/poll", nil, 429)
	f.ready(path)
	f.response = func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(gin.H{"status": "need_verifycode", "session": weixinSession{"private-qr", "https://ilinkai.weixin.qq.com"}})
	}
	if f.request("POST", path+"/poll", nil, 200)["status"] != "NEED_VERIFY_CODE" {
		t.Fatal("missing verification state")
	}
	f.request("POST", path+"/verify", gin.H{"verifyCode": ""}, 400)
	f.ready(path)
	f.mu.Lock()
	f.response = func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/verify") {
			t.Error("expected verify operation")
		}
		json.NewEncoder(w).Encode(f.confirmed())
	}
	f.mu.Unlock()
	f.request("POST", path+"/verify", gin.H{"verifyCode": "verify-secret"}, 200)
	f.request("POST", path+"/complete", nil, 200)
	unauth := f.call("POST", "/api/internal/channels/"+f.channel+"/credential", gin.H{"revision": 1}, nil)
	if unauth.Code != 401 {
		t.Fatal("public credential access allowed")
	}
	f.request("PUT", "/api/channels/"+f.channel, gin.H{"properties": gin.H{"botToken": "runtime-secret"}}, 400)
	f.request("PUT", "/api/channels/"+f.channel, gin.H{"properties": gin.H{"credentialRef": "another-credential"}}, 400)
}

func TestWeixinRotationAndCredentialScope(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	f.request("POST", path+"/complete", nil, 200)
	first := f.connection()
	path = f.authorize()
	f.request("POST", path+"/complete", nil, 200)
	second := f.connection()
	if first.VaultID != second.VaultID || first.CredentialID != second.CredentialID || second.Revision != 2 {
		t.Fatal("reauthorization did not rotate credential")
	}
	f.credential(1, 404)
	f.credential(2, 200)
	for _, sql := range []string{
		`UPDATE vaults SET owner_id='other' WHERE vault_id=$1`,
		`UPDATE vaults SET archived_at=1 WHERE vault_id=$1`,
	} {
		resourceSQL(t, f.s, sql, second.VaultID)
		f.credential(2, 404)
		resourceSQL(t, f.s, `UPDATE vaults SET owner_id=$2,archived_at=NULL WHERE vault_id=$1`, second.VaultID, f.owner)
	}
	resourceSQL(t, f.s, `UPDATE vault_credentials SET type='static_bearer' WHERE credential_id=$1`, second.CredentialID)
	f.credential(2, 404)
	resourceSQL(t, f.s, `UPDATE vault_credentials SET type='weixin_bot' WHERE credential_id=$1`, second.CredentialID)
	f.request("POST", "/api/channels/"+f.channel+"/disable", nil, 204)
	f.credential(2, 404)
	f.request("POST", "/api/channels/"+f.channel+"/enable", nil, 204)
	f.credential(2, 200)
	f.request("DELETE", "/api/channels/"+f.channel, nil, 204)
	var count int
	if err := f.s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM vault_credentials WHERE credential_id=$1`, second.CredentialID).Scan(&count); err != nil || count != 0 {
		t.Fatal("deleting channel left runtime credential")
	}
}

func TestWeixinRelinkReplacesAuthorizedAccount(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	f.request("POST", path+"/complete", nil, 200)
	oldAccount := f.connection().AccountID

	f.account = shortID("wx_replacement_account_")
	path = f.authorize()
	f.request("POST", path+"/complete", nil, 200)

	conn := f.connection()
	if conn.AccountID != f.account || conn.AccountID == oldAccount {
		t.Fatalf("reauthorization retained old account: got %q want %q", conn.AccountID, f.account)
	}
	status := f.request("GET", f.root()+"/status", nil, 200)
	if status["accountId"] != f.account {
		t.Fatalf("status returned stale account: got %v want %q", status["accountId"], f.account)
	}
}

func TestWeixinLateProviderResponseCannotReviveCancelledOrReplacedFlow(t *testing.T) {
	for _, operation := range []string{"cancel", "relink", "disable", "expire", "disconnect"} {
		t.Run(operation, func(t *testing.T) {
			f := newWeixinFixture(t)
			path := f.start()
			f.ready(path)
			entered, release := make(chan struct{}), make(chan struct{})
			f.response = func(w http.ResponseWriter, r *http.Request) {
				close(entered)
				<-release
				json.NewEncoder(w).Encode(f.confirmed())
			}
			done := make(chan *httptest.ResponseRecorder, 1)
			go func() { done <- f.call("POST", path+"/poll", nil, nil) }()
			<-entered
			// Another replica must reject a concurrent poll while allowing cancellation/relink.
			if w := f.call("POST", path+"/poll", nil, nil); w.Code != 429 {
				t.Errorf("parallel poll not rejected: %d", w.Code)
			}
			switch operation {
			case "cancel":
				f.request("POST", path+"/cancel", nil, 200)
			case "relink":
				f.start()
			case "disable":
				f.request("POST", "/api/channels/"+f.channel+"/disable", nil, 204)
			case "disconnect":
				f.request("POST", f.root()+"/disconnect", nil, 200)
			case "expire":
				resourceSQL(t, f.s, `UPDATE weixin_link_flows SET expires_at=1 WHERE channel_id=$1`, f.channel)
				if err := f.s.expireWeixinFlows(t.Context()); err != nil {
					t.Fatal(err)
				}
			}
			close(release)
			response := <-done
			if response.Code != 409 {
				t.Fatalf("late response applied: %d %s", response.Code, response.Body.String())
			}
			var count int
			f.s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM weixin_link_flows WHERE channel_id=$1 AND credential_ciphertext IS NOT NULL`, f.channel).Scan(&count)
			if count != 0 {
				t.Fatal("late credential retained")
			}
		})
	}
}

func TestWeixinExpiryProviderFailureAndCompletionRollback(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	resourceSQL(t, f.s, `UPDATE weixin_link_flows SET expires_at=1 WHERE channel_id=$1`, f.channel)
	f.request("POST", path+"/complete", nil, 409)
	if f.request("GET", path, nil, 200)["status"] != "EXPIRED" {
		t.Fatal("expired flow not cleared")
	}
	path = f.start()
	f.ready(path)
	f.response = func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(502)
		w.Write([]byte("private-error runtime-secret"))
	}
	if f.request("POST", path+"/poll", nil, 200)["status"] != "WAITING_SCAN" {
		t.Fatal("transient error killed login")
	}
	f.mu.Lock()
	f.response = nil
	f.mu.Unlock()
	f.ready(path)
	f.request("POST", path+"/poll", nil, 200)
	f.request("POST", path+"/complete", nil, 200)
	// A different Channel cannot install the same account, and its Vault insert rolls back.
	other := newWeixinFixture(t)
	other.account = f.account
	otherPath := other.authorize()
	other.request("POST", otherPath+"/complete", nil, 409)
	var count int
	other.s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM vaults WHERE owner_id=$1`, other.owner).Scan(&count)
	if count != 0 {
		t.Fatal("failed completion leaked a Vault")
	}
	// Credential is retained if rotation fails because the Vault became unavailable.
	conn := f.connection()
	path = f.authorize()
	resourceSQL(t, f.s, `UPDATE vaults SET archived_at=1 WHERE vault_id=$1`, conn.VaultID)
	f.request("POST", path+"/complete", nil, 409)
	if f.connection().Revision != 1 {
		t.Fatal("failed rotation partially committed")
	}
}

func TestWeixinLegacyPresenceCannotBypassManagedCredentialLifecycle(t *testing.T) {
	f := newWeixinFixture(t)
	path := f.authorize()
	f.request("POST", path+"/complete", nil, 200)
	presence := "/api/agents/agent/presences/" + f.channel
	f.request("GET", presence, nil, 200)
	f.request("PUT", presence, gin.H{"enabled": false, "credentials": gin.H{"botToken": "runtime-secret"}}, 400)
	f.request("DELETE", presence, nil, 400)
	f.credential(1, 200)
}

func TestWeixinDraftDisconnectAndConcurrentCompletion(t *testing.T) {
	f := newWeixinFixture(t)
	f.request("POST", f.root()+"/disconnect", nil, 200)
	if f.request("GET", f.root()+"/status", nil, 200)["status"] != "DISCONNECTED" {
		t.Fatal("draft disconnect was not persisted")
	}
	path := f.authorize()
	results := make(chan *httptest.ResponseRecorder, 2)
	for range 2 {
		go func() { results <- f.call("POST", path+"/complete", nil, nil) }()
	}
	for range 2 {
		if out := <-results; out.Code != 200 {
			t.Fatalf("concurrent complete: %d %s", out.Code, out.Body.String())
		}
	}
	if f.connection().Revision != 1 {
		t.Fatal("concurrent completion rotated twice")
	}
}
