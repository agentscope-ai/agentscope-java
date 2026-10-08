/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package model

import (
	"time"

	"github.com/google/uuid"
)

// Application is the durable business identity behind one or more Endpoint credentials.
// Members grant explicit human access; a credential remains only an authentication instrument.
type Application struct {
	ID            uuid.UUID           `json:"id"`
	Tenant        string              `json:"tenant"`
	Namespace     string              `json:"namespace"`
	Name          string              `json:"name"`
	Description   string              `json:"description,omitempty"`
	OwnerUserID   string              `json:"ownerUserId"`
	Members       []ApplicationMember `json:"members"`
	Status        string              `json:"status"`
	MaxConcurrent int                 `json:"maxConcurrent"`
	TokenBudget   int64               `json:"tokenBudget"`
	TokensUsed    int64               `json:"tokensUsed"`
	Version       int64               `json:"version"`
	CreatedAt     time.Time           `json:"createdAt"`
	UpdatedAt     time.Time           `json:"updatedAt"`
}
type ApplicationMember struct {
	UserID string   `json:"userId"`
	Roles  []string `json:"roles"`
}

// Allows separates read, operate, and human approval; application ownership implies management,
// not an ability for API keys to impersonate the owner in approval decisions.
func (a *Application) Allows(user, action string) bool {
	if user == "" {
		return false
	}
	if a.OwnerUserID == user {
		return true
	}
	for _, member := range a.Members {
		if member.UserID != user {
			continue
		}
		for _, role := range member.Roles {
			if action == "read" && (role == "viewer" || role == "operator" || role == "approver") || action == "operate" && role == "operator" || action == "approve" && role == "approver" {
				return true
			}
		}
	}
	return false
}
