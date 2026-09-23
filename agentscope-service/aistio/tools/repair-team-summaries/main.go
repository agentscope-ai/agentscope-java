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

// repair-team-summaries backfills root summaries for explicitly selected
// terminal runs. It preserves Issue, Task, Attempt and Run statuses.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"

	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
)

func main() {
	apply := flag.Bool("apply", false, "persist summaries; otherwise only inspect selected runs")
	flag.Parse()
	if flag.NArg() == 0 {
		fmt.Fprintln(os.Stderr, "supply terminal Run IDs; set AISTIO_STORAGE_DSN")
		os.Exit(2)
	}
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverPostgres, PostgresDSN: os.Getenv("AISTIO_STORAGE_DSN")})
	if err != nil {
		fmt.Fprintln(os.Stderr, "cannot open store (check AISTIO_STORAGE_DSN)")
		os.Exit(1)
	}
	defer st.Close()
	// Validate every selection before applying any changes.
	runs := []*controlmodel.OrchestrationRun{}
	for _, arg := range flag.Args() {
		id, err := uuid.Parse(arg)
		if err != nil {
			panic("invalid Run ID")
		}
		run, err := st.Orchestration().GetRun(ctx, id)
		if err != nil {
			panic(err)
		}
		if !controlmodel.IsOrchestrationRunTerminal(run.State) || run.ParentRunID != nil {
			panic("only terminal root Runs can be repaired")
		}
		runs = append(runs, run)
	}
	for _, run := range runs {
		if *apply {
			if err := (&collaboration.Service{Store: st}).EnsureTerminalTeamSummary(ctx, run); err != nil {
				panic(err)
			}
		}
		fmt.Printf("run=%s issue=%s state=%s applied=%t\n", run.ID, run.RootIssueID, run.State, *apply)
	}
}
