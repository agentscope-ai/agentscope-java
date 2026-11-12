// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package runtimehost

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeauth"
)

type hostCredentialRecord struct {
	BootstrapFingerprint string `json:"bootstrapFingerprint"`
	RuntimeToken         string `json:"runtimeToken"`
}

func (c *Client) currentHostCredential() string {
	if stored := c.hostCredential.Load(); stored != nil {
		return stored.(string)
	}
	return c.InternalToken
}

// ConfigureHostCredentials is called before starting the daemon's goroutines.
// Cached renewals belong to the exact supplied bootstrap and machine identity.
// Unverified claims are expiry hints; only the remote server grants authority.
func (c *Client) ConfigureHostCredentials(stateRoot, hostKey string) error {
	if !strings.HasPrefix(c.InternalToken, runtimeauth.Prefix) {
		return nil // Explicit private-link development credentials retain their contract.
	}
	initial, err := runtimeauth.ParseUnverified(c.InternalToken)
	if err != nil || initial.HostKey != hostKey {
		return fmt.Errorf("Runtime Host credential does not match the local identity")
	}
	c.bootstrapFingerprint = fmt.Sprintf("%x", sha256.Sum256([]byte(c.InternalToken)))
	c.hostCredentialPath = filepath.Join(stateRoot, "host-credential.json")
	c.hostCredential.Store(c.InternalToken)
	file, err := os.Open(c.hostCredentialPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read Runtime Host credential cache: %w", err)
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 8193))
	if err != nil || len(data) > 8192 {
		return fmt.Errorf("Runtime Host credential cache exceeds limits or is unreadable")
	}
	var cached hostCredentialRecord
	if err := json.Unmarshal(data, &cached); err != nil {
		return fmt.Errorf("invalid Runtime Host credential cache")
	}
	if cached.BootstrapFingerprint != c.bootstrapFingerprint {
		return nil // A newly supplied enrollment credential takes precedence.
	}
	claims, err := runtimeauth.ParseUnverified(cached.RuntimeToken)
	if err != nil || claims.HostKey != initial.HostKey || claims.Tenant != initial.Tenant || claims.Namespace != initial.Namespace {
		return fmt.Errorf("Runtime Host credential cache has a different identity")
	}
	if claims.ExpiresAt > initial.ExpiresAt && claims.ExpiresAt > time.Now().Unix() {
		c.hostCredential.Store(cached.RuntimeToken)
	}
	return nil
}

func (c *Client) refreshHostCredentialIfDue(ctx context.Context, host *model.RuntimeHost) {
	if c.hostCredentialPath == "" || host == nil {
		return
	}
	c.hostRefreshMu.Lock()
	defer c.hostRefreshMu.Unlock()
	now := time.Now()
	claims, err := runtimeauth.ParseUnverified(c.currentHostCredential())
	if err != nil || claims.ExpiresAt <= now.Unix() || now.Before(c.hostRefreshRetryAt) ||
		time.Unix(claims.ExpiresAt, 0).After(now.Add(24*time.Hour)) {
		return
	}
	retryDelay := time.Minute
	if remaining := time.Until(time.Unix(claims.ExpiresAt, 0)); remaining < 4*retryDelay {
		retryDelay = remaining / 4
	}
	c.hostRefreshRetryAt = now.Add(retryDelay)
	if err := c.refreshHostCredential(ctx, host, claims); err != nil {
		// A renewal transport/disk failure must not re-register the Host and
		// change generations while the current credential remains usable.
		slog.Warn("Runtime Host credential renewal failed", "error", err)
	}
}

func (c *Client) refreshHostCredential(ctx context.Context, host *model.RuntimeHost, current runtimeauth.Claims) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	var response struct {
		RuntimeToken string `json:"runtimeToken"`
	}
	path := "/api/v1/runtime-hosts/" + host.ID.String() + "/credentials"
	_, err := c.request(ctx, http.MethodPost, path, map[string]any{"generation": host.LeaseGeneration}, &response)
	if err != nil {
		return err
	}
	updated, err := runtimeauth.ParseUnverified(response.RuntimeToken)
	if err != nil || updated.HostKey != current.HostKey || updated.Tenant != current.Tenant ||
		updated.Namespace != current.Namespace || updated.ExpiresAt <= current.ExpiresAt {
		return fmt.Errorf("Runtime Host renewal returned a different identity or an older expiry")
	}
	data, err := json.Marshal(hostCredentialRecord{c.bootstrapFingerprint, response.RuntimeToken})
	if err != nil {
		return err
	}
	if len(data) > 8192 {
		return fmt.Errorf("Runtime Host renewal exceeds cache limits")
	}
	file, err := os.CreateTemp(filepath.Dir(c.hostCredentialPath), ".host-credential-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	// Windows uses the state directory's inherited ACL; POSIX modes apply on Unix.
	if runtime.GOOS != "windows" {
		err = file.Chmod(0o600)
	}
	if err == nil {
		_, err = file.Write(data)
	}
	if err == nil {
		err = file.Sync()
	}
	if closed := file.Close(); err == nil {
		err = closed
	}
	if err == nil {
		err = os.Rename(file.Name(), c.hostCredentialPath)
	}
	if err != nil {
		return fmt.Errorf("persist Runtime Host credential: %w", err)
	}
	c.hostCredential.Store(response.RuntimeToken)
	return nil
}
