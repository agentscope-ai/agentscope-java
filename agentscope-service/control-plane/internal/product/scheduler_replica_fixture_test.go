// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"testing"
)

// TestWebhookReplicaFixture exposes the production worker to the owned process
// failure acceptance harness without adding another deployable service command.
func TestWebhookReplicaFixture(t *testing.T) {
	if os.Getenv("MANAGED_AGENTS_WEBHOOK_REPLICA_FIXTURE") != "1" {
		t.Skip("process acceptance fixture only")
	}
	worker, err := newAgentServiceWebhookWorker(os.Getenv("MANAGED_AGENTS_AGENT_SERVICE_URL"), os.Getenv("MANAGED_AGENTS_INTERNAL_TOKEN"))
	if err != nil {
		t.Fatal(err)
	}
	defer worker.close()
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	fmt.Println("READY production webhook worker")
	if err = worker.run(ctx); err != nil {
		t.Fatal(err)
	}
}
