package asdp

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"sync"
)

// ConfigSnapshot holds a versioned configuration snapshot for an agent.
type ConfigSnapshot struct {
	CfgType   ConfigType `json:"configType"`
	Version   string     `json:"version"`
	Resources []byte     `json:"resources"`
	Nonce     string     `json:"nonce"`
}

// SnapshotStore manages configuration snapshots and version tracking per agent.
type SnapshotStore struct {
	mu        sync.RWMutex
	snapshots map[string]map[ConfigType]*ConfigSnapshot
	versions  map[string]map[ConfigType]int64
}

// NewSnapshotStore creates a new SnapshotStore.
func NewSnapshotStore() *SnapshotStore {
	return &SnapshotStore{
		snapshots: make(map[string]map[ConfigType]*ConfigSnapshot),
		versions:  make(map[string]map[ConfigType]int64),
	}
}

// UpdateSnapshot stores a new config snapshot for an agent, incrementing the version.
func (s *SnapshotStore) UpdateSnapshot(namespace, agentName string, cfgType ConfigType, resources interface{}) (*ConfigSnapshot, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	agentKey := fmt.Sprintf("%s/%s", namespace, agentName)

	data, err := json.Marshal(resources)
	if err != nil {
		return nil, false, fmt.Errorf("marshaling resources: %w", err)
	}

	if existing := s.getSnapshotLocked(agentKey, cfgType); existing != nil {
		if existing.Resources != nil && contentHash(existing.Resources) == contentHash(data) {
			return existing, false, nil
		}
	}

	if s.snapshots[agentKey] == nil {
		s.snapshots[agentKey] = make(map[ConfigType]*ConfigSnapshot)
	}
	if s.versions[agentKey] == nil {
		s.versions[agentKey] = make(map[ConfigType]int64)
	}

	s.versions[agentKey][cfgType]++
	ver := s.versions[agentKey][cfgType]

	snapshot := &ConfigSnapshot{
		CfgType:   cfgType,
		Version:   fmt.Sprintf("v%d", ver),
		Resources: data,
		Nonce:     generateNonce(agentKey, cfgType, ver),
	}

	s.snapshots[agentKey][cfgType] = snapshot
	return snapshot, true, nil
}

// GetSnapshot retrieves the current snapshot for an agent and config type.
func (s *SnapshotStore) GetSnapshot(namespace, agentName string, cfgType ConfigType) *ConfigSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	agentKey := fmt.Sprintf("%s/%s", namespace, agentName)
	return s.getSnapshotLocked(agentKey, cfgType)
}

// GetAllSnapshots returns all current snapshots for an agent.
func (s *SnapshotStore) GetAllSnapshots(namespace, agentName string) []*ConfigSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	agentKey := fmt.Sprintf("%s/%s", namespace, agentName)
	agentSnapshots := s.snapshots[agentKey]
	if agentSnapshots == nil {
		return nil
	}

	result := make([]*ConfigSnapshot, 0, len(agentSnapshots))
	for _, snap := range agentSnapshots {
		result = append(result, snap)
	}
	return result
}

// DeleteAgent removes all snapshots for an agent.
func (s *SnapshotStore) DeleteAgent(namespace, agentName string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	agentKey := fmt.Sprintf("%s/%s", namespace, agentName)
	delete(s.snapshots, agentKey)
	delete(s.versions, agentKey)
}

func (s *SnapshotStore) getSnapshotLocked(agentKey string, cfgType ConfigType) *ConfigSnapshot {
	agentSnapshots := s.snapshots[agentKey]
	if agentSnapshots == nil {
		return nil
	}
	return agentSnapshots[cfgType]
}

func contentHash(data []byte) string {
	h := sha256.Sum256(data)
	return fmt.Sprintf("%x", h[:8])
}

func generateNonce(agentKey string, cfgType ConfigType, version int64) string {
	data := fmt.Sprintf("%s/%d/%d", agentKey, cfgType, version)
	h := sha256.Sum256([]byte(data))
	return fmt.Sprintf("%x", h[:8])
}
