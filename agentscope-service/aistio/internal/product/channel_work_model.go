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
	"encoding/hex"
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"github.com/google/uuid"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// ChannelWorkRuntime uses the same authorization and collaboration store as the console.
// Provider callbacks never supply an internal account, namespace or execution identity.
type ChannelWorkRuntime struct {
	AuthorizeTarget func(context.Context, *model.Namespace, string, string, string) error
	Store           store.Store
	Namespace       func(context.Context, string) (*model.Namespace, error)
	Target          func(context.Context, *model.Namespace, string, string) error
}

func (s *Server) SetChannelWorkRuntime(runtime *ChannelWorkRuntime) { s.channelWork = runtime }

type ChannelTarget struct {
	TargetType string `json:"targetType"`
	TargetRef  string `json:"targetRef"`
}
type ChannelWorkRoute struct {
	RestrictGroups bool     `json:"restrictGroups,omitempty"`
	AllowedGroups  []string `json:"allowedGroups,omitempty"`
	ChannelTarget
	AccountID string `json:"accountId"`
	PeerKind  string `json:"peerKind"`
	PeerID    string `json:"peerId"`
	ThreadID  string `json:"threadId,omitempty"`
}
type ChannelWorkSettings struct {
	Enabled       bool               `json:"enabled"`
	DefaultTarget ChannelTarget      `json:"defaultTarget"`
	Routes        []ChannelWorkRoute `json:"routes"`
	// Group work requires an explicit /new-shared command as well as this opt-in.
	AllowGroupWork bool     `json:"allowGroupWork"`
	NotifyEvents   []string `json:"notifyEvents"`
	Version        int64    `json:"version"`
}
type ChannelInbound struct {
	ChannelID string `json:"channelId"`
	AccountID string `json:"accountId"`
	SenderID  string `json:"senderId"`
	PeerKind  string `json:"peerKind"`
	PeerID    string `json:"peerId"`
	ThreadID  string `json:"threadId"`
	MessageID string `json:"messageId"`
	ReplyToID string `json:"replyToId"`
	Text      string `json:"text"`
}

func (v ChannelInbound) addressKey() string {
	b, _ := json.Marshal([]string{v.AccountID, v.PeerKind, v.PeerID, v.ThreadID})
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func (v ChannelInbound) key() uuid.UUID {
	b, _ := json.Marshal([]string{v.ChannelID, v.AccountID, v.MessageID})
	return uuid.NewSHA1(uuid.NameSpaceURL, b)
}
func (v ChannelInbound) validate() error {
	for _, field := range []string{v.ChannelID, v.AccountID, v.SenderID, v.PeerID, v.MessageID} {
		if strings.TrimSpace(field) == "" || len(field) > 512 {
			return fmt.Errorf("provider identity, organization, address and message ID are required")
		}
	}
	if v.PeerKind != "DIRECT" && v.PeerKind != "GROUP" {
		return fmt.Errorf("unsupported conversation kind")
	}
	if len(v.Text) > 32000 || strings.TrimSpace(v.Text) == "" || len(v.ThreadID) > 512 || len(v.ReplyToID) > 512 {
		return fmt.Errorf("invalid message")
	}
	return nil
}
func (v ChannelWorkSettings) route(in ChannelInbound) ChannelTarget {
	// An exact thread rule wins; otherwise the first configured address rule wins.
	for _, r := range v.Routes {
		if r.ThreadID != "" && r.ThreadID == in.ThreadID && r.AccountID == in.AccountID && r.PeerKind == in.PeerKind && r.PeerID == in.PeerID {
			return r.ChannelTarget
		}
	}
	for _, r := range v.Routes {
		if r.ThreadID == "" && r.AccountID == in.AccountID && r.PeerKind == in.PeerKind && r.PeerID == in.PeerID {
			return r.ChannelTarget
		}
	}
	return v.DefaultTarget
}

const channelWorkMigrationSQL = `
CREATE TABLE IF NOT EXISTS channel_work_settings (
 channel_id TEXT PRIMARY KEY REFERENCES channels(channel_id) ON DELETE CASCADE,
 config JSONB NOT NULL, version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS channel_pairing_codes (
 code_hash TEXT PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE, expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS channel_identities (
 channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 account_id TEXT NOT NULL, sender_id TEXT NOT NULL,
 user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(channel_id,account_id,sender_id)
);
CREATE TABLE IF NOT EXISTS channel_inbound (
 id UUID PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 request JSONB NOT NULL, user_id TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'pending', issue_id UUID, comment_id UUID,
 result TEXT NOT NULL DEFAULT '', attempts INT NOT NULL DEFAULT 0,
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS channel_inbound_pending ON channel_inbound(next_attempt) WHERE status='pending';
CREATE TABLE IF NOT EXISTS channel_work_links (
 id UUID PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 address_key TEXT NOT NULL, address JSONB NOT NULL, user_id TEXT NOT NULL,
 issue_id UUID NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 cursor_time TIMESTAMPTZ NOT NULL DEFAULT now(), cursor_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
 last_status TEXT NOT NULL DEFAULT '', next_poll TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(channel_id,address_key,user_id,issue_id)
);
CREATE TABLE IF NOT EXISTS channel_deliveries (
 id UUID PRIMARY KEY, channel_id TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
 address_key TEXT NOT NULL, address JSONB NOT NULL, user_id TEXT NOT NULL,
 issue_id UUID, comment_id UUID, event_key TEXT NOT NULL UNIQUE, text TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending', attempts INT NOT NULL DEFAULT 0,
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(), lease_token TEXT NOT NULL DEFAULT '',
 provider_message_id TEXT NOT NULL DEFAULT '', last_error TEXT NOT NULL DEFAULT '',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS channel_deliveries_pending ON channel_deliveries(next_attempt) WHERE state IN ('pending','submitted');
CREATE INDEX IF NOT EXISTS channel_delivery_replies ON channel_deliveries(channel_id,address_key,provider_message_id) WHERE provider_message_id<>'';
`

// Only the most specific matched window rule contributes permissions. Falling
// back to a broader rule must never bypass a restrictive thread rule.
func (v ChannelWorkSettings) allowsWindow(n *model.Namespace, user string, in ChannelInbound) bool {
	var chosen *ChannelWorkRoute
	for i := range v.Routes {
		r := &v.Routes[i]
		if r.AccountID != in.AccountID || r.PeerKind != in.PeerKind || r.PeerID != in.PeerID {
			continue
		}
		if r.ThreadID != "" && r.ThreadID == in.ThreadID {
			chosen = r
			break
		}
		if r.ThreadID == "" && chosen == nil {
			chosen = r
		}
	}
	if chosen == nil || !chosen.RestrictGroups {
		return true
	}
	for _, id := range chosen.AllowedGroups {
		if slices.Contains(n.GroupIDs(user), id) {
			return true
		}
	}
	return false
}
