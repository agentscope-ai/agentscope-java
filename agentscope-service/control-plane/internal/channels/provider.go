// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Package channels implements provider transports without product authorization
// or process-local message deduplication. The host acknowledges intake only after
// committing it to shared storage.
package channels

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Type       string         `json:"type"`
	Properties map[string]any `json:"properties"`
}

type Message struct {
	ChannelID    string `json:"channelId"`
	ProviderType string `json:"providerType,omitempty"`
	AccountID    string `json:"accountId"`
	SenderID     string `json:"senderId"`
	PeerKind     string `json:"peerKind"`
	PeerID       string `json:"peerId"`
	ThreadID     string `json:"threadId"`
	MessageID    string `json:"messageId"`
	ReplyToID    string `json:"replyToId"`
	Text         string `json:"text"`
}

type Address struct{ AccountID, PeerKind, PeerID, ThreadID string }
type Accept func(context.Context, Message) error

type Provider struct {
	ID      string
	Config  Config
	client  *http.Client
	mu      sync.Mutex
	token   string
	expires time.Time
	botID   string
}

func New(id string, cfg Config, client *http.Client) (*Provider, error) {
	required := map[string][]string{
		"dingtalk": {"appKey", "appSecret", "robotCode"}, "feishu": {"appId", "appSecret", "verificationToken"},
		"wecom": {"corpId", "agentId", "secret", "token", "encodingAesKey"}, "github": {"token", "webhookSecret"}, "gitlab": {"token", "webhookToken"},
	}
	fields, ok := required[cfg.Type]
	if !ok {
		return nil, fmt.Errorf("unsupported channel type")
	}
	for _, key := range fields {
		if prop(cfg, key) == "" {
			return nil, fmt.Errorf("channel property %s is required", key)
		}
	}
	if cfg.Type == "dingtalk" {
		mode := prop(cfg, "mode")
		if mode != "" && mode != "stream" && mode != "http" {
			return nil, fmt.Errorf("invalid DingTalk mode")
		}
	}
	keyName := ""
	if cfg.Type == "wecom" {
		keyName = "encodingAesKey"
		id, err := strconv.Atoi(prop(cfg, "agentId"))
		if err != nil || id < 1 {
			return nil, fmt.Errorf("invalid WeCom agentId")
		}
	}
	if cfg.Type == "dingtalk" && prop(cfg, "aesKey") != "" {
		keyName = "aesKey"
	}
	if keyName != "" {
		raw := prop(cfg, keyName)
		key, err := base64.RawStdEncoding.DecodeString(raw)
		if len(raw) != 43 || err != nil || len(key) != 32 {
			return nil, fmt.Errorf("invalid channel encryption key")
		}
	}

	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	return &Provider{ID: id, Config: cfg, client: client}, nil
}
func prop(c Config, key string) string {
	v := c.Properties[key]
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}
func (p *Provider) Property(key string) string { return prop(p.Config, key) }
func (p *Provider) base() string {
	b := p.Property("apiBase")
	if b == "" {
		b = map[string]string{"dingtalk": "https://api.dingtalk.com", "feishu": "https://open.feishu.cn", "wecom": "https://qyapi.weixin.qq.com", "github": "https://api.github.com", "gitlab": "https://gitlab.com/api/v4"}[p.Config.Type]
	}
	b = strings.TrimRight(b, "/")
	if p.Config.Type == "gitlab" && !strings.Contains(b, "/api/v") {
		b += "/api/v4"
	}
	return b
}
func (p *Provider) request(ctx context.Context, method, endpoint string, headers map[string]string, body any) (map[string]any, error) {
	var data []byte
	var err error
	if body != nil {
		data, err = json.Marshal(body)
		if err != nil {
			return nil, err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("invalid provider endpoint")
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("provider transport unavailable")
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("provider HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20+1))
	if err != nil || len(raw) > 1<<20 {
		return nil, fmt.Errorf("invalid provider response")
	}
	var out map[string]any
	if json.Unmarshal(raw, &out) != nil {
		return nil, fmt.Errorf("invalid provider JSON")
	}
	return out, nil
}
func (p *Provider) accessToken(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.token != "" && time.Now().Before(p.expires) {
		return p.token, nil
	}
	var out map[string]any
	var err error
	var token string
	switch p.Config.Type {
	case "feishu":
		out, err = p.request(ctx, "POST", p.base()+"/open-apis/auth/v3/tenant_access_token/internal", nil, map[string]any{"app_id": p.Property("appId"), "app_secret": p.Property("appSecret")})
		if err == nil {
			err = checkCode(out, "code")
			token = value(out, "tenant_access_token")
		}
	case "wecom":
		q := url.Values{"corpid": {p.Property("corpId")}, "corpsecret": {p.Property("secret")}}
		out, err = p.request(ctx, "GET", p.base()+"/cgi-bin/gettoken?"+q.Encode(), nil, nil)
		if err == nil {
			err = checkCode(out, "errcode")
			token = value(out, "access_token")
		}
	case "dingtalk":
		out, err = p.request(ctx, "POST", p.base()+"/v1.0/oauth2/accessToken", nil, map[string]any{"appKey": p.Property("appKey"), "appSecret": p.Property("appSecret")})
		token = value(out, "accessToken")
	}
	if err != nil {
		return "", err
	}
	if token == "" {
		return "", fmt.Errorf("provider token missing")
	}
	seconds := number(out, "expire")
	if seconds == 0 {
		seconds = number(out, "expires_in")
	}
	if seconds == 0 {
		seconds = number(out, "expireIn")
	}
	if seconds <= 60 {
		seconds = 120
	}
	p.token = token
	p.expires = time.Now().Add(time.Duration(seconds-60) * time.Second)
	return token, nil
}
func checkCode(m map[string]any, key string) error {
	v, ok := m[key].(float64)
	if !ok || v != 0 {
		return fmt.Errorf("provider rejected request (%s)", key)
	}
	return nil
}
func value(m map[string]any, keys ...string) string {
	for i, k := range keys {
		if i == len(keys)-1 {
			v := m[k]
			if v == nil {
				return ""
			}
			return fmt.Sprint(v)
		}
		m, _ = m[k].(map[string]any)
	}
	return ""
}
func object(m map[string]any, key string) map[string]any { v, _ := m[key].(map[string]any); return v }
func number(m map[string]any, key string) int            { v, _ := m[key].(float64); return int(v) }
func encoded(v any) string                               { b, _ := json.Marshal(v); return string(b) }

func (p *Provider) Send(ctx context.Context, a Address, text, deliveryID string) (string, error) {
	if strings.TrimSpace(text) == "" || a.PeerID == "" {
		return "", fmt.Errorf("message and destination required")
	}
	typ := p.Config.Type
	if typ == "github" || typ == "gitlab" {
		return p.sendCode(ctx, a, text)
	}
	token, err := p.accessToken(ctx)
	if err != nil {
		return "", err
	}
	body := map[string]any{}
	headers := map[string]string{}
	var path, codeKey string
	switch typ {
	case "feishu":
		codeKey = "code"
		headers["Authorization"] = "Bearer " + token
		body = map[string]any{"msg_type": "text", "content": encoded(map[string]string{"text": text}), "uuid": deliveryID}
		if a.ThreadID != "" {
			path = "/open-apis/im/v1/messages/" + url.PathEscape(a.ThreadID) + "/reply"
			body["reply_in_thread"] = true
		} else {
			path = "/open-apis/im/v1/messages?receive_id_type=chat_id"
			body["receive_id"] = a.PeerID
		}
	case "wecom":
		codeKey = "errcode"
		kind := "text"
		if markdown(text) {
			kind = "markdown"
		}
		body = map[string]any{"msgtype": kind, kind: map[string]string{"content": text}}
		if a.PeerKind == "GROUP" {
			path = "/cgi-bin/appchat/send"
			body["chatid"] = a.PeerID
		} else if a.PeerKind == "DIRECT" {
			path = "/cgi-bin/message/send"
			body["touser"] = a.PeerID
			id, _ := strconv.Atoi(p.Property("agentId"))
			body["agentid"] = id
		} else {
			return "", fmt.Errorf("unsupported WeCom address")
		}
		path += "?access_token=" + url.QueryEscape(token)
	case "dingtalk":
		headers["x-acs-dingtalk-access-token"] = token
		param := map[string]string{"content": text}
		key := "sampleText"
		if markdown(text) {
			key = "sampleMarkdown"
			param = map[string]string{"title": "Agent", "text": text}
		}
		body = map[string]any{"robotCode": p.Property("robotCode"), "msgKey": key, "msgParam": encoded(param)}
		if a.PeerKind == "GROUP" {
			path = "/v1.0/robot/groupMessages/send"
			body["openConversationId"] = a.PeerID
		} else if a.PeerKind == "DIRECT" {
			path = "/v1.0/robot/oToMessages/batchSend"
			body["userIds"] = []string{a.PeerID}
		} else {
			return "", fmt.Errorf("unsupported DingTalk address")
		}
	}
	out, err := p.request(ctx, "POST", p.base()+path, headers, body)
	if err == nil && codeKey != "" {
		err = checkCode(out, codeKey)
	}
	if err != nil {
		p.mu.Lock()
		p.token = ""
		p.mu.Unlock()
		return "", err
	}
	switch typ {
	case "feishu":
		id := value(out, "data", "message_id")
		if id == "" {
			return "", fmt.Errorf("provider receipt missing")
		}
		return id, nil
	case "wecom":
		return value(out, "msgid"), nil
	default:
		return value(out, "processQueryKey"), nil
	}
}
func markdown(text string) bool {
	return strings.ContainsAny(text, "\n[") || strings.Contains(text, "**") || strings.Contains(text, "```")
}
func (p *Provider) sendCode(ctx context.Context, a Address, text string) (string, error) {
	pos := strings.LastIndex(a.PeerID, "#")
	if pos < 1 {
		return "", fmt.Errorf("invalid repository thread")
	}
	repo, tail := a.PeerID[:pos], a.PeerID[pos+1:]
	headers := map[string]string{}
	var path string
	if p.Config.Type == "github" {
		parts := strings.Split(repo, "/")
		if len(parts) != 2 {
			return "", fmt.Errorf("invalid repository")
		}
		n, err := strconv.Atoi(tail)
		if err != nil || n < 1 {
			return "", fmt.Errorf("invalid issue")
		}
		path = "/repos/" + url.PathEscape(parts[0]) + "/" + url.PathEscape(parts[1]) + "/issues/" + tail + "/comments"
		headers["Authorization"] = "Bearer " + p.Property("token")
		headers["Accept"] = "application/vnd.github+json"
	} else {
		parts := strings.Split(tail, ":")
		if len(parts) != 2 {
			return "", fmt.Errorf("invalid GitLab thread")
		}
		n, err := strconv.Atoi(parts[0])
		if err != nil || n < 1 {
			return "", fmt.Errorf("invalid issue")
		}
		kind := "issues"
		if parts[1] == "MergeRequest" {
			kind = "merge_requests"
		} else if parts[1] != "Issue" {
			return "", fmt.Errorf("invalid note type")
		}
		path = "/projects/" + url.PathEscape(repo) + "/" + kind + "/" + parts[0] + "/notes"
		headers["PRIVATE-TOKEN"] = p.Property("token")
	}
	out, err := p.request(ctx, "POST", p.base()+path, headers, map[string]string{"body": text})
	if err != nil {
		return "", err
	}
	id := value(out, "id")
	if id == "" {
		return "", fmt.Errorf("provider receipt missing")
	}
	return id, nil
}
func (p *Provider) self(ctx context.Context) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.botID != "" {
		return p.botID, nil
	}
	if id := p.Property("botUserLogin"); id != "" {
		p.botID = id
		return id, nil
	}
	h := map[string]string{"Authorization": "Bearer " + p.Property("token")}
	if p.Config.Type == "gitlab" {
		h = map[string]string{"PRIVATE-TOKEN": p.Property("token")}
	}
	out, err := p.request(ctx, "GET", p.base()+"/user", h, nil)
	if err != nil {
		return "", err
	}
	key := "login"
	if p.Config.Type == "gitlab" {
		key = "username"
	}
	p.botID = value(out, key)
	if p.botID == "" {
		return "", fmt.Errorf("provider identity missing")
	}
	return p.botID, nil
}
