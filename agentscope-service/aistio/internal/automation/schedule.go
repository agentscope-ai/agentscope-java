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

package automation

import (
	"encoding/json"
	"fmt"
	"github.com/robfig/cron/v3"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"strings"
	"time"
	_ "time/tzdata"
)

func ParseSchedule(expression, timezone string) (cron.Schedule, error) {
	expression = strings.TrimSpace(expression)
	if timezone == "" {
		timezone = "UTC"
	}
	if _, err := time.LoadLocation(timezone); err != nil {
		return nil, fmt.Errorf("invalid timezone: %s", timezone)
	}
	if strings.Contains(expression, "TZ=") {
		return nil, fmt.Errorf("use the timezone field instead of an embedded timezone")
	}
	if strings.HasPrefix(expression, "@every ") {
		d, err := time.ParseDuration(strings.TrimPrefix(expression, "@every "))
		if err != nil || d < time.Minute {
			return nil, fmt.Errorf("interval must be at least one minute")
		}
	}
	parser := cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow | cron.Descriptor)
	schedule, err := parser.Parse("CRON_TZ=" + timezone + " " + expression)
	if err != nil {
		return nil, fmt.Errorf("invalid schedule: %w", err)
	}
	return schedule, nil
}
func Preview(expression, timezone string, after time.Time, count int) ([]time.Time, error) {
	schedule, err := ParseSchedule(expression, timezone)
	if err != nil {
		return nil, err
	}
	if count < 1 || count > 10 {
		count = 5
	}
	out := make([]time.Time, 0, count)
	for i := 0; i < count; i++ {
		after = schedule.Next(after)
		if after.IsZero() {
			break
		}
		out = append(out, after.UTC())
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("schedule has no occurrence in the supported horizon")
	}
	return out, nil
}
func nextCron(raw json.RawMessage, after time.Time) (time.Time, error) {
	var v struct {
		Schedule string `json:"schedule"`
		Timezone string `json:"timezone"`
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		return time.Time{}, err
	}
	times, err := Preview(v.Schedule, v.Timezone, after, 1)
	if err != nil {
		return time.Time{}, err
	}
	return times[0], nil
}
func refreshNext(rule *controlmodel.Automation) {
	rule.NextRunAt = nil
	for _, t := range rule.Triggers {
		if rule.Enabled && t.Enabled && t.NextRunAt != nil && (rule.NextRunAt == nil || t.NextRunAt.Before(*rule.NextRunAt)) {
			v := *t.NextRunAt
			rule.NextRunAt = &v
		}
	}
}
