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
