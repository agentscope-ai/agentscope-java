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
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

type githubTestTransport func(*http.Request) (*http.Response, error)

func (f githubTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func githubResponse(code int, body string) *http.Response {
	return &http.Response{StatusCode: code, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(body))}
}

func newGitHubFixture(t *testing.T) *oauthFixture {
	f := &oauthFixture{t: t, s: resourceTestServer(t), vault: shortID("ghv_"), owner: shortID("ghowner_")}
	f.s.cfg.OAuthPublicURL = "http://localhost:18080"
	resourceSQL(t, f.s, `DELETE FROM oauth_provider_apps WHERE provider='github'`)
	resourceSQL(t, f.s, `INSERT INTO vaults(vault_id,owner_id,display_name,created_at,updated_at) VALUES($1,$2,'GitHub',1,1)`, f.vault, f.owner)
	t.Cleanup(func() {
		resourceSQL(t, f.s, `DELETE FROM vaults WHERE vault_id=$1`, f.vault)
		resourceSQL(t, f.s, `DELETE FROM oauth_provider_apps WHERE provider='github'`)
	})
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
		if c.GetHeader("X-Test-Admin") == "true" {
			c.Set(ctxRoles, []string{"admin"})
		}
	})
	f.s.registerOAuth(f.router)
	f.base = "/api/vaults/" + f.vault + "/oauth-connections"
	return f
}

var githubAdmin = map[string]string{"X-Test-Admin": "true"}

const githubAppBody = `{"enabled":true,"clientId":"managed-client","clientSecret":"client-secret","scope":"read:user","revision":0}`

func (f *oauthFixture) configureGitHub() {
	f.expect(f.call("PUT", "/api/admin/integrations/github", githubAppBody, nil, githubAdmin), 200)
	out := f.call("POST", f.base, `{"provider":"github","serverName":"github","endpoint":"https://api.githubcopilot.com/mcp/","tokenEndpoint":"https://untrusted.example/token","clientId":"untrusted"}`, nil, nil)
	f.expect(out, 201)
	var c struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(out.Body.Bytes(), &c)
	f.id = c.ID
	f.base += "/" + f.id
}

func (f *oauthFixture) startGitHub() testOAuthStart {
	out := f.call("POST", f.base+"/authorize", "", nil, nil)
	f.expect(out, 200)
	var flow testOAuthStart
	_ = json.Unmarshal(out.Body.Bytes(), &flow)
	flow.Cookie = out.Result().Cookies()[0]
	u, _ := url.Parse(flow.AuthorizationURL)
	q := u.Query()
	f.challenge = q.Get("code_challenge")
	if u.Host != "github.com" || q.Get("client_id") != "managed-client" || q.Get("resource") != "" || q.Get("redirect_uri") != "http://localhost:18080/api/oauth/mcp/callback/github" || q.Get("code_challenge_method") != "S256" {
		f.t.Fatal("managed provider must use fixed OAuth parameters")
	}
	if flow.Cookie.Path != oauthCallbackPrefix+"github" || !flow.Cookie.HttpOnly {
		f.t.Fatal("invalid callback cookie")
	}
	return flow
}

func githubCallbackPath(flow testOAuthStart) string {
	u, _ := url.Parse(flow.AuthorizationURL)
	return oauthCallbackPrefix + "github?" + url.Values{"state": {u.Query().Get("state")}, "code": {"authorized-code"}}.Encode()
}

func TestGitHubManagedOAuthLifecycle(t *testing.T) {
	f := newGitHubFixture(t)
	f.configureGitHub()
	f.s.oauthHTTPClient = &http.Client{Transport: githubTestTransport(func(r *http.Request) (*http.Response, error) {
		switch r.URL.String() {
		case "https://github.com/login/oauth/access_token":
			f.calls.Add(1)
			_ = r.ParseForm()
			if r.Header.Get("Accept") != "application/json" || r.Form.Get("client_id") != "managed-client" || r.Form.Get("client_secret") != "client-secret" || r.Form.Get("redirect_uri") != "http://localhost:18080/api/oauth/mcp/callback/github" {
				t.Error("invalid token exchange")
			}
			hash := sha256.Sum256([]byte(r.Form.Get("code_verifier")))
			if base64.RawURLEncoding.EncodeToString(hash[:]) != f.challenge {
				t.Error("PKCE mismatch")
			}
			return githubResponse(200, `{"access_token":"access-secret","token_type":"bearer"}`), nil
		case "https://api.github.com/user":
			if r.Header.Get("Authorization") != "Bearer access-secret" {
				t.Error("missing account token")
			}
			resp := githubResponse(200, `{"login":"octocat","id":42}`)
			resp.Header.Set("X-OAuth-Scopes", "read:user")
			return resp, nil
		case githubMCPURL:
			if r.Header.Get("Authorization") != "Bearer access-secret" {
				t.Error("missing MCP token")
			}
			var msg struct {
				Method string `json:"method"`
				ID     int    `json:"id"`
			}
			_ = json.NewDecoder(r.Body).Decode(&msg)
			switch msg.Method {
			case "initialize":
				return githubResponse(200, `{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26"}}`), nil
			case "notifications/initialized":
				return githubResponse(202, ""), nil
			case "tools/list":
				return githubResponse(200, `{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_me"},{"name":"search_repositories"}]}}`), nil
			default:
				t.Fatalf("probe must never call tools: %s", msg.Method)
			}
		default:
			t.Fatalf("unexpected credential destination: %s", r.URL.Host)
		}
		return nil, fmt.Errorf("unexpected request")
	})}
	flow := f.startGitHub()
	f.expect(f.call("GET", githubCallbackPath(flow), "", nil, nil), 400)
	f.expect(f.call("GET", githubCallbackPath(flow), "", flow.Cookie, nil), 200)
	f.expect(f.call("GET", githubCallbackPath(flow), "", flow.Cookie, nil), 400)
	if f.calls.Load() != 1 {
		t.Fatal("repeated callback exchanged token")
	}
	complete := f.base + "/flows/" + flow.FlowID + "/complete"
	f.expect(f.call("POST", complete, "", nil, map[string]string{"X-Test-User": "bob"}), 404)
	f.expect(f.call("POST", complete, "", nil, nil), 200)
	f.expect(f.call("POST", complete, "", nil, nil), 200)
	f.expect(f.call("POST", f.base+"/verify", "", nil, map[string]string{"X-Test-Owner": "other"}), 404)
	out := f.call("POST", f.base+"/verify", "", nil, nil)
	f.expect(out, 200)
	var status githubAccountStatus
	_ = json.Unmarshal(out.Body.Bytes(), &status)
	if status.Status != "ready" || status.Login != "octocat" || status.ToolCount != 2 {
		t.Fatalf("unexpected verification: %+v", status)
	}
	out = f.call("GET", "/api/vaults/"+f.vault+"/oauth-connections", "", nil, nil)
	f.expect(out, 200)
	if !strings.Contains(out.Body.String(), "octocat") {
		t.Fatal("account metadata not saved")
	}
	f.expect(f.call("POST", f.base+"/disconnect", "", nil, nil), 200)
	f.expect(f.call("POST", f.base+"/verify", "", nil, nil), 409)
	out = f.call("GET", "/api/vaults/"+f.vault+"/oauth-connections", "", nil, nil)
	if strings.Contains(out.Body.String(), "octocat") {
		t.Fatal("disconnected account metadata remained")
	}
}

