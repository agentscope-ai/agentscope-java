package orchestration

import (
	"context"
	"testing"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestEngineKeepsDynamicAdaptiveNodeWaiting(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	team, err := st.Collaboration().CreateTeam(ctx, &controlmodel.CollaborationTeam{
		Tenant: "tenant-a", Namespace: "ns-a", Name: "operators", LeaderAgentRef: "leader",
	})
	if err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{
		Tenant: "tenant-a", Namespace: "ns-a", Title: "dynamic work",
		AssigneeType: controlmodel.AssigneeTeam, AssigneeRef: team.ID.String(),
		Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"},
	})
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID, Limit: 10})
	if err != nil || len(tasks) != 1 {
		t.Fatalf("dynamic task: tasks=%+v err=%v", tasks, err)
	}
	if err = (&Engine{Store: st}).ReconcileRun(ctx, tasks[0].OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	run, err := st.Orchestration().GetRun(ctx, tasks[0].OrchestrationRunID)
	if err != nil || run.State != controlmodel.RunWaiting {
		t.Fatalf("adaptive Run should wait for its coordinator: run=%+v err=%v", run, err)
	}
	node, err := st.Orchestration().GetNode(ctx, tasks[0].RunNodeID)
	if err != nil || node.State != controlmodel.RunNodeWaiting || node.WaitReason != "team_coordinator" {
		t.Fatalf("dynamic coordinator node should wait: node=%+v err=%v", node, err)
	}
}
