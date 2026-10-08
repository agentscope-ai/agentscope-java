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

package store

import (
	"bytes"
	"encoding/json"
	"fmt"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"reflect"
	"time"
)

// AutomationAdmission is committed under the rule lock. A scheduled admission
// advances exactly the cursor it observed, in the same transaction as the Run.
type AutomationAdmission struct {
	Run                 *controlmodel.AutomationRun
	ExpectedVersion     int64
	ObservedScheduledAt *time.Time
	NextRunAt           *time.Time
}

func SameAutomationInput(a, b json.RawMessage) bool {
	if len(a) == 0 {
		a = []byte(`null`)
	}
	if len(b) == 0 {
		b = []byte(`null`)
	}
	var x, y any
	da, db := json.NewDecoder(bytes.NewReader(a)), json.NewDecoder(bytes.NewReader(b))
	da.UseNumber()
	db.UseNumber()
	return da.Decode(&x) == nil && db.Decode(&y) == nil && reflect.DeepEqual(x, y)
}

func PrepareAutomationAdmission(rule *controlmodel.Automation, req AutomationAdmission, active bool, now time.Time) error {
	run := req.Run
	if rule.ArchivedAt != nil || (!rule.Enabled && run.Source != "test") {
		return fmt.Errorf("automation is disabled")
	}
	if rule.Version != req.ExpectedVersion {
		return ErrConflict
	}
	if run.ScheduledAt != nil {
		observed := run.ScheduledAt
		if req.ObservedScheduledAt != nil {
			observed = req.ObservedScheduledAt
		}
		found := false
		for i := range rule.Triggers {
			t := &rule.Triggers[i]
			if t.ID == run.TriggerID && t.Enabled && t.NextRunAt != nil && t.NextRunAt.Equal(*observed) {
				t.NextRunAt = req.NextRunAt
				fired := *run.ScheduledAt
				t.LastFiredAt = &fired
				found = true
			}
		}
		if !found {
			return ErrConflict
		}
		rule.NextRunAt = nil
		for _, t := range rule.Triggers {
			if t.Enabled && t.NextRunAt != nil && (rule.NextRunAt == nil || t.NextRunAt.Before(*rule.NextRunAt)) {
				v := *t.NextRunAt
				rule.NextRunAt = &v
			}
		}
	}
	run.Status = controlmodel.AutomationRunQueued
	if active && rule.Execution != nil && rule.Execution.ConcurrencyPolicy != "queue" {
		run.Status = controlmodel.AutomationRunSkipped
		run.ErrorCode = "already_active"
		run.ErrorMessage = "A previous execution is still active."
		run.CompletedAt = &now
	}
	if run.ErrorCode == "misfire_expired" {
		run.Status = controlmodel.AutomationRunSkipped
		run.CompletedAt = &now
	}
	run.Version = 1
	run.CreatedAt = now
	run.UpdatedAt = now
	rule.LastRunAt = &now
	return nil
}

// Preserve schedule progress committed since an editor loaded the rule. The
// caller holds the rule lock, so unchanged triggers cannot rewind a live cursor.
func MergeAutomationScheduleOnEdit(current, next *controlmodel.Automation) {
	if next.Triggers == nil {
		return
	}
	for i := range next.Triggers {
		t := &next.Triggers[i]
		if !next.Enabled || !t.Enabled {
			t.NextRunAt = nil
			continue
		}
		for _, old := range current.Triggers {
			if old.ID == t.ID && current.Enabled && old.Enabled && old.Type == t.Type && old.Schedule == t.Schedule && old.Timezone == t.Timezone {
				t.NextRunAt = old.NextRunAt
				t.LastFiredAt = old.LastFiredAt
			}
		}
	}
	next.NextRunAt = nil
	for _, t := range next.Triggers {
		if next.Enabled && t.Enabled && t.NextRunAt != nil && (next.NextRunAt == nil || t.NextRunAt.Before(*next.NextRunAt)) {
			v := *t.NextRunAt
			next.NextRunAt = &v
		}
	}
}
