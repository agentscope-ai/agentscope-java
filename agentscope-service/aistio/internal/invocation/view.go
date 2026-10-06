package invocation

import (
	"encoding/json"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// View is the public resource shared by admission, lifecycle reports and projections.
func View(inv *model.EndpointInvocation) map[string]any {
	result := map[string]any{"id": inv.ID.String(), "endpoint_id": inv.EndpointID.String(), "status": inv.Status, "mode": inv.Mode, "created_at": inv.CreatedAt.UnixMilli(), "correlation_id": inv.CorrelationID}
	result["actor"] = inv.Actor
	if inv.ApplicationID != nil {
		result["application_id"] = inv.ApplicationID.String()
	}
	if inv.ReleaseID != nil {
		result["release_id"] = inv.ReleaseID.String()
	}
	if inv.ConversationID != nil {
		result["conversation_id"] = inv.ConversationID.String()
	}
	if len(inv.Result) > 0 {
		result["result"] = json.RawMessage(inv.Result)
	}
	if inv.ErrorCode != "" {
		result["error"] = map[string]any{"code": inv.ErrorCode, "message": inv.ErrorMessage}
	}
	return result
}
