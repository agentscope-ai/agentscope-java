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

package invocation

import (
	"encoding/json"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
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
