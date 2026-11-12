// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package channels

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/gorilla/websocket"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func testProvider(t *testing.T, typ string, props map[string]any) *Provider {
	t.Helper()
	p, err := New("channel", Config{Type: typ, Properties: props}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return p
}
func feishu(t *testing.T) *Provider {
	return testProvider(t, "feishu", map[string]any{"appId": "app", "appSecret": "secret", "verificationToken": "verified"})
}
func feishuMessage() string {
	return `{"header":{"token":"verified","tenant_key":"org"},"event":{"sender":{"sender_type":"user","sender_id":{"open_id":"human"}},"message":{"message_id":"provider-message","chat_id":"room","chat_type":"group","root_id":"root","parent_id":"parent","message_type":"text","mentions":[{"key":"@bot"}],"content":"{\"text\":\"@bot /new task\"}"}}}`
}
func TestFeishuAuthenticatedIntakeAndFailureRetry(t *testing.T) {
	p := feishu(t)
	count := 0
	accept := func(_ context.Context, m Message) error {
		count++
		if m.Text != "/new task" || m.ThreadID != "root" || m.MessageID != "provider-message" || m.AccountID != "org" || m.PeerKind != "GROUP" {
			t.Fatalf("bad normalized event %+v", m)
		}
		return fmt.Errorf("database unavailable")
	}
	w := httptest.NewRecorder()
	p.Callback(w, httptest.NewRequest("POST", "/", strings.NewReader(feishuMessage())), accept)
	if w.Code != 503 || count != 1 {
		t.Fatalf("ACK before commit: %d %d", w.Code, count)
	}
	w = httptest.NewRecorder()
	p.Callback(w, httptest.NewRequest("POST", "/", strings.NewReader(strings.Replace(feishuMessage(), "verified", "forged", 1))), accept)
	if w.Code != 401 || count != 1 {
		t.Fatalf("forged identity: %d", w.Code)
	}
	w = httptest.NewRecorder()
	p.Callback(w, httptest.NewRequest("POST", "/", strings.NewReader(`{"type":"url_verification","token":"verified","challenge":"challenge"}`)), accept)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "challenge") {
		t.Fatal(w.Body.String())
	}
}
func encryptCBC(key, iv, plain []byte, blockSize int) []byte {
	pad := blockSize - len(plain)%blockSize
	plain = append(append([]byte{}, plain...), bytes.Repeat([]byte{byte(pad)}, pad)...)
	block, _ := aes.NewCipher(key)
	out := make([]byte, len(plain))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(out, plain)
	return out
}
func TestEncryptedFeishuSignature(t *testing.T) {
	p := feishu(t)
	p.Config.Properties["encryptKey"] = "encryption-key"
	key := sha256.Sum256([]byte("encryption-key"))
	iv := bytes.Repeat([]byte{3}, 16)
	body := encoded(map[string]string{"encrypt": base64.StdEncoding.EncodeToString(append(iv, encryptCBC(key[:], iv, []byte(feishuMessage()), 16)...))})
	req := httptest.NewRequest("POST", "/", strings.NewReader(body))
	req.Header.Set("X-Lark-Request-Timestamp", "1")
	req.Header.Set("X-Lark-Request-Nonce", "nonce")
	sum := sha256.Sum256([]byte("1nonceencryption-key" + body))
	req.Header.Set("X-Lark-Signature", hex.EncodeToString(sum[:]))
	count := 0
	w := httptest.NewRecorder()
	p.Callback(w, req, func(context.Context, Message) error { count++; return nil })
	if w.Code != 200 || count != 1 {
		t.Fatalf("encrypted callback %d", w.Code)
	}
	req = httptest.NewRequest("POST", "/", strings.NewReader(body))
	req.Header.Set("X-Lark-Signature", "forged")
	w = httptest.NewRecorder()
	p.Callback(w, req, func(context.Context, Message) error { t.Fatal("forged encrypted event"); return nil })
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
}
func TestWeComHandshakeAndXML(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	p := testProvider(t, "wecom", map[string]any{"corpId": "corp", "agentId": 1, "secret": "s", "token": "token", "encodingAesKey": base64.RawStdEncoding.EncodeToString(key)})
	text := `<xml><FromUserName><![CDATA[user]]></FromUserName><MsgType>text</MsgType><Content><![CDATA[hello]]></Content><MsgId>42</MsgId></xml>`
	frame := append(bytes.Repeat([]byte{1}, 16), 0, 0, 0, 0)
	binary.BigEndian.PutUint32(frame[16:20], uint32(len(text)))
	frame = append(frame, []byte(text+"corp")...)
	encrypted := base64.StdEncoding.EncodeToString(encryptCBC(key, key[:16], frame, 32))
	parts := []string{"token", "1", "nonce", encrypted}
	sort.Strings(parts)
	sum := sha1.Sum([]byte(strings.Join(parts, "")))
	path := "/?msg_signature=" + hex.EncodeToString(sum[:]) + "&timestamp=1&nonce=nonce"
	w := httptest.NewRecorder()
	count := 0
	p.Callback(w, httptest.NewRequest("POST", path, strings.NewReader("<xml><Encrypt>"+encrypted+"</Encrypt></xml>")), func(_ context.Context, m Message) error {
		count++
		if m.SenderID != "user" || m.Text != "hello" || m.MessageID != "42" {
			t.Fatal(m)
		}
		return nil
	})
	if w.Code != 200 || count != 1 {
		t.Fatalf("WeCom %d %s", w.Code, w.Body.String())
	}
	w = httptest.NewRecorder()
	p.Callback(w, httptest.NewRequest("GET", path+"&echostr="+url.QueryEscape(encrypted), nil), func(context.Context, Message) error { t.Fatal("handshake dispatched"); return nil })
	if w.Body.String() != text {
		t.Fatal(w.Body.String())
	}
	if _, err := decodeXML([]byte(`<!DOCTYPE x [<!ENTITY y SYSTEM "file:///etc/passwd">]><xml><Content>&y;</Content></xml>`)); err == nil {
		t.Fatal("XML directive accepted")
	}
}
func TestFeishuDeliveryReceiptAndIdempotency(t *testing.T) {
	var sent []map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "tenant_access_token") {
			io.WriteString(w, `{"code":0,"tenant_access_token":"token","expire":7200}`)
			return
		}
		if r.Header.Get("Authorization") != "Bearer token" {
			t.Error("token missing")
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		sent = append(sent, body)
		if r.URL.Path != "/open-apis/im/v1/messages/root/reply" {
			t.Error(r.URL)
		}
		io.WriteString(w, `{"code":0,"data":{"message_id":"receipt"}}`)
	}))
	defer server.Close()
	p := feishu(t)
	p.Config.Properties["apiBase"] = server.URL
	for range 2 {
		receipt, err := p.Send(t.Context(), Address{PeerID: "room", ThreadID: "root"}, "result", "stable")
		if err != nil || receipt != "receipt" {
			t.Fatalf("%s %v", receipt, err)
		}
	}
	if len(sent) != 2 || sent[0]["uuid"] != "stable" || sent[1]["uuid"] != "stable" || sent[0]["reply_in_thread"] != true {
		t.Fatal(sent)
	}
}
func TestProviderRejectsMissingReceiptAndErrorCode(t *testing.T) {
	for _, body := range []string{`{"code":0,"data":{}}`, `{"code":99}`, `{}`, `not-json`} {
		t.Run(body, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.Contains(r.URL.Path, "tenant_access_token") {
					io.WriteString(w, `{"code":0,"tenant_access_token":"token"}`)
				} else {
					io.WriteString(w, body)
				}
			}))
			defer server.Close()
			p := feishu(t)
			p.Config.Properties["apiBase"] = server.URL
			if _, err := p.Send(t.Context(), Address{PeerID: "room"}, "result", "stable"); err == nil {
				t.Fatal("invalid receipt accepted")
			}
		})
	}
}
func TestGitCallbacksAuthenticateAndIgnoreBots(t *testing.T) {
	for _, typ := range []string{"github", "gitlab"} {
		t.Run(typ, func(t *testing.T) {
			p := testProvider(t, typ, map[string]any{"token": "token", "webhookSecret": "secret", "webhookToken": "secret", "botUserLogin": "bot"})
			body := `{"action":"created","repository":{"full_name":"org/repo","owner":{"login":"org"}},"issue":{"number":3,"iid":3},"comment":{"id":9,"body":"hello","user":{"login":"user"}},"user":{"username":"user"},"project":{"path_with_namespace":"org/repo","namespace":"org"},"object_attributes":{"id":9,"noteable_type":"Issue","note":"hello"}}`
			req := httptest.NewRequest("POST", "/", strings.NewReader(body))
			if typ == "github" {
				mac := hmac.New(sha256.New, []byte("secret"))
				mac.Write([]byte(body))
				req.Header.Set("X-Hub-Signature-256", "sha256="+hex.EncodeToString(mac.Sum(nil)))
				req.Header.Set("X-GitHub-Event", "issue_comment")
			} else {
				req.Header.Set("X-Gitlab-Token", "secret")
				req.Header.Set("X-Gitlab-Event", "Note Hook")
			}
			w := httptest.NewRecorder()
			count := 0
			p.Callback(w, req, func(_ context.Context, m Message) error {
				count++
				if m.MessageID != "9" || m.PeerKind != "THREAD" {
					t.Fatal(m)
				}
				return nil
			})
			if w.Code != 200 || count != 1 {
				t.Fatalf("%d %s", w.Code, w.Body.String())
			}
		})
	}
}
func TestDingTalkStreamACKAfterDurableIntake(t *testing.T) {
	var accepted atomic.Bool
	acks := make(chan int, 2)
	upgrader := websocket.Upgrader{}
	var base string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/register" {
			io.WriteString(w, encoded(map[string]string{"endpoint": "ws" + strings.TrimPrefix(base, "http") + "/stream", "ticket": "ticket"}))
			return
		}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		conn.WriteJSON(map[string]any{"type": "CALLBACK", "headers": map[string]string{"messageId": "frame"}, "data": `{"msgtype":"text","text":{"content":"hello"},"senderStaffId":"user","msgId":"message"}`})
		var ack map[string]any
		if conn.ReadJSON(&ack) == nil {
			if !accepted.Load() {
				t.Error("ACK before durable acceptance")
			}
			acks <- number(ack, "code")
		}
	}))
	defer server.Close()
	base = server.URL
	p := testProvider(t, "dingtalk", map[string]any{"appKey": "app", "appSecret": "secret", "robotCode": "robot", "streamRegisterUrl": base + "/register"})
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	go p.streamOnce(ctx, func(context.Context, Message) error { accepted.Store(true); return fmt.Errorf("not committed") })
	select {
	case code := <-acks:
		if code != 503 {
			t.Fatal(code)
		}
	case <-ctx.Done():
		t.Fatal("stream failed to ACK")
	}
}

