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

package runtimehost

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/collaboration"
	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimehost/provider"
)

type Journal struct{ Root string }

const (
	maxJournalAttempts      = 256
	maxJournalMetadataBytes = 8 << 20
	maxJournalEvents        = 65536
	maxJournalEventBytes    = 1 << 20
	maxJournalAttemptBytes  = 256 << 20
)

var journalAdmission sync.Mutex
var errJournalCleanupPending = errors.New("journal event cleanup pending")

type JournalRecord struct {
	Attempt              *controlmodel.ExecutionAttempt `json:"attempt"`
	Task                 *controlmodel.AgentTask        `json:"task"`
	Context              *collaboration.ContextEnvelope `json:"context"`
	HostID               uuid.UUID                      `json:"hostId,omitempty"`
	AttemptToken         string                         `json:"attemptToken,omitempty"`
	Workspace            string                         `json:"workspace,omitempty"`
	Events               []provider.Event               `json:"events,omitempty"`
	EventCount           int                            `json:"eventCount,omitempty"`
	EventBytes           int64                          `json:"eventBytes,omitempty"`
	FinalEventsDelivered int                            `json:"finalEventsDelivered,omitempty"`
	ProviderName         string                         `json:"providerName,omitempty"`
	DeliveryFailures     int                            `json:"deliveryFailures,omitempty"`
	NextDeliveryAt       int64                          `json:"nextDeliveryAt,omitempty"`
	PendingTerminal      *PendingTerminal               `json:"pendingTerminal,omitempty"`
	UpdatedAt            time.Time                      `json:"updatedAt"`
}

type PendingTerminal struct {
	Action         string          `json:"action"`
	Result         json.RawMessage `json:"result,omitempty"`
	Checkpoint     json.RawMessage `json:"checkpoint,omitempty"`
	FailureCode    string          `json:"failureCode,omitempty"`
	FailureMessage string          `json:"failureMessage,omitempty"`
}

func (j *Journal) path(id uuid.UUID) string {
	return filepath.Join(j.Root, "execution-attempts", id.String()+".json")
}

func (j *Journal) Save(record *JournalRecord) error {
	if record == nil || record.Attempt == nil {
		return fmt.Errorf("journal record execution is required")
	}
	path := j.path(record.Attempt.ID)
	journalAdmission.Lock()
	defer journalAdmission.Unlock()
	if _, err := os.Stat(path); os.IsNotExist(err) {
		ids, err := j.IDs()
		if err != nil {
			return err
		}
		if len(ids) >= maxJournalAttempts {
			return fmt.Errorf("runtime host journal has %d pending attempts", maxJournalAttempts)
		}
	}
	record.UpdatedAt = time.Now().UTC()
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	if len(data) > maxJournalMetadataBytes {
		return fmt.Errorf("runtime host journal metadata exceeds %d bytes", maxJournalMetadataBytes)
	}
	return writeJournalFile(path, data)
}

func (j *Journal) Remove(id uuid.UUID) error {
	dir := j.eventDir(id)
	file, err := os.Open(dir)
	if err == nil {
		entries, readErr := file.ReadDir(64)
		_ = file.Close()
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return readErr
		}
		for _, entry := range entries {
			if entry.IsDir() {
				return fmt.Errorf("unexpected directory in runtime event journal")
			}
			if err := os.Remove(filepath.Join(dir, entry.Name())); err != nil {
				return err
			}
		}
		if err := syncJournalDirectory(dir); err != nil {
			return err
		}
		if len(entries) == 64 {
			return errJournalCleanupPending
		}
		if err := os.Remove(dir); err != nil {
			return err
		}
		if err := syncJournalDirectory(filepath.Dir(dir)); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	err = os.Remove(j.path(id))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	return syncJournalDirectory(filepath.Dir(j.path(id)))
}

