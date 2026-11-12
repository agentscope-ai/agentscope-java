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
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

func equal(a, b string) bool {
	return a != "" && b != "" && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
func cbcDecrypt(key, iv, data []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil || len(iv) != 16 || len(data) == 0 || len(data)%16 != 0 {
		return nil, fmt.Errorf("invalid encrypted callback")
	}
	plain := make([]byte, len(data))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, data)
	pad := int(plain[len(plain)-1])
	if pad < 1 || pad > 32 || pad > len(plain) {
		return nil, fmt.Errorf("invalid callback padding")
	}
	for _, v := range plain[len(plain)-pad:] {
		if int(v) != pad {
			return nil, fmt.Errorf("invalid callback padding")
		}
	}
	return plain[:len(plain)-pad], nil
}
func decryptFrame(keyText, encoded, receiver string) ([]byte, error) {
	key, err := base64.RawStdEncoding.DecodeString(strings.TrimRight(keyText, "="))
	if err != nil || len(key) != 32 {
		return nil, fmt.Errorf("invalid callback key")
	}
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	plain, err := cbcDecrypt(key, key[:16], data)
	if err != nil || len(plain) < 20 {
		return nil, fmt.Errorf("invalid encrypted frame")
	}
	n := int(binary.BigEndian.Uint32(plain[16:20]))
	if n > len(plain)-20 || !equal(string(plain[20+n:]), receiver) {
		return nil, fmt.Errorf("callback receiver mismatch")
	}
	return plain[20 : 20+n], nil
}
func decodeXML(data []byte) (map[string]string, error) {
	// Go's decoder does not resolve external entities. Reject directives as well.
	d := xml.NewDecoder(strings.NewReader(string(data)))
	out := map[string]string{}
	for {
		tok, err := d.Token()
		if err == io.EOF {
			return out, nil
		}
		if err != nil {
			return nil, err
		}
		switch v := tok.(type) {
		case xml.Directive:
			return nil, fmt.Errorf("XML directives forbidden")
		case xml.StartElement:
			if v.Name.Local == "xml" {
				continue
			}
			var s string
			if err = d.DecodeElement(&s, &v); err != nil {
				return nil, err
			}
			out[v.Name.Local] = s
		}
	}
}

