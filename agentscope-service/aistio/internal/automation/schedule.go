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
