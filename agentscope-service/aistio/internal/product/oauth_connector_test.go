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
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

type oauthFixture struct {
	t                      *testing.T
	s                      *Server
	router                 *gin.Engine
	vault, owner, base, id string
	config                 oauthConnectionRequest
	calls                  atomic.Int32
	challenge              string
}
type testOAuthStart struct {
	FlowID           string `json:"flowId"`
	AuthorizationURL string `json:"authorizationUrl"`
	Cookie           *http.Cookie
}

func newOAuthFixture(t *testing.T) *oauthFixture {
	t.Helper()
	f := &oauthFixture{t: t, s: resourceTestServer(t), vault: shortID("ov_"), owner: shortID("owner_")}
	provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		user, secret, ok := r.BasicAuth()
		if !ok || user != "client" || secret != "client-secret" {
			t.Errorf("incorrect client authentication")
		}
		if r.Form.Get("resource") != "https://crm.example/mcp" {
			t.Error("missing resource")
		}
		if r.Form.Get("grant_type") == "refresh_token" {
			if r.Form.Get("refresh_token") != "refresh-secret" {
				t.Error("incorrect refresh token")
			}
			_, _ = w.Write([]byte(`{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600,"token_type":"Bearer"}`))
			return
		}
		challenge := sha256.Sum256([]byte(r.Form.Get("code_verifier")))
		if base64.RawURLEncoding.EncodeToString(challenge[:]) != f.challenge {
			t.Error("PKCE verifier does not match challenge")
		}
		if r.Form.Get("redirect_uri") != "http://localhost:18080"+oauthCallbackPrefix+f.id {
			t.Error("incorrect redirect URI")
		}
		if r.Form.Get("code") == "rejected" {
			w.WriteHeader(400)
			_, _ = w.Write([]byte(`{"error_description":"private-provider-details"}`))
			return
		}
		_, _ = w.Write([]byte(`{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600,"token_type":"Bearer","scope":"crm.read"}`))
	}))
	t.Cleanup(provider.Close)
	f.s.oauthHTTPClient = provider.Client()
	f.s.cfg.OAuthPublicURL = "http://localhost:18080"
	resourceSQL(t, f.s, `INSERT INTO vaults(vault_id,owner_id,display_name,created_at,updated_at) VALUES($1,$2,'OAuth',1,1)`, f.vault, f.owner)
	t.Cleanup(func() { resourceSQL(t, f.s, `DELETE FROM vaults WHERE vault_id=$1`, f.vault) })
	gin.SetMode(gin.TestMode)
	f.router = gin.New()
	f.router.Use(func(c *gin.Context) {
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
	f.s.registerOAuth(f.router)
	f.base = "/api/vaults/" + f.vault + "/oauth-connections"
	f.config = oauthConnectionRequest{ServerName: "crm", Endpoint: "https://crm.example/mcp", oauthSettings: oauthSettings{AuthorizationEndpoint: provider.URL + "/authorize", TokenEndpoint: provider.URL + "/token", ClientID: "client", ClientSecret: "client-secret", AuthMethod: "client_secret_basic", Scope: "crm.read offline_access", Resource: "https://crm.example/mcp"}}
	out := f.call("POST", f.base, mustJSON(f.config), nil, nil)
	f.expect(out, 201)
	var connection struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(out.Body.Bytes(), &connection); err != nil {
		t.Fatal(err)
	}
	f.id = connection.ID
	f.base += "/" + f.id
	return f
}
func (f *oauthFixture) call(method, path, body string, cookie *http.Cookie, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	out := httptest.NewRecorder()
	f.router.ServeHTTP(out, req)
	return out
}
func (f *oauthFixture) expect(out *httptest.ResponseRecorder, code int) {
	f.t.Helper()
	if out.Code != code {
		f.t.Fatalf("HTTP %d expected %d: %s", out.Code, code, out.Body.String())
	}
	for _, secret := range []string{"access-secret", "refresh-secret", "client-secret", "private-provider-details"} {
		if strings.Contains(out.Body.String(), secret) {
			f.t.Fatalf("response exposed a secret: %s", secret)
		}
	}
}
func (f *oauthFixture) start() testOAuthStart {
	f.t.Helper()
	out := f.call("POST", f.base+"/authorize", "", nil, nil)
	f.expect(out, 200)
	var start testOAuthStart
	if err := json.Unmarshal(out.Body.Bytes(), &start); err != nil {
		f.t.Fatal(err)
	}
	cookies := out.Result().Cookies()
	if len(cookies) != 1 {
		f.t.Fatal("missing browser binding cookie")
	}
	start.Cookie = cookies[0]
	if !start.Cookie.HttpOnly || start.Cookie.SameSite != http.SameSiteLaxMode || start.Cookie.Path != oauthCallbackPrefix+f.id {
		f.t.Fatal("unsafe cookie")
	}
	u, _ := url.Parse(start.AuthorizationURL)
	q := u.Query()
	f.challenge = q.Get("code_challenge")
	if q.Get("code_challenge_method") != "S256" || q.Get("state") == "" || q.Get("resource") != f.config.Resource {
		f.t.Fatal("invalid authorization URL")
	}
	return start
}
func (f *oauthFixture) callback(start testOAuthStart, extra url.Values, cookie *http.Cookie) *httptest.ResponseRecorder {
	u, _ := url.Parse(start.AuthorizationURL)
	extra.Set("state", u.Query().Get("state"))
	return f.call("GET", oauthCallbackPrefix+f.id+"?"+extra.Encode(), "", cookie, nil)
}
func (f *oauthFixture) flowPath(start testOAuthStart) string {
	return f.base + "/flows/" + start.FlowID
}

func TestMcpOAuthLifecycle(t *testing.T) {
	f := newOAuthFixture(t)
	start := f.start()
	f.expect(f.callback(start, url.Values{"code": {"accepted"}}, start.Cookie), 200)
	if f.calls.Load() != 1 {
		t.Fatal("expected one token exchange")
	}
	f.expect(f.callback(start, url.Values{"code": {"accepted"}}, start.Cookie), 400)
	f.expect(f.call("POST", f.flowPath(start)+"/complete", "", nil, map[string]string{"X-Test-User": "bob"}), 404)
	f.expect(f.call("POST", f.flowPath(start)+"/complete", "", nil, map[string]string{"X-Test-Owner": "different-namespace"}), 404)
	out := f.call("GET", f.flowPath(start), "", nil, nil)
	f.expect(out, 200)
	if !strings.Contains(out.Body.String(), `"authorized"`) {
		t.Fatal(out.Body.String())
	}
	var count int
	if err := f.s.db.Pool.QueryRow(context.Background(), `SELECT COUNT(*) FROM vault_credentials WHERE vault_id=$1`, f.vault).Scan(&count); err != nil || count != 0 {
		t.Fatal("credential finalized without original authenticated user")
	}
	f.expect(f.call("POST", f.flowPath(start)+"/complete", "", nil, nil), 200)
	f.expect(f.call("POST", f.flowPath(start)+"/complete", "", nil, nil), 200)
	var ciphertext []byte
	var target, kind string
	var revision int64
	if err := f.s.db.Pool.QueryRow(context.Background(), `SELECT ciphertext,target,type,revision FROM vault_credentials WHERE vault_id=$1`, f.vault).Scan(&ciphertext, &target, &kind, &revision); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(ciphertext, []byte("access-secret")) || target != f.config.Endpoint || kind != "mcp_oauth" || revision != 1 {
		t.Fatal("invalid Vault credential")
	}
	raw, err := decryptAESGCM(f.s.vaultKey, ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	rotated, changed, err := refreshOAuthSecret(context.Background(), raw, time.Now().Add(2*time.Hour), f.s.oauthHTTP())
	if err != nil || !changed || !strings.Contains(rotated, "rotated-refresh") {
		t.Fatalf("refresh: changed=%v err=%v", changed, err)
	}
	credentials, err := f.s.resolveVaultCredentials(context.Background(), []string{f.vault}, f.owner)
	if err != nil || len(credentials) != 1 {
		t.Fatalf("runtime resolution: %v", err)
	}
	resolved := mustJSON(credentials)
	if !strings.Contains(resolved, "access-secret") || strings.Contains(resolved, "refresh-secret") || strings.Contains(resolved, "client-secret") {
		t.Fatal("runtime must receive only access token")
	}
	next := f.start()
	f.expect(f.callback(next, url.Values{"code": {"accepted"}}, next.Cookie), 200)
	f.expect(f.call("POST", f.flowPath(next)+"/complete", "", nil, nil), 200)
	if err := f.s.db.Pool.QueryRow(context.Background(), `SELECT revision FROM vault_credentials WHERE vault_id=$1`, f.vault).Scan(&revision); err != nil || revision != 2 {
		t.Fatal("reconnect must rotate same credential")
	}
	f.expect(f.call("POST", f.base+"/disconnect", "", nil, nil), 200)
	if err := f.s.db.Pool.QueryRow(context.Background(), `SELECT COUNT(*) FROM vault_credentials WHERE vault_id=$1`, f.vault).Scan(&count); err != nil || count != 0 {
		t.Fatal("disconnect left credential")
	}
	out = f.call("GET", "/api/vaults/"+f.vault+"/oauth-connections", "", nil, nil)
	f.expect(out, 200)
	if !strings.Contains(out.Body.String(), `"connected":false`) {
		t.Fatal("disconnect removed configuration or retained status")
	}
}
func TestMcpOAuthCallbackGuards(t *testing.T) {
	for _, scenario := range []string{"cookie", "state", "denial", "expired", "cancelled", "superseded", "issuer", "provider-error", "archived"} {
		t.Run(scenario, func(t *testing.T) {
			f := newOAuthFixture(t)
			start := f.start()
			values := url.Values{"code": {"accepted"}}
			cookie := start.Cookie
			switch scenario {
			case "cookie":
				cookie = &http.Cookie{Name: start.Cookie.Name, Value: "different-browser"}
			case "state":
				start.AuthorizationURL = "https://example/authorize?state=unknown"
			case "denial":
				values.Set("error", "access_denied")
				values.Set("error_description", "private-provider-details")
			case "expired":
				resourceSQL(t, f.s, `UPDATE mcp_oauth_flows SET expires_at=1 WHERE flow_id=$1`, start.FlowID)
			case "cancelled":
				f.expect(f.call("POST", f.flowPath(start)+"/cancel", "", nil, nil), 204)
			case "superseded":
				f.start()
			case "issuer":
				f.config.Issuer = "https://issuer.example"
				f.expect(f.call("PATCH", f.base, mustJSON(f.config), nil, nil), 200)
				start = f.start()
				cookie = start.Cookie
				values.Set("iss", "https://wrong.example")
			case "provider-error":
				values.Set("code", "rejected")
			case "archived":
				resourceSQL(t, f.s, `UPDATE vaults SET archived_at=1 WHERE vault_id=$1`, f.vault)
			}
			f.expect(f.callback(start, values, cookie), 400)
			expectedCalls := int32(0)
			if scenario == "provider-error" {
				expectedCalls = 1
			}
			if f.calls.Load() != expectedCalls {
				t.Fatal("invalid callback exchanged a code")
			}
			out := f.call("POST", f.flowPath(start)+"/complete", "", nil, nil)
			if out.Code == 200 {
				t.Fatal("invalid flow completed")
			}
		})
	}
}
func TestMcpOAuthConcurrentCallback(t *testing.T) {
	f := newOAuthFixture(t)
	start := f.start()
	var wg sync.WaitGroup
	var successes atomic.Int32
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out := f.callback(start, url.Values{"code": {"accepted"}}, start.Cookie)
			if out.Code == 200 {
				successes.Add(1)
			}
		}()
	}
	wg.Wait()
	if successes.Load() != 1 || f.calls.Load() != 1 {
		t.Fatal("authorization code was exchanged more than once")
	}
}
func TestMcpOAuthConfigurationValidation(t *testing.T) {
	good := oauthConnectionRequest{ServerName: "crm", Endpoint: "https://crm.example/mcp", oauthSettings: oauthSettings{AuthorizationEndpoint: "https://auth.example/authorize", TokenEndpoint: "https://auth.example/token", ClientID: "client", AuthMethod: "none"}}
	if err := validateOAuthSettings(good); err != nil {
		t.Fatal(err)
	}
	for _, mutate := range []func(*oauthConnectionRequest){func(v *oauthConnectionRequest) { v.TokenEndpoint = "http://auth.example/token" }, func(v *oauthConnectionRequest) { v.AuthorizationEndpoint += "?state=override" }, func(v *oauthConnectionRequest) {
		v.AuthorizationParams = map[string]string{"redirect_uri": "https://attacker.example"}
	}, func(v *oauthConnectionRequest) { v.ClientSecret = "not-public" }, func(v *oauthConnectionRequest) { v.Endpoint = "https://user:pass@crm.example/mcp" }} {
		v := good
		mutate(&v)
		if validateOAuthSettings(v) == nil {
			t.Fatal("unsafe config accepted")
		}
	}
	s := &Server{}
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest("GET", "http://console.example/", nil)
	c.Request.Header.Set("X-Forwarded-Host", "attacker.example")
	if _, err := s.oauthOrigin(c); err == nil {
		t.Fatal("unconfigured public origin accepted")
	}
	s.cfg.OAuthPublicURL = "https://console.example"
	if origin, err := s.oauthOrigin(c); err != nil || origin != "https://console.example" {
		t.Fatal("configured origin not used")
	}
}
func TestMcpOAuthSecretCannotBeRetargeted(t *testing.T) {
	f := newOAuthFixture(t)
	f.config.ClientSecret = ""
	f.config.TokenEndpoint = "https://another.example/token"
	f.expect(f.call("PATCH", f.base, mustJSON(f.config), nil, nil), 400)
}