// Callback verifies provider authentication before mapping or persisting an
// identity. A failed durable Accept returns 503 so the provider can retry.
func (p *Provider) Callback(w http.ResponseWriter, r *http.Request, accept Accept) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		http.Error(w, "invalid callback body", 400)
		return
	}
	var payload map[string]any
	var msg *Message
	reply := "{}"
	contentType := "application/json"
	fail := func(code int) { http.Error(w, http.StatusText(code), code) }
	switch p.Config.Type {
	case "feishu":
		if key := p.Property("encryptKey"); key != "" && r.Header.Get("X-Lark-Signature") != "" {
			sum := sha256.Sum256([]byte(r.Header.Get("X-Lark-Request-Timestamp") + r.Header.Get("X-Lark-Request-Nonce") + key + string(raw)))
			if !equal(hex.EncodeToString(sum[:]), r.Header.Get("X-Lark-Signature")) {
				fail(401)
				return
			}
		}
		if json.Unmarshal(raw, &payload) != nil {
			fail(400)
			return
		}
		if encrypted := value(payload, "encrypt"); encrypted != "" {
			key := sha256.Sum256([]byte(p.Property("encryptKey")))
			data, e := base64.StdEncoding.DecodeString(encrypted)
			if e != nil || len(data) < 32 || p.Property("encryptKey") == "" {
				fail(400)
				return
			}
			plain, e := cbcDecrypt(key[:], data[:16], data[16:])
			if e != nil || json.Unmarshal(plain, &payload) != nil {
				fail(400)
				return
			}
		}
		if value(payload, "type") == "url_verification" {
			if !equal(value(payload, "token"), p.Property("verificationToken")) {
				fail(401)
				return
			}
			w.Header().Set("Content-Type", contentType)
			io.WriteString(w, encoded(map[string]string{"challenge": value(payload, "challenge")}))
			return
		}
		if !equal(value(payload, "header", "token"), p.Property("verificationToken")) {
			fail(401)
			return
		}
		msg = p.feishu(payload)
	case "wecom":
		q := r.URL.Query()
		encrypted := q.Get("echostr")
		if r.Method != "GET" {
			fields, e := decodeXML(raw)
			if e != nil {
				fail(400)
				return
			}
			encrypted = fields["Encrypt"]
		}
		parts := []string{p.Property("token"), q.Get("timestamp"), q.Get("nonce"), encrypted}
		sort.Strings(parts)
		sum := sha1.Sum([]byte(strings.Join(parts, "")))
		if encrypted == "" || !equal(hex.EncodeToString(sum[:]), q.Get("msg_signature")) {
			fail(401)
			return
		}
		plain, e := decryptFrame(p.Property("encodingAesKey"), encrypted, p.Property("corpId"))
		if e != nil {
			fail(400)
			return
		}
		if r.Method == "GET" {
			w.Header().Set("Content-Type", "text/plain")
			w.Write(plain)
			return
		}
		fields, e := decodeXML(plain)
		if e != nil {
			fail(400)
			return
		}
		msg = p.wecom(fields)
		reply = ""
		contentType = "text/plain"
	case "dingtalk":
		stamp := r.Header.Get("timestamp")
		ms, e := strconv.ParseInt(stamp, 10, 64)
		if e != nil || time.Since(time.UnixMilli(ms)).Abs() > time.Hour {
			fail(401)
			return
		}
		mac := hmac.New(sha256.New, []byte(p.Property("appSecret")))
		mac.Write([]byte(stamp + "\n" + p.Property("appSecret")))
		if !equal(base64.StdEncoding.EncodeToString(mac.Sum(nil)), r.Header.Get("sign")) {
			fail(401)
			return
		}
		if json.Unmarshal(raw, &payload) != nil {
			fail(400)
			return
		}
		encrypted := value(payload, "encrypt")
		if p.Property("aesKey") != "" && encrypted == "" {
			fail(400)
			return
		}
		if encrypted != "" {
			plain, e := decryptFrame(p.Property("aesKey"), encrypted, p.Property("appKey"))
			if e != nil || json.Unmarshal(plain, &payload) != nil {
				fail(400)
				return
			}
		}
		msg = p.dingtalk(payload)
		reply = ""
		contentType = "text/plain"
	case "github":
		mac := hmac.New(sha256.New, []byte(p.Property("webhookSecret")))
		mac.Write(raw)
		if !equal("sha256="+hex.EncodeToString(mac.Sum(nil)), r.Header.Get("X-Hub-Signature-256")) {
			fail(401)
			return
		}
		if json.Unmarshal(raw, &payload) != nil {
			fail(400)
			return
		}
		kind := r.Header.Get("X-GitHub-Event")
		if kind != "issue_comment" && kind != "pull_request_review_comment" {
			w.WriteHeader(204)
			return
		}
		self, e := p.self(r.Context())
		if e != nil {
			fail(503)
			return
		}
		if value(payload, "comment", "user", "login") == self || strings.EqualFold(value(payload, "comment", "user", "type"), "Bot") {
			break
		}
		msg = p.github(kind, payload)
	case "gitlab":
		if !equal(p.Property("webhookToken"), r.Header.Get("X-Gitlab-Token")) {
			fail(401)
			return
		}
		if r.Header.Get("X-Gitlab-Event") != "Note Hook" {
			w.WriteHeader(204)
			return
		}
		if json.Unmarshal(raw, &payload) != nil {
			fail(400)
			return
		}
		self, e := p.self(r.Context())
		if e != nil {
			fail(503)
			return
		}
		if value(payload, "user", "username") == self {
			break
		}
		msg = p.gitlab(payload)
	default:
		fail(404)
		return
	}
	if msg != nil {
		if msg.MessageID == "" || msg.AccountID == "" || msg.SenderID == "" || msg.PeerID == "" {
			fail(400)
			return
		}
		if err := accept(r.Context(), *msg); err != nil {
			fail(503)
			return
		}
	}
	w.Header().Set("Content-Type", contentType)
	io.WriteString(w, reply)
}
func (p *Provider) feishu(m map[string]any) *Message {
	e := object(m, "event")
	msg := object(e, "message")
	if value(e, "sender", "sender_type") != "user" || value(msg, "chat_id") == "" {
		return nil
	}
	var content map[string]any
	if json.Unmarshal([]byte(value(msg, "content")), &content) != nil {
		return nil
	}
	typ := strings.ToLower(value(msg, "message_type"))
	text := value(content, "text")
	if typ != "text" {
		key := "file_key"
		if typ == "image" {
			key = "image_key"
		}
		if value(content, key) == "" {
			return nil
		}
		text = media(typ, value(content, "file_name"))
		if text == "" {
			return nil
		}
	} else {
		mentions, _ := msg["mentions"].([]any)
		text = strings.TrimSpace(text)
		for _, v := range mentions {
			k := value(asObject(v), "key")
			if k != "" {
				text = strings.TrimSpace(strings.TrimPrefix(text, k))
			}
		}
	}
	if strings.TrimSpace(text) == "" {
		return nil
	}
	kind := "DIRECT"
	if value(msg, "chat_type") == "group" {
		kind = "GROUP"
	}
	return &Message{ChannelID: p.ID, ProviderType: p.Config.Type, AccountID: value(m, "header", "tenant_key"), SenderID: value(e, "sender", "sender_id", "open_id"), PeerKind: kind, PeerID: value(msg, "chat_id"), MessageID: value(msg, "message_id"), ReplyToID: value(msg, "parent_id"), ThreadID: value(msg, "root_id"), Text: text}
}
func asObject(v any) map[string]any { m, _ := v.(map[string]any); return m }
func media(kind, name string) string {
	switch kind {
	case "picture":
		kind = "image"
	case "voice":
		kind = "audio"
	case "media":
		kind = "video"
	}
	switch kind {
	case "image", "audio", "video", "file", "folder":
	default:
		return ""
	}
	if kind != "file" || name == "" {
		return "[" + kind + "]"
	}
	name = strings.Map(func(r rune) rune {
		if r < ' ' || r == ']' || r == '[' {
			return ' '
		}
		return r
	}, name)
	r := []rune(name)
	if len(r) > 120 {
		r = r[:120]
	}
	return "[file: " + strings.TrimSpace(string(r)) + "]"
}
func (p *Provider) dingtalk(m map[string]any) *Message {
	typ := strings.ToLower(value(m, "msgtype"))
	text := strings.TrimSpace(value(m, "text", "content"))
	if typ != "text" {
		filename := value(m, "fileName")
		if filename == "" {
			filename = value(m, "content", "fileName")
		}
		text = media(typ, filename)
		if value(m, "downloadCode") == "" && value(m, "content", "downloadCode") == "" {
			return nil
		}
	}
	if text == "" {
		return nil
	}
	peer := value(m, "senderStaffId")
	if peer == "" {
		peer = value(m, "conversationId")
	}
	sender := peer
	kind := "DIRECT"
	if value(m, "conversationType") == "2" {
		kind = "GROUP"
		peer = value(m, "conversationId")
	}
	return &Message{ChannelID: p.ID, ProviderType: p.Config.Type, AccountID: p.Property("appKey"), SenderID: sender, PeerKind: kind, PeerID: peer, MessageID: value(m, "msgId"), Text: text}
}
func (p *Provider) wecom(m map[string]string) *Message {
	text := m["Content"]
	if m["MsgType"] != "text" {
		if m["MediaId"] == "" {
			return nil
		}
		text = media(m["MsgType"], "")
	}
	if text == "" || m["FromUserName"] == "" {
		return nil
	}
	return &Message{ChannelID: p.ID, ProviderType: p.Config.Type, AccountID: p.Property("corpId"), SenderID: m["FromUserName"], PeerKind: "DIRECT", PeerID: m["FromUserName"], MessageID: m["MsgId"], Text: text}
}
func (p *Provider) github(kind string, m map[string]any) *Message {
	if value(m, "action") != "created" {
		return nil
	}
	field := "issue"
	if kind == "pull_request_review_comment" {
		field = "pull_request"
	}
	n := value(m, field, "number")
	repo := value(m, "repository", "full_name")
	text := value(m, "comment", "body")
	if n == "" || repo == "" || text == "" {
		return nil
	}
	return &Message{ChannelID: p.ID, ProviderType: p.Config.Type, AccountID: value(m, "repository", "owner", "login"), SenderID: value(m, "comment", "user", "login"), PeerKind: "THREAD", PeerID: repo + "#" + n, MessageID: value(m, "comment", "id"), Text: text}
}
func (p *Provider) gitlab(m map[string]any) *Message {
	a := object(m, "object_attributes")
	if a["system"] == true {
		return nil
	}
	kind := value(a, "noteable_type")
	field := "issue"
	if kind == "MergeRequest" {
		field = "merge_request"
	} else if kind != "Issue" {
		return nil
	}
	n := value(m, field, "iid")
	repo := value(m, "project", "path_with_namespace")
	text := value(a, "note")
	if n == "" || repo == "" || text == "" {
		return nil
	}
	return &Message{ChannelID: p.ID, ProviderType: p.Config.Type, AccountID: value(m, "project", "namespace"), SenderID: value(m, "user", "username"), PeerKind: "THREAD", PeerID: repo + "#" + n + ":" + kind, MessageID: value(a, "id"), Text: text}
}
