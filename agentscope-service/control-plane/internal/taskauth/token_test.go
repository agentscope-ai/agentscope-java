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
	"encoding/base64"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestManagedCredentialBindsNativeLifetimeWithoutBecomingLegacyAuthority(t *testing.T) {
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	task, attempt, session := uuid.New(), uuid.New(), uuid.New()
	token, err := manager.MintManaged(task, attempt, 7, session)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := manager.VerifyClaims(token, time.Now().Add(72*time.Hour))
	if err != nil || claims.TaskID != task || claims.AttemptID != attempt || claims.Generation != 7 || claims.ManagedSessionID != session || !claims.ExpiresAt.IsZero() {
		t.Fatalf("native identity: %+v %v", claims, err)
	}
	if err := manager.Verify(token, task, time.Now()); err == nil {
		t.Fatal("signature-only legacy helper authorized a native credential")
	}
	if _, err := manager.VerifyAttemptClaims(token, time.Now()); err == nil {
		t.Fatal("native credential became a backend reporting token")
	}
	parts := strings.Split(token, ".")
	raw, _ := base64.RawURLEncoding.DecodeString(parts[0])
	parts[0] = base64.RawURLEncoding.EncodeToString([]byte(strings.Replace(string(raw), session.String(), uuid.NewString(), 1)))
	if _, err := manager.VerifyClaims(strings.Join(parts, "."), time.Now()); err == nil {
		t.Fatal("Session identity was not signed")
	}
	for _, invalid := range []struct {
		task, attempt, session uuid.UUID
		generation             int64
	}{
		{uuid.Nil, attempt, session, 7}, {task, uuid.Nil, session, 7}, {task, attempt, uuid.Nil, 7}, {task, attempt, session, 0},
	} {
		if _, err := manager.MintManaged(invalid.task, invalid.attempt, invalid.generation, invalid.session); err == nil {
			t.Fatal("incomplete native credential minted")
		}
	}
}

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

func TestHostedTaskTokenCarriesItsPhysicalLeaseFence(t *testing.T) {
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	taskID, attemptID, hostID := uuid.New(), uuid.New(), uuid.New()
	now := time.Now()
	token, err := manager.MintFenced(taskID, attemptID, 3, 19, hostID, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := manager.VerifyClaims(token, now)
	if err != nil || claims.TaskID != taskID || claims.AttemptID != attemptID || claims.Generation != 3 || claims.Fence != 19 || claims.HostID != hostID {
		t.Fatalf("fenced claims=%+v %v", claims, err)
	}
	if _, err := manager.VerifyAttemptClaims(token, now); err == nil {
		t.Fatal("task credential substituted for an attempt reporter")
	}
}