func (j *Journal) IDs() ([]uuid.UUID, error) {
	dir := filepath.Join(j.Root, "execution-attempts")
	file, err := os.Open(dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer file.Close()
	// There can be one temporary metadata file per active writer as well as
	// the bounded set of committed records. Never enumerate an unbounded dir.
	entries, err := file.ReadDir(2*maxJournalAttempts + 1)
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	if len(entries) > 2*maxJournalAttempts {
		return nil, fmt.Errorf("runtime host journal directory exceeds capacity")
	}
	ids := make([]uuid.UUID, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			id, err := uuid.Parse(strings.TrimSuffix(entry.Name(), ".json"))
			if err != nil {
				return nil, err
			}
			ids = append(ids, id)
		}
	}
	if len(ids) > maxJournalAttempts {
		return nil, fmt.Errorf("runtime host journal exceeds %d attempts", maxJournalAttempts)
	}
	sort.Slice(ids, func(a, b int) bool { return ids[a].String() < ids[b].String() })
	return ids, nil
}

func (j *Journal) Load(id uuid.UUID) (*JournalRecord, error) {
	data, err := readJournalFile(j.path(id), maxJournalMetadataBytes)
	if err != nil {
		return nil, err
	}
	var record JournalRecord
	if err := json.Unmarshal(data, &record); err != nil {
		return nil, err
	}
	if record.Attempt == nil || record.Attempt.ID != id || record.EventCount < 0 || record.EventCount > maxJournalEvents || record.EventBytes < 0 || record.EventBytes > maxJournalAttemptBytes || record.FinalEventsDelivered < 0 || record.FinalEventsDelivered > journalEventCount(&record) {
		return nil, fmt.Errorf("invalid runtime host journal record")
	}
	return &record, nil
}

// List is used by diagnostics and fixtures; the daemon loads at most eight
// records per polling pass through IDs and Load.
func (j *Journal) List() ([]*JournalRecord, error) {
	ids, err := j.IDs()
	if err != nil {
		return nil, err
	}
	records := make([]*JournalRecord, 0, len(ids))
	for _, id := range ids {
		record, err := j.Load(id)
		if err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, nil
}

func (j *Journal) eventDir(id uuid.UUID) string {
	return filepath.Join(j.Root, "event-streams", id.String())
}

func (j *Journal) AppendEvent(record *JournalRecord, event provider.Event) (int64, error) {
	if record == nil || record.Attempt == nil || len(record.Events) > 0 {
		return 0, fmt.Errorf("append requires a file-backed journal record")
	}
	data, err := json.Marshal(event)
	if err != nil {
		return 0, err
	}
	if len(data) > maxJournalEventBytes || record.EventCount >= maxJournalEvents || record.EventBytes+int64(len(data)) > maxJournalAttemptBytes {
		return 0, fmt.Errorf("runtime provider event journal exceeds capacity")
	}
	ordinal := record.EventCount + 1
	if err := writeJournalFile(filepath.Join(j.eventDir(record.Attempt.ID), fmt.Sprintf("%08d.json", ordinal)), data); err != nil {
		return 0, err
	}
	record.EventCount, record.EventBytes = ordinal, record.EventBytes+int64(len(data))
	if err := j.Save(record); err != nil {
		return 0, err
	}
	return int64(ordinal), nil
}

func (j *Journal) Event(record *JournalRecord, offset int) (provider.Event, error) {
	if offset < 0 || offset >= journalEventCount(record) {
		return provider.Event{}, fmt.Errorf("invalid journal event offset")
	}
	if record.EventCount == 0 {
		return record.Events[offset], nil
	}
	data, err := readJournalFile(filepath.Join(j.eventDir(record.Attempt.ID), fmt.Sprintf("%08d.json", offset+1)), maxJournalEventBytes)
	if err != nil {
		return provider.Event{}, err
	}
	var event provider.Event
	err = json.Unmarshal(data, &event)
	return event, err
}

func journalEventCount(record *JournalRecord) int {
	if record.EventCount > 0 {
		return record.EventCount
	}
	return len(record.Events)
}

func readJournalFile(path string, limit int64) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if len(data) > int(limit) {
		return nil, fmt.Errorf("runtime journal file exceeds capacity")
	}
	return data, err
}

func writeJournalFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	file, err := os.CreateTemp(dir, ".journal-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err = file.Write(data); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(file.Name(), path); err != nil {
		return err
	}
	if err := syncJournalDirectory(dir); err != nil {
		return err
	}
	return syncJournalDirectory(filepath.Dir(dir))
}

func syncJournalDirectory(path string) error {
	if runtime.GOOS == "windows" {
		return nil // Windows does not support fsync on directory handles.
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}
