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
