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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

// Repair missing coordinator return routes for explicitly selected completed
// child tasks. Existing results and terminal execution records are preserved.
package main

import (
	"context"
	"flag"
	"fmt"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"os"
)

func main() {
	apply := flag.Bool("apply", false, "restore missing return routes; otherwise inspect only")
	yieldTask := flag.Bool("yield-decided-task", false, "complete a decided leader turn waiting for queued sibling outcomes")
	reconcileRun := flag.Bool("reconcile-completed-run", false, "reconcile root Issue status for explicitly selected completed Run IDs")
	flag.Parse()
	if flag.NArg() == 0 {
		panic("supply completed child Task IDs")
	}
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverPostgres, PostgresDSN: os.Getenv("AISTIO_STORAGE_DSN")})
	if err != nil {
		panic("cannot open store; check AISTIO_STORAGE_DSN")
	}
	defer st.Close()
	svc := &collaboration.Service{Store: st}
	for _, raw := range flag.Args() {
		id, err := uuid.Parse(raw)
		if err != nil {
			panic(err)
		}
		if *reconcileRun {
			run, err := st.Orchestration().GetRun(ctx, id)
			if err != nil {
				panic(err)
			}
			if run.State != controlmodel.RunSucceeded && run.State != controlmodel.RunPartialSucceeded {
				panic("only completed Runs can be reconciled by this repair")
			}
			if *apply {
				if err = (&orchestration.Engine{Store: st}).ReconcileRun(ctx, id); err != nil {
					panic(err)
				}
			}
			fmt.Printf("run=%s issue=%s state=%s apply=%t\n", run.ID, run.RootIssueID, run.State, *apply)
			continue
		}
		task, err := st.Collaboration().GetAgentTask(ctx, id)
		if err != nil {
			panic(err)
		}
		fmt.Printf("task=%s issue=%s state=%s apply=%t\n", task.ID, task.IssueID, task.Status, *apply)
		if *apply {
			if *yieldTask {
				_, comment, err := svc.WaitForDelegatedWork(ctx, id, "系统恢复等待回合：当前子任务已验收，交由同一协调节点中已排队的结果回合继续处理。")
				if err != nil {
					panic(err)
				}
				fmt.Printf("waitComment=%s\n", comment.ID)
				continue
			}
			comment, err := svc.ResumeCompletedDelegatedTask(ctx, id)
			if err != nil {
				panic(err)
			}
			fmt.Printf("returnComment=%s\n", comment.ID)
		}
	}
}