func TestMcpOAuthPublicCallbackOnly(t *testing.T) {
	gin.SetMode(gin.TestMode)
	s := &Server{}
	r := gin.New()
	r.Use(s.jwtMiddleware())
	s.registerOAuth(r)
	for _, tc := range []struct {
		method, path string
		status       int
	}{
		{"GET", oauthCallbackPrefix + "unknown", 400},
		{"POST", "/api/vaults/v/oauth-connections", 401},
		{"GET", "/api/vaults/v/oauth-connections", 401},
		{"POST", "/api/vaults/v/oauth-connections/c/flows/f/complete", 401},
	} {
		req := httptest.NewRequest(tc.method, tc.path, nil)
		out := httptest.NewRecorder()
		r.ServeHTTP(out, req)
		if out.Code != tc.status {
			t.Fatalf("%s: %d", tc.path, out.Code)
		}
	}
}

func TestMcpOAuthTokenEndpointAuthentication(t *testing.T) {
	for _, method := range []string{"none", "client_secret_basic", "client_secret_post"} {
		t.Run(method, func(t *testing.T) {
			provider := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if err := r.ParseForm(); err != nil {
					t.Fatal(err)
				}
				if r.Form.Get("grant_type") != "authorization_code" || r.Form.Get("code_verifier") != "verifier" {
					t.Error("missing code grant or PKCE")
				}
				switch method {
				case "none":
					if r.Header.Get("Authorization") != "" || r.Form.Get("client_secret") != "" {
						t.Error("public client sent credentials")
					}
				case "client_secret_post":
					if r.Form.Get("client_secret") != "secret +" || r.Header.Get("Authorization") != "" {
						t.Error("incorrect POST authentication")
					}
				case "client_secret_basic":
					user, secret, ok := r.BasicAuth()
					if !ok || user != url.QueryEscape("client +") || secret != url.QueryEscape("secret +") {
						t.Error("incorrect Basic authentication escaping")
					}
				}
				_, _ = w.Write([]byte(`{"access_token":"short-lived","token_type":"Bearer","expires_in":3600}`))
			}))
			defer provider.Close()
			cfg := oauthSettings{TokenEndpoint: provider.URL, ClientID: "client +", AuthMethod: method}
			if method != "none" {
				cfg.ClientSecret = "secret +"
			}
			raw, err := exchangeOAuthCode(context.Background(), provider.Client(), oauthFlowRequest{Settings: cfg, RedirectURI: "https://console.example/callback", Verifier: "verifier"}, "code", time.Now())
			if err != nil || strings.Contains(raw, "refresh") {
				t.Fatalf("token without refresh token: %v", err)
			}
		})
	}
}