func TestOtherProviderDeliveryProtocols(t *testing.T) {
	for _, tc := range []struct {
		typ, kind, peer, path string
		properties            map[string]any
	}{
		{"dingtalk", "DIRECT", "user", "/v1.0/robot/oToMessages/batchSend", map[string]any{"appKey": "app", "appSecret": "secret", "robotCode": "robot"}},
		{"dingtalk", "GROUP", "group", "/v1.0/robot/groupMessages/send", map[string]any{"appKey": "app", "appSecret": "secret", "robotCode": "robot"}},
		{"wecom", "DIRECT", "user", "/cgi-bin/message/send", map[string]any{"corpId": "corp", "agentId": 1, "secret": "secret", "token": "verify", "encodingAesKey": base64.RawStdEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))}},
		{"wecom", "GROUP", "group", "/cgi-bin/appchat/send", map[string]any{"corpId": "corp", "agentId": 1, "secret": "secret", "token": "verify", "encodingAesKey": base64.RawStdEncoding.EncodeToString(bytes.Repeat([]byte{1}, 32))}},
		{"github", "THREAD", "org/repo#3", "/repos/org/repo/issues/3/comments", map[string]any{"token": "secret", "webhookSecret": "verify"}},
		{"gitlab", "THREAD", "org/repo#3:MergeRequest", "/api/v4/projects/org/repo/merge_requests/3/notes", map[string]any{"token": "secret", "webhookToken": "verify"}},
	} {
		t.Run(tc.typ+tc.kind, func(t *testing.T) {
			sent := false
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/v1.0/oauth2/accessToken":
					io.WriteString(w, `{"accessToken":"token","expireIn":7200}`)
					return
				case "/cgi-bin/gettoken":
					if r.URL.Query().Get("corpsecret") != "secret" {
						t.Error("missing credential")
					}
					io.WriteString(w, `{"errcode":0,"access_token":"token","expires_in":7200}`)
					return
				}
				if r.Method != "POST" || r.URL.Path != tc.path {
					t.Errorf("unexpected delivery %s %s", r.Method, r.URL)
				}
				var body map[string]any
				json.NewDecoder(r.Body).Decode(&body)
				sent = true
				switch tc.typ {
				case "dingtalk":
					if r.Header.Get("x-acs-dingtalk-access-token") != "token" || body["msgKey"] != "sampleMarkdown" || body["robotCode"] != "robot" {
						t.Error(body)
					}
					io.WriteString(w, `{"processQueryKey":"receipt"}`)
				case "wecom":
					if r.URL.Query().Get("access_token") != "token" || body["msgtype"] != "markdown" {
						t.Error(body)
					}
					io.WriteString(w, `{"errcode":0,"msgid":"receipt"}`)
				case "github":
					if r.Header.Get("Authorization") != "Bearer secret" || body["body"] != "**answer**" {
						t.Error(body)
					}
					io.WriteString(w, `{"id":42}`)
				case "gitlab":
					if r.Header.Get("PRIVATE-TOKEN") != "secret" || !strings.Contains(r.URL.EscapedPath(), "org%2Frepo") || body["body"] != "**answer**" {
						t.Error(body)
					}
					io.WriteString(w, `{"id":42}`)
				}
			}))
			defer server.Close()
			tc.properties["apiBase"] = server.URL
			provider := testProvider(t, tc.typ, tc.properties)
			receipt, err := provider.Send(t.Context(), Address{PeerKind: tc.kind, PeerID: tc.peer}, "**answer**", "stable")
			if err != nil || receipt == "" || !sent {
				t.Fatalf("send receipt=%s err=%v", receipt, err)
			}
		})
	}
}
func TestDingTalkHTTPAuthenticationAndEncryptedBody(t *testing.T) {
	p := testProvider(t, "dingtalk", map[string]any{"appKey": "app", "appSecret": "secret", "robotCode": "robot", "mode": "http"})
	stamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
	mac := hmac.New(sha256.New, []byte("secret"))
	mac.Write([]byte(stamp + "\nsecret"))
	signature := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	plain := `{"msgtype":"text","text":{"content":"hello"},"senderStaffId":"user","msgId":"message"}`
	count := 0
	call := func(body, sign string) int {
		req := httptest.NewRequest("POST", "/", strings.NewReader(body))
		req.Header.Set("timestamp", stamp)
		req.Header.Set("sign", sign)
		w := httptest.NewRecorder()
		p.Callback(w, req, func(_ context.Context, m Message) error {
			count++
			if m.MessageID != "message" || m.Text != "hello" {
				t.Error(m)
			}
			return nil
		})
		return w.Code
	}
	if got := call(plain, signature); got != 200 || count != 1 {
		t.Fatal(got, count)
	}
	if got := call(plain, "forged"); got != 401 || count != 1 {
		t.Fatal(got, count)
	}
	key := bytes.Repeat([]byte{7}, 32)
	p.Config.Properties["aesKey"] = base64.RawStdEncoding.EncodeToString(key)
	if got := call(plain, signature); got != 400 {
		t.Fatal("plaintext accepted for encrypted channel", got)
	}
	frame := append(bytes.Repeat([]byte{1}, 16), 0, 0, 0, 0)
	binary.BigEndian.PutUint32(frame[16:20], uint32(len(plain)))
	frame = append(frame, []byte(plain+"app")...)
	encrypted := encoded(map[string]string{"encrypt": base64.StdEncoding.EncodeToString(encryptCBC(key, key[:16], frame, 32))})
	if got := call(encrypted, signature); got != 200 || count != 2 {
		t.Fatal(got, count)
	}
}
