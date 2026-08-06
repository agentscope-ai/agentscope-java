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

package dataplane

import (
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/prober"
)

func TestSessionsProbeLikelyTruncated(t *testing.T) {
	if prober.SessionsProbeLikelyTruncated(499) {
		t.Fatal("499 should not be truncated")
	}
	if !prober.SessionsProbeLikelyTruncated(500) {
		t.Fatal("500 should be truncated")
	}
	res := prober.SessionsProbeResult{Sessions: make([]prober.SessionSnapshot, 10), HasMore: true}
	if !res.LikelyTruncated() {
		t.Fatal("HasMore should mark truncated")
	}
}