func TestGitHubAppPermissionsAndConfigurationChanges(t *testing.T) {
	f := newGitHubFixture(t)
	f.expect(f.call("PUT", "/api/admin/integrations/github", githubAppBody, nil, nil), 403)
	f.expect(f.call("GET", "/api/admin/integrations/github", "", nil, nil), 403)
	out := f.call("GET", "/api/admin/integrations/github", "", nil, githubAdmin)
	f.expect(out, 200)
	if !strings.Contains(out.Body.String(), "/api/oauth/mcp/callback/github") {
		t.Fatal("callback must be available before registering app")
	}
	f.expect(f.call("POST", f.base, `{"provider":"github","serverName":"github","endpoint":"https://api.githubcopilot.com/mcp/"}`, nil, nil), 409)
	f.configureGitHub()
	f.expect(f.call("POST", "/api/vaults/"+f.vault+"/oauth-connections", `{"provider":"github","serverName":"github","endpoint":"https://attacker.example/mcp"}`, nil, nil), 400)
	f.expect(f.call("PATCH", f.base, `{"clientId":"changed"}`, nil, nil), 400)
	f.expect(f.call("PUT", "/api/admin/integrations/github", githubAppBody, nil, githubAdmin), 409)
	f.expect(f.call("PUT", "/api/admin/integrations/github", `{"enabled":true,"clientId":"changed","revision":1}`, nil, githubAdmin), 400)
	flow := f.startGitHub()
	f.expect(f.call("PUT", "/api/admin/integrations/github", `{"enabled":true,"clientId":"managed-client","scope":"read:user","revision":1}`, nil, githubAdmin), 200)
	f.expect(f.call("GET", githubCallbackPath(flow), "", flow.Cookie, nil), 400)
	f.expect(f.call("PUT", "/api/admin/integrations/github", `{"enabled":false,"clientId":"managed-client","revision":2}`, nil, githubAdmin), 200)
	f.expect(f.call("POST", f.base+"/authorize", "", nil, nil), 409)
}

func TestGitHubVerificationFailureAndSSEResponses(t *testing.T) {
	for _, code := range []int{401, 403, 429, 500} {
		t.Run(fmt.Sprint(code), func(t *testing.T) {
			client := &http.Client{Transport: githubTestTransport(func(r *http.Request) (*http.Response, error) {
				return githubResponse(code, `{"error":"access-secret"}`), nil
			})}
			status := inspectGitHubAccount(context.Background(), client, "access-secret", githubMCPURL)
			if status.Status == "ready" || status.ErrorCode != githubHTTPError(code) || strings.Contains(mustJSON(status), "access-secret") {
				t.Fatalf("bad failure status: %+v", status)
			}
		})
	}
	for _, body := range []string{
		"event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n",
		"data: {\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\ndata: \"result\":{\"tools\":[]}}\n\n",
	} {
		resp := githubResponse(200, body)
		resp.Header.Set("Content-Type", "text/event-stream")
		if _, err := readMCPProbeResponse(resp, 2); err != nil {
			t.Fatal(err)
		}
	}
	resp := githubResponse(200, `{"id":2,"error":{"message":"access-secret"}}`)
	if _, err := readMCPProbeResponse(resp, 2); err == nil || strings.Contains(err.Error(), "access-secret") {
		t.Fatal("provider error not sanitized")
	}
}

func TestGitHubCallbackRemainsPublicButAdminAPIRequiresLogin(t *testing.T) {
	s := &Server{}
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(s.jwtMiddleware())
	r.GET(oauthCallbackPrefix+":connectionId", func(c *gin.Context) { c.Status(204) })
	r.GET("/api/admin/integrations/github", func(c *gin.Context) { c.Status(204) })
	for path, expected := range map[string]int{oauthCallbackPrefix + "github": 204, "/api/admin/integrations/github": 401} {
		out := httptest.NewRecorder()
		r.ServeHTTP(out, httptest.NewRequest("GET", path, nil))
		if out.Code != expected {
			t.Fatalf("%s: %d", path, out.Code)
		}
	}
}
