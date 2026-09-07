package automation

import (
	"context"
	"fmt"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
)

// ProcessDelivery can be retried after any process crash. The delivery ID is
// the stable identity of its admitted Run, including explicitly replayed input.
func (s *Service) ProcessDelivery(ctx context.Context, d *controlmodel.AutomationDelivery) (*controlmodel.AutomationDelivery, error) {
	if d.Status != "queued" {
		return d, nil
	}
	rule, err := s.Store.Collaboration().GetAutomation(ctx, d.AutomationID)
	if err != nil {
		return nil, err
	}
	NormalizeLegacy(rule)
	var trigger *controlmodel.AutomationTrigger
	for i := range rule.Triggers {
		if rule.Triggers[i].ID == d.TriggerID && rule.Triggers[i].Type == controlmodel.AutomationTriggerWebhook {
			trigger = &rule.Triggers[i]
			break
		}
	}
	reason := ""
	if rule.ArchivedAt != nil || !rule.Enabled {
		reason = "automation_disabled"
	} else if trigger == nil || !trigger.Enabled {
		reason = "trigger_disabled"
	} else if len(trigger.Events) > 0 {
		match := false
		for _, event := range trigger.Events {
			if event == d.Event {
				match = true
			}
		}
		if !match {
			reason = "event_filtered"
		}
	}
	if reason != "" {
		d.Status = "ignored"
		d.Reason = reason
	} else {
		run, err := s.TriggerSource(ctx, rule.ID, d.TriggerID, "webhook", d.Event, d.ID.String(), d.Input, nil)
		if err != nil {
			return nil, err
		}
		d.RunID = &run.ID
		d.Status = "dispatched"
		d.Reason = run.ErrorCode
	}
	saved, _, err := s.Store.Collaboration().SaveAutomationDelivery(ctx, d)
	return saved, err
}
func (s *Service) ReplayDelivery(ctx context.Context, ruleID, id uuid.UUID, key string) (*controlmodel.AutomationDelivery, error) {
	old, err := s.Store.Collaboration().GetAutomationDelivery(ctx, id)
	if err != nil {
		return nil, err
	}
	if old.AutomationID != ruleID {
		return nil, fmt.Errorf("delivery is outside automation scope")
	}
	if old.Status == "queued" || old.Status == "rejected" {
		return nil, fmt.Errorf("only processed authenticated deliveries can be replayed")
	}
	copy := *old
	copy.ID = uuid.New()
	copy.IdempotencyKey = "replay:" + key
	copy.Status = "queued"
	copy.Reason = ""
	copy.RunID = nil
	copy.ReplayedFrom = &id
	d, _, err := s.Store.Collaboration().SaveAutomationDelivery(ctx, &copy)
	if err != nil {
		return nil, err
	}
	return s.ProcessDelivery(ctx, d)
}
