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

package taskauth

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestVerifyAndExtractTaskToken(t *testing.T) {
	now := time.Now().UTC()
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	taskID := uuid.New()
	token, err := manager.Mint(taskID, now)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := manager.VerifyAndExtract(token, now.Add(time.Second))
	if err != nil || actual != taskID {
		t.Fatalf("extract: id=%s err=%v", actual, err)
	}
	if err := manager.Verify(token, uuid.New(), now); err == nil {
		t.Fatal("token verified for a different task")
	}
	if _, err := manager.VerifyAndExtract(token, now.Add(2*time.Minute)); err == nil {
		t.Fatal("expired token was accepted")
	}
}

func TestAttemptTokenIsFencedAndNotATaskToken(t *testing.T) {
	now := time.Now().UTC()
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	attemptID := uuid.New()
	token, err := manager.MintAttempt(attemptID, 3, "external-application", "instance.one", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.VerifyAttempt(token, attemptID, 3, "external-application", "instance.one", now); err != nil {
		t.Fatal(err)
	}
	if err := manager.VerifyAttempt(token, attemptID, 4, "external-application", "instance.one", now); err == nil {
		t.Fatal("stale generation was accepted")
	}
	if _, err := manager.VerifyClaims(token, now); err == nil {
		t.Fatal("attempt token was accepted as a task token")
	}
}
