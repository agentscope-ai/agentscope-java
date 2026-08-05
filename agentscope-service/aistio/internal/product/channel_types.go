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
	"fmt"
	"strings"
)

// ChannelFieldSpec describes one provider property shown in the Console form.
type ChannelFieldSpec struct {
	Key       string `json:"key"`
	Label     string `json:"label"`
	Required  bool   `json:"required"`
	Secret    bool   `json:"secret"`
	InputType string `json:"inputType"` // text | password | number
	Advanced  bool   `json:"advanced,omitempty"`
	Hint      string `json:"hint,omitempty"`
}

// ChannelTypeSpec is returned by GET /api/channels/types.
type ChannelTypeSpec struct {
	Type        string             `json:"type"`
	Label       string             `json:"label"`
	Transport   string             `json:"transport"` // stream | callback | webhook
	CallbackURL string             `json:"callbackUrlTemplate,omitempty"`
	Hint        string             `json:"hint,omitempty"`
	Fields      []ChannelFieldSpec `json:"fields"`
}

var supportedChannelTypes = []ChannelTypeSpec{
	{
		Type:      "dingtalk",
		Label:     "DingTalk",
		Transport: "stream",
		Hint:      "Enable Stream mode and subscribe to /v1.0/im/bot/messages/get in the DingTalk console.",
		Fields: []ChannelFieldSpec{
			{Key: "appKey", Label: "App Key", Required: true, InputType: "text"},
			{Key: "appSecret", Label: "App Secret", Required: true, Secret: true, InputType: "password"},
			{Key: "robotCode", Label: "Robot Code", Required: true, InputType: "text"},
			{Key: "apiBase", Label: "API Base", Required: false, InputType: "text", Advanced: true, Hint: "Default https://api.dingtalk.com"},
			{Key: "oapiBase", Label: "OAPI Base", Required: false, InputType: "text", Advanced: true, Hint: "Default https://oapi.dingtalk.com"},
			{Key: "streamRegisterUrl", Label: "Stream Register URL", Required: false, InputType: "text", Advanced: true},
		},
	},
	{
		Type:        "feishu",
		Label:       "Feishu / Lark",
		Transport:   "callback",
		CallbackURL: "/api/channels/feishu/{channelId}/callback",
		Hint:        "Paste the callback URL into Feishu event subscription settings.",
		Fields: []ChannelFieldSpec{
			{Key: "appId", Label: "App ID", Required: true, InputType: "text"},
			{Key: "appSecret", Label: "App Secret", Required: true, Secret: true, InputType: "password"},
			{Key: "encryptKey", Label: "Encrypt Key", Required: false, Secret: true, InputType: "password"},
			{Key: "verificationToken", Label: "Verification Token", Required: false, Secret: true, InputType: "password"},
			{Key: "callbackPath", Label: "Callback Path", Required: false, InputType: "text", Advanced: true},
			{Key: "apiBase", Label: "API Base", Required: false, InputType: "text", Advanced: true, Hint: "Default https://open.feishu.cn"},
		},
	},
	{
		Type:        "wecom",
		Label:       "WeCom",
		Transport:   "callback",
		CallbackURL: "/api/channels/wecom/{channelId}/callback",
		Hint:        "Paste the callback URL into WeCom receive-message settings.",
		Fields: []ChannelFieldSpec{
			{Key: "corpId", Label: "Corp ID", Required: true, InputType: "text"},
			{Key: "agentId", Label: "Agent ID", Required: true, InputType: "number"},
			{Key: "secret", Label: "Secret", Required: true, Secret: true, InputType: "password"},
			{Key: "token", Label: "Callback Token", Required: true, Secret: true, InputType: "password"},
			{Key: "encodingAesKey", Label: "EncodingAESKey", Required: true, Secret: true, InputType: "password", Hint: "Exactly 43 characters"},
			{Key: "callbackPath", Label: "Callback Path", Required: false, InputType: "text", Advanced: true},
			{Key: "apiBase", Label: "API Base", Required: false, InputType: "text", Advanced: true},
		},
	},
	{
		Type:        "github",
		Label:       "GitHub",
		Transport:   "webhook",
		CallbackURL: "/api/channels/github/{channelId}/webhook",
		Fields: []ChannelFieldSpec{
			{Key: "token", Label: "Personal Access Token", Required: true, Secret: true, InputType: "password"},
			{Key: "webhookSecret", Label: "Webhook Secret", Required: true, Secret: true, InputType: "password"},
			{Key: "botUserLogin", Label: "Bot User Login", Required: false, InputType: "text"},
			{Key: "webhookPath", Label: "Webhook Path", Required: false, InputType: "text", Advanced: true},
			{Key: "apiBase", Label: "API Base", Required: false, InputType: "text", Advanced: true},
		},
	},
	{
		Type:        "gitlab",
		Label:       "GitLab",
		Transport:   "webhook",
		CallbackURL: "/api/channels/gitlab/{channelId}/webhook",
		Fields: []ChannelFieldSpec{
			{Key: "token", Label: "Access Token", Required: true, Secret: true, InputType: "password"},
			{Key: "webhookToken", Label: "Webhook Token", Required: true, Secret: true, InputType: "password"},
			{Key: "webhookPath", Label: "Webhook Path", Required: false, InputType: "text", Advanced: true},
			{Key: "apiBase", Label: "API Base", Required: false, InputType: "text", Advanced: true},
		},
	},
}

