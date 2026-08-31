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

import "testing"

func TestValidCreateDeployReqRequiresEnvironment(t *testing.T) {
	base := createDeployReq{Name: "nightly", AgentID: "agent-1", TriggerType: "manual", EnvironmentID: "env-1"}
	if !validCreateDeployReq(base) {
		t.Fatal("expected complete deployment request to be valid")
	}

	for _, environmentID := range []string{"", "   "} {
		req := base
		req.EnvironmentID = environmentID
		if validCreateDeployReq(req) {
			t.Fatalf("expected environmentId %q to be rejected", environmentID)
		}
	}
}

func TestValidCronExpression(t *testing.T) {
	for _, expression := range []string{"0 9 * * 1-5", "*/15 0-23 1,15 * 0", "0 0 1 1 *"} {
		if !validCronExpression(expression) {
			t.Errorf("expected valid cron expression %q", expression)
		}
	}
	for _, expression := range []string{"", "0 9 * *", "60 9 * * *", "0 24 * * *", "0 9 * 13 *", "0 9 * * foo", "*/0 9 * * *"} {
		if validCronExpression(expression) {
			t.Errorf("expected invalid cron expression %q", expression)
		}
	}
}