func TestMcpOAuthCompletionRechecksVaultAndPreservesOtherCredentials(t *testing.T) {
	for _, scenario := range []string{"archived-after-callback", "duplicate-credential", "disconnect-after-callback"} {
		t.Run(scenario, func(t *testing.T) {
			f := newOAuthFixture(t)
			start := f.start()
			f.expect(f.callback(start, url.Values{"code": {"accepted"}}, start.Cookie), 200)
			switch scenario {
			case "archived-after-callback":
				resourceSQL(t, f.s, `UPDATE vaults SET archived_at=1 WHERE vault_id=$1`, f.vault)
			case "duplicate-credential":
				ct, err := encryptAESGCM(f.s.vaultKey, "existing-token")
				if err != nil {
					t.Fatal(err)
				}
				resourceSQL(t, f.s, `INSERT INTO vault_credentials(credential_id,vault_id,type,label,target,ciphertext,created_at) VALUES($1,$2,'static_bearer','existing','crm',$3,1)`, shortID("cred_"), f.vault, ct)
			case "disconnect-after-callback":
				f.expect(f.call("POST", f.base+"/disconnect", "", nil, nil), 200)
			}
			expected := 409
			if scenario == "archived-after-callback" {
				expected = 404
			}
			f.expect(f.call("POST", f.flowPath(start)+"/complete", "", nil, nil), expected)
			if scenario == "duplicate-credential" {
				var ct []byte
				if err := f.s.db.Pool.QueryRow(context.Background(), `SELECT ciphertext FROM vault_credentials WHERE vault_id=$1`, f.vault).Scan(&ct); err != nil {
					t.Fatal(err)
				}
				raw, err := decryptAESGCM(f.s.vaultKey, ct)
				if err != nil || raw != "existing-token" {
					t.Fatal("existing credential overwritten")
				}
			}
		})
	}
}