func channelTypeSpec(t string) *ChannelTypeSpec {
	for i := range supportedChannelTypes {
		if supportedChannelTypes[i].Type == t {
			return &supportedChannelTypes[i]
		}
	}
	return nil
}

func knownChannelType(t string) bool {
	return channelTypeSpec(t) != nil
}

// validateChannelProperties checks required fields for the given type.
// When allowMaskedSecrets is true, secretMask counts as present (update path).
func validateChannelProperties(typ string, props map[string]any, existing map[string]any, allowMaskedSecrets bool) (missing []string, err error) {
	spec := channelTypeSpec(typ)
	if spec == nil {
		return nil, fmt.Errorf("Unknown channel type: %s", typ)
	}
	if props == nil {
		props = map[string]any{}
	}
	for _, f := range spec.Fields {
		if !f.Required {
			continue
		}
		v, ok := props[f.Key]
		if ok && v != nil {
			if str, isStr := v.(string); isStr {
				if str == "" {
					ok = false
				} else if allowMaskedSecrets && f.Secret && str == secretMask {
					// Keep existing secret.
					if existing != nil {
						if ev, eok := existing[f.Key]; eok && ev != nil && fmt.Sprint(ev) != "" {
							ok = true
						} else {
							ok = false
						}
					} else {
						ok = false
					}
				}
			}
			if f.InputType == "number" {
				switch n := v.(type) {
				case float64:
					ok = n > 0
				case int:
					ok = n > 0
				case int64:
					ok = n > 0
				case string:
					ok = strings.TrimSpace(n) != "" && n != "0"
				}
			}
			if f.Key == "encodingAesKey" {
				if str, isStr := v.(string); isStr && str != secretMask && len(str) != 43 {
					return nil, fmt.Errorf("encodingAesKey must be 43 characters")
				}
			}
		} else {
			ok = false
			// Fall back to existing on update when key omitted.
			if allowMaskedSecrets && existing != nil {
				if ev, eok := existing[f.Key]; eok && ev != nil && fmt.Sprint(ev) != "" {
					ok = true
				}
			}
		}
		if !ok {
			missing = append(missing, f.Key)
		}
	}
	if len(missing) > 0 {
		return missing, fmt.Errorf("missing required fields: %s", strings.Join(missing, ", "))
	}
	return nil, nil
}

func propsAsMap(v any) map[string]any {
	if v == nil {
		return map[string]any{}
	}
	if m, ok := v.(map[string]any); ok {
		return m
	}
	return map[string]any{}
}
