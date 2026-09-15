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

package runtimeauth

import (
	"strings"
	"testing"
	"time"
)

func TestRuntimeCredentialIsScopedAndExpires(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef"), TTL: time.Minute}
	token, minted, err := manager.Mint("host-1", "acme", "engineering", now)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(token, Prefix) {
		t.Fatalf("token = %q", token)
	}
	verified, err := manager.Verify(token, now.Add(30*time.Second))
	if err != nil || verified != minted {
		t.Fatalf("verified=%+v minted=%+v err=%v", verified, minted, err)
	}
	if _, err := manager.Verify(token+"x", now); err == nil {
		t.Fatal("tampered token was accepted")
	}
	if _, err := manager.Verify(token, now.Add(2*time.Minute)); err == nil {
		t.Fatal("expired token was accepted")
	}
}

func TestEnrollmentTokenCarriesScopeAndExpiresQuickly(t *testing.T) {
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef")}
	token, minted, err := manager.MintEnrollment("acme", "engineering", now)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(token, EnrollmentPrefix) {
		t.Fatalf("token = %q", token)
	}
	verified, err := manager.VerifyEnrollment(token, now.Add(time.Minute))
	if err != nil || verified != minted {
		t.Fatalf("verified=%+v minted=%+v err=%v", verified, minted, err)
	}
	if _, err := manager.VerifyEnrollment(token, now.Add(16*time.Minute)); err == nil {
		t.Fatal("expired enrollment token was accepted")
	}
	runtimeToken, _, err := manager.Mint("host-1", "acme", "engineering", now)
	if err != nil {
		t.Fatal(err)
	}
	forgedEnrollment := EnrollmentPrefix + strings.TrimPrefix(runtimeToken, Prefix)
	if _, err := manager.VerifyEnrollment(forgedEnrollment, now); err == nil {
		t.Fatal("Runtime Host credential was accepted as an enrollment token")
	}
}

func TestParseUnverifiedReturnsClientRoutingScope(t *testing.T) {
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	manager := Manager{Secret: []byte("0123456789abcdef0123456789abcdef")}
	token, _, err := manager.Mint("host-1", "acme", "engineering", now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := ParseUnverified(token)
	if err != nil {
		t.Fatal(err)
	}
	if claims.HostKey != "host-1" || claims.Tenant != "acme" || claims.Namespace != "engineering" {
		t.Fatalf("claims = %+v", claims)
	}
}
