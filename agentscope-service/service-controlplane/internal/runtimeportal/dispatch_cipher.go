// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package runtimeportal

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
)

// DispatchCodec keeps native task, Attempt and collaboration outbox payloads from
// persisting a second plaintext copy of the frozen provider configuration.
type DispatchCodec interface {
	ProtectDispatch(string, model.RuntimeDispatchSnapshot) (model.RuntimeDispatchSnapshot, error)
	RevealDispatch(string, model.RuntimeDispatchSnapshot) (model.RuntimeDispatchSnapshot, error)
}

func dispatchIdentity(task string, value model.RuntimeDispatchSnapshot) (string, error) {
	if task == "" {
		return "", fmt.Errorf("dispatch task identity is required")
	}
	// Bind stable typed identities rather than JSONB formatting of raw capability maps.
	// PostgreSQL may reorder keys or normalize numeric literals in those query fields.
	identity := struct {
		Task                                string
		Binding                             model.RuntimeBinding
		Portal, Session, Source, ResolvedAt string
		Candidate                           int32
		Profile, Pool, Provider             string
		ProfileVersion, PoolVersion         int64
		CipherVersion                       int
	}{Task: task, Binding: value.Binding, Portal: value.PortalCommandID, Session: value.SessionID,
		Source: value.SelectionSource, ResolvedAt: value.ResolvedAt.UTC().Format("2006-01-02T15:04:05.999999999Z07:00"),
		Candidate: value.CandidateIndex, CipherVersion: value.SnapshotCipherVersion}
	identity.Binding.ExecutionOverrides = nil
	if value.RuntimeProfile != nil {
		identity.Profile = value.RuntimeProfile.ID.String()
		identity.Provider = value.RuntimeProfile.Provider
		identity.ProfileVersion = value.RuntimeProfile.Version
	}
	if value.RuntimePool != nil {
		identity.Pool = value.RuntimePool.ID.String()
		identity.PoolVersion = value.RuntimePool.Version
	}
	raw, err := json.Marshal(identity)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(raw)
	return "dispatch:" + task + ":" + hex.EncodeToString(digest[:]), nil
}

func (c *Cipher) ProtectDispatch(task string, value model.RuntimeDispatchSnapshot) (model.RuntimeDispatchSnapshot, error) {
	if value.SnapshotCipherVersion != 0 || len(value.SealedConfiguration) != 0 {
		return model.RuntimeDispatchSnapshot{}, fmt.Errorf("dispatch configuration is already sealed")
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return model.RuntimeDispatchSnapshot{}, err
	}
	if len(raw) > 8*1024*1024 {
		return model.RuntimeDispatchSnapshot{}, fmt.Errorf("dispatch configuration exceeds limit")
	}
	// Clone before removing fields so the shared RuntimeProfile and binding remain immutable.
	var metadata model.RuntimeDispatchSnapshot
	if err = json.Unmarshal(raw, &metadata); err != nil {
		return metadata, err
	}
	metadata.Definition = nil
	metadata.ResolvedProviderConfiguration = nil
	metadata.ExecutionOverrides = nil
	metadata.Binding.ExecutionOverrides = nil
	if metadata.RuntimeProfile != nil {
		metadata.RuntimeProfile.Configuration = nil
	}
	metadata.SnapshotCipherVersion = 1
	identity, err := dispatchIdentity(task, metadata)
	if err != nil {
		return model.RuntimeDispatchSnapshot{}, err
	}
	metadata.SealedConfiguration, err = c.encode(identity, value)
	return metadata, err
}

func (c *Cipher) RevealDispatch(task string, value model.RuntimeDispatchSnapshot) (model.RuntimeDispatchSnapshot, error) {
	// Non-Portal legacy records without encrypted fields remain readable by their existing path.
	if value.SnapshotCipherVersion == 0 && len(value.SealedConfiguration) == 0 {
		if value.PortalCommandID != "" && value.Binding.Kind == model.DataPlaneHostedRuntime {
			return model.RuntimeDispatchSnapshot{}, fmt.Errorf("Portal dispatch configuration must be sealed")
		}
		return value, nil
	}
	if value.SnapshotCipherVersion != 1 || len(value.SealedConfiguration) == 0 || len(value.SealedConfiguration) > 8*1024*1024+128 {
		return model.RuntimeDispatchSnapshot{}, fmt.Errorf("unsupported dispatch ciphertext")
	}
	identity, err := dispatchIdentity(task, value)
	if err != nil {
		return model.RuntimeDispatchSnapshot{}, err
	}
	var original model.RuntimeDispatchSnapshot
	if err = c.decode(identity, value.SealedConfiguration, &original); err != nil {
		return original, err
	}
	return original, nil
}
