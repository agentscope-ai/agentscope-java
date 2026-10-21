// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimehost

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeauth"
	"github.com/google/uuid"
)

func TestRuntimeHostCredentialRenewalIsConcurrentDurableAndScoped(t *testing.T) {
	manager := runtimeauth.Manager{Secret: []byte("machine-signing-secret-at-least-32-bytes")}
	short := manager
	short.TTL = time.Minute
	initial, _, _ := short.Mint("host", "tenant", "space", time.Now())
	renewed, _, _ := manager.Mint("host", "tenant", "space", time.Now())
	host := &model.RuntimeHost{ID: uuid.New(), HostKey: "host", Tenant: "tenant", Namespace: "space", LeaseGeneration: 7}
	var renewals atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/v1/runtime-hosts/"+host.ID.String()+"/credentials" {
			if r.Header.Get("Authorization") != "Bearer "+initial || r.Header.Get("X-Builder-Internal-Token") != "" {
				t.Error("renewal replaced Host authority")
			}
			var body struct {
				Generation int64 `json:"generation"`
			}
			if json.NewDecoder(r.Body).Decode(&body) != nil || body.Generation != host.LeaseGeneration {
				t.Error("renewal changed generation")
			}
			renewals.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"runtimeToken": renewed})
		} else if r.URL.Path == "/api/v1/runtime-hosts/"+host.ID.String()+"/heartbeat" {
			if r.Header.Get("Authorization") != "Bearer "+renewed {
				t.Error("heartbeat did not use renewed machine credential")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"host": host})
		} else {
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	root := t.TempDir()
	client := &Client{BaseURL: server.URL, InternalToken: initial, HTTPClient: server.Client()}
	if err := client.ConfigureHostCredentials(root, "host"); err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	for range 8 {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := client.Heartbeat(t.Context(), host, 0, nil); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	if renewals.Load() != 1 {
		t.Fatalf("concurrent renewal count=%d", renewals.Load())
	}
	path := filepath.Join(root, "host-credential.json")
	info, err := os.Stat(path)
	if err != nil || (runtime.GOOS != "windows" && info.Mode().Perm() != 0o600) {
		t.Fatal("credential cache is missing or not private")
	}
	restarted := &Client{InternalToken: initial}
	if err := restarted.ConfigureHostCredentials(root, "host"); err != nil || restarted.currentHostCredential() != renewed {
		t.Fatal("restart did not restore renewal")
	}
	manual, _, _ := manager.Mint("host", "tenant", "new-space", time.Now())
	reconfigured := &Client{InternalToken: manual}
	if err := reconfigured.ConfigureHostCredentials(root, "host"); err != nil || reconfigured.currentHostCredential() != manual {
		t.Fatal("old cache overrode a newly supplied credential")
	}
	other, _, _ := manager.Mint("other-host", "tenant", "space", time.Now())
	bad, _ := json.Marshal(hostCredentialRecord{client.bootstrapFingerprint, other})
	if err := os.WriteFile(path, bad, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := (&Client{InternalToken: initial}).ConfigureHostCredentials(root, "host"); err == nil {
		t.Fatal("cross-host cache was accepted")
	}
}
