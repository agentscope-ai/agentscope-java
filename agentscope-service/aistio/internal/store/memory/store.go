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

package memory

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func init() {
	store.RegisterOpener(store.DriverMemory, Open)
}

// Store is an in-memory store.Store used for local/dev and unit tests.
type Store struct {
	accessNamespaces     map[string]*controlmodel.Namespace
	accessNamespaceAudit []*controlmodel.NamespaceAudit
	mu                   sync.RWMutex
	sessionLocks         *keyedMutex
	sessions             map[uuid.UUID]*store.Session
	sessKey              map[string]uuid.UUID // agent/ns/sessionID -> uuid
	snapshots            []store.SessionSnapshot
	events               []store.SessionEvent
	eventSignals         map[uuid.UUID]chan struct{}
	contexts             []store.ContextSnapshot
	tokens               []store.TokenUsageMetric
	agents               []store.AgentMetric
	commands             []store.SessionCommand
	turns                []store.SessionTurn
	transcriptIndex      map[uuid.UUID]store.SessionTranscriptIndex

	// Hosted DistributedStore backends.
	kv          map[string]*store.KVItem // tenant+\x00+nsPath+\x00+itemKey
	locks       map[string]*store.Lock   // tenant+\x00+lockName
	dpSnapshots map[string]*memSnapshot  // tenant+\x00+snapshotID
	busEntries  []memBusEntry
	asyncTools  map[string]*store.AsyncToolRecord // tenant+\x00+recordID
	dpTasks     map[string]*store.DPTask          // tenant+\x00+parentAgent+\x00+session+\x00+taskID

	// Control-plane registry, task, execution, and outbox authority.
	logicalAgents         map[uuid.UUID]*controlmodel.Agent
	agentBindings         map[uuid.UUID]*controlmodel.AgentBinding
	agentCredentials      map[uuid.UUID]*controlmodel.AgentRegistrationCredential
	agentInstances        map[uuid.UUID]*controlmodel.AgentInstance
	runtimeProfiles       map[string]*controlmodel.RuntimeProfile
	runtimePools          map[string]*controlmodel.RuntimePool
	runtimeHosts          map[uuid.UUID]*controlmodel.RuntimeHost
	executions            map[uuid.UUID]*controlmodel.ExecutionAttempt
	definitions           map[uuid.UUID]*controlmodel.OrchestrationDefinition
	revisions             map[uuid.UUID]*controlmodel.OrchestrationRevision
	runs                  map[uuid.UUID]*controlmodel.OrchestrationRun
	runNodes              map[uuid.UUID]*controlmodel.RunNode
	runEdges              map[uuid.UUID]*controlmodel.RunEdge
	runSnapshots          map[string]*controlmodel.RunTeamSnapshot
	runEvents             map[uuid.UUID][]*controlmodel.RunEvent
	runEventSignals       map[uuid.UUID]chan struct{}
	runtimePolicies       map[string]*controlmodel.AgentRuntimePolicy
	outboxEvents          map[uuid.UUID]*controlmodel.OutboxEvent
	issues                map[uuid.UUID]*controlmodel.Issue
	comments              map[uuid.UUID]*controlmodel.Comment
	commentMentions       map[uuid.UUID][]controlmodel.Mention
	commentRoutes         map[uuid.UUID][]controlmodel.CommentRoute
	agentTasks            map[uuid.UUID]*controlmodel.AgentTask
	taskInputs            map[uuid.UUID][]controlmodel.AgentTaskInput
	collabTeams           map[uuid.UUID]*controlmodel.CollaborationTeam
	collabMembers         map[uuid.UUID][]controlmodel.CollaborationTeamMember
	artifacts             map[uuid.UUID]*controlmodel.Artifact
	artifactLinks         map[uuid.UUID][]controlmodel.ArtifactLink
	subscribers           map[uuid.UUID][]controlmodel.IssueSubscriber
	approvals             map[uuid.UUID]*controlmodel.Approval
	inboxItems            map[uuid.UUID]*controlmodel.InboxItem
	activities            []controlmodel.Activity
	automationDeliveries  map[uuid.UUID]*controlmodel.AutomationDelivery
	automations           map[uuid.UUID]*controlmodel.Automation
	automationRuns        map[uuid.UUID]*controlmodel.AutomationRun
	workSources           map[uuid.UUID]*controlmodel.WorkSource
	webhookDeliveries     map[uuid.UUID]*controlmodel.WebhookDelivery
	issueExternalRefs     map[string]*controlmodel.IssueExternalRef
	commentExternalRefs   map[uuid.UUID]*controlmodel.CommentExternalRef
	externalLinks         map[uuid.UUID]*controlmodel.ExternalLink
	endpoints             map[uuid.UUID]*controlmodel.Endpoint
	endpointReleases      map[uuid.UUID]*controlmodel.EndpointRelease
	endpointCredentials   map[uuid.UUID]*controlmodel.EndpointCredential
	endpointInvocations   map[uuid.UUID]*controlmodel.EndpointInvocation
	endpointConversations map[uuid.UUID]*controlmodel.EndpointConversation
	endpointRateWindows   map[string]*endpointRateWindow
	teamProposals         map[uuid.UUID]*controlmodel.TeamProposal
	chats                 map[uuid.UUID]*controlmodel.Chat
	nextBusID             int64
	nextFencing           int64

	nextSnapID int64
	nextEvtID  int64
	nextCtxID  int64
	nextTokID  int64
	nextAgID   int64

	retention store.RetentionConfig
}

type memSnapshot struct {
	meta    store.SnapshotMeta
	payload []byte
}

type memBusEntry struct {
	id      int64
	tenant  string
	key     string
	kind    int16
	payload []byte
	created time.Time
}

// Open creates a memory store.
func Open(_ context.Context, cfg store.Config) (store.Store, error) {
	s := &Store{
		sessions:              make(map[uuid.UUID]*store.Session),
		sessKey:               make(map[string]uuid.UUID),
		eventSignals:          make(map[uuid.UUID]chan struct{}),
		sessionLocks:          newKeyedMutex(),
		kv:                    make(map[string]*store.KVItem),
		locks:                 make(map[string]*store.Lock),
		dpSnapshots:           make(map[string]*memSnapshot),
		asyncTools:            make(map[string]*store.AsyncToolRecord),
		dpTasks:               make(map[string]*store.DPTask),
		logicalAgents:         make(map[uuid.UUID]*controlmodel.Agent),
		agentBindings:         make(map[uuid.UUID]*controlmodel.AgentBinding),
		agentCredentials:      make(map[uuid.UUID]*controlmodel.AgentRegistrationCredential),
		agentInstances:        make(map[uuid.UUID]*controlmodel.AgentInstance),
		runtimeProfiles:       make(map[string]*controlmodel.RuntimeProfile),
		runtimePools:          make(map[string]*controlmodel.RuntimePool),
		runtimeHosts:          make(map[uuid.UUID]*controlmodel.RuntimeHost),
		executions:            make(map[uuid.UUID]*controlmodel.ExecutionAttempt),
		definitions:           make(map[uuid.UUID]*controlmodel.OrchestrationDefinition),
		revisions:             make(map[uuid.UUID]*controlmodel.OrchestrationRevision),
		runs:                  make(map[uuid.UUID]*controlmodel.OrchestrationRun),
		runNodes:              make(map[uuid.UUID]*controlmodel.RunNode),
		runEdges:              make(map[uuid.UUID]*controlmodel.RunEdge),
		runSnapshots:          make(map[string]*controlmodel.RunTeamSnapshot),
		runEvents:             make(map[uuid.UUID][]*controlmodel.RunEvent),
		runEventSignals:       make(map[uuid.UUID]chan struct{}),
		runtimePolicies:       make(map[string]*controlmodel.AgentRuntimePolicy),
		outboxEvents:          make(map[uuid.UUID]*controlmodel.OutboxEvent),
		issues:                make(map[uuid.UUID]*controlmodel.Issue),
		comments:              make(map[uuid.UUID]*controlmodel.Comment),
		commentMentions:       make(map[uuid.UUID][]controlmodel.Mention),
		commentRoutes:         make(map[uuid.UUID][]controlmodel.CommentRoute),
		agentTasks:            make(map[uuid.UUID]*controlmodel.AgentTask),
		taskInputs:            make(map[uuid.UUID][]controlmodel.AgentTaskInput),
		collabTeams:           make(map[uuid.UUID]*controlmodel.CollaborationTeam),
		collabMembers:         make(map[uuid.UUID][]controlmodel.CollaborationTeamMember),
		artifacts:             make(map[uuid.UUID]*controlmodel.Artifact),
		artifactLinks:         make(map[uuid.UUID][]controlmodel.ArtifactLink),
		subscribers:           make(map[uuid.UUID][]controlmodel.IssueSubscriber),
		approvals:             make(map[uuid.UUID]*controlmodel.Approval),
		inboxItems:            make(map[uuid.UUID]*controlmodel.InboxItem),
		automationDeliveries:  make(map[uuid.UUID]*controlmodel.AutomationDelivery),
		automations:           make(map[uuid.UUID]*controlmodel.Automation),
		automationRuns:        make(map[uuid.UUID]*controlmodel.AutomationRun),
		workSources:           make(map[uuid.UUID]*controlmodel.WorkSource),
		webhookDeliveries:     make(map[uuid.UUID]*controlmodel.WebhookDelivery),
		issueExternalRefs:     make(map[string]*controlmodel.IssueExternalRef),
		commentExternalRefs:   make(map[uuid.UUID]*controlmodel.CommentExternalRef),
		externalLinks:         make(map[uuid.UUID]*controlmodel.ExternalLink),
		endpoints:             make(map[uuid.UUID]*controlmodel.Endpoint),
		endpointReleases:      make(map[uuid.UUID]*controlmodel.EndpointRelease),
		endpointCredentials:   make(map[uuid.UUID]*controlmodel.EndpointCredential),
		endpointInvocations:   make(map[uuid.UUID]*controlmodel.EndpointInvocation),
		endpointConversations: make(map[uuid.UUID]*controlmodel.EndpointConversation),
		endpointRateWindows:   make(map[string]*endpointRateWindow),
		teamProposals:         make(map[uuid.UUID]*controlmodel.TeamProposal),
		chats:                 make(map[uuid.UUID]*controlmodel.Chat),
		retention:             cfg.Retention,
	}
	return s, nil
}

func (s *Store) Sessions() store.SessionRepository                 { return &sessionRepo{s} }
func (s *Store) Turns() store.TurnRepository                       { return &turnRepo{s} }
func (s *Store) Events() store.EventRepository                     { return &eventRepo{s} }
func (s *Store) ContextSnapshots() store.ContextSnapshotRepository { return &contextRepo{s} }
func (s *Store) Metrics() store.MetricsRepository                  { return &metricsRepo{s} }
func (s *Store) TranscriptIndex() store.TranscriptIndexRepository  { return &transcriptIndexRepo{s} }
func (s *Store) Commands() store.SessionCommandRepository          { return &commandRepo{s} }
func (s *Store) AgentCatalog() store.AgentCatalogRepository        { return &agentCatalogRepo{s} }
func (s *Store) RuntimeRegistry() store.RuntimeRegistryRepository  { return &controlPlaneRepo{s} }
func (s *Store) ExecutionAttempts() store.ExecutionAttemptRepository {
	return &executionRepo{controlPlaneRepo: &controlPlaneRepo{s}}
}
func (s *Store) Orchestration() store.OrchestrationRepository { return &orchestrationRepo{s} }
func (s *Store) Outbox() store.OutboxRepository {
	return &outboxRepo{controlPlaneRepo: &controlPlaneRepo{s}}
}
func (s *Store) Collaboration() store.CollaborationRepository { return &collaborationRepo{s} }
func (s *Store) WorkSources() store.WorkSourceRepository      { return &workSourceRepo{s} }
func (s *Store) Endpoints() store.EndpointRepository          { return &endpointRepo{s} }
func (s *Store) TeamProposals() store.TeamProposalRepository  { return &teamProposalRepo{s} }
func (s *Store) Chats() store.ChatRepository                  { return &chatRepo{s} }
func (s *Store) KV() store.KVRepository                       { return &kvRepo{s} }
func (s *Store) Locks() store.LockRepository                  { return &lockRepo{s} }
func (s *Store) Snapshots() store.SnapshotRepository          { return &snapshotRepo{s} }
func (s *Store) Bus() store.BusRepository                     { return &busRepo{s} }
func (s *Store) AsyncTools() store.AsyncToolRepository        { return &asyncToolRepo{s} }
func (s *Store) DPTasks() store.DPTaskRepository              { return &dpTaskRepo{s} }

func (s *Store) Migrate(context.Context) error { return nil }
func (s *Store) Ping(context.Context) error    { return nil }
func (s *Store) Close() error                  { return nil }

// WithSessionLock serializes fn per sessionKey within this process.
func (s *Store) WithSessionLock(ctx context.Context, sessionKey string, fn func(context.Context) error) error {
	if fn == nil {
		return nil
	}
	if s.sessionLocks == nil {
		s.sessionLocks = newKeyedMutex()
	}
	unlock := s.sessionLocks.Lock(sessionKey)
	defer unlock()
	return fn(ctx)
}

func (s *Store) PurgeOlderThan(_ context.Context, r store.RetentionConfig) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	var n int64
	if r.SessionEvents > 0 {
		cut := now.Add(-r.SessionEvents)
		kept := s.events[:0]
		for _, e := range s.events {
			if e.OccurredAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.events = kept
	}
	if r.Snapshots > 0 {
		cut := now.Add(-r.Snapshots)
		kept := s.snapshots[:0]
		for _, e := range s.snapshots {
			if e.CapturedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.snapshots = kept
	}
	if r.ContextSnapshots > 0 {
		cut := now.Add(-r.ContextSnapshots)
		kept := s.contexts[:0]
		for _, e := range s.contexts {
			if e.CapturedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.contexts = kept
	}
	if r.Metrics > 0 {
		cut := now.Add(-r.Metrics)
		kept := s.tokens[:0]
		for _, e := range s.tokens {
			if e.RecordedAt.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.tokens = kept
		keptA := s.agents[:0]
		for _, e := range s.agents {
			if e.RecordedAt.Before(cut) {
				n++
				continue
			}
			keptA = append(keptA, e)
		}
		s.agents = keptA
	}
	// Hosted store retention — dp_kv is NEVER purged.
	if r.BusQueue > 0 || r.BusLog > 0 {
		kept := s.busEntries[:0]
		for _, e := range s.busEntries {
			var cut time.Time
			switch e.kind {
			case store.BusKindQueue:
				if r.BusQueue > 0 {
					cut = now.Add(-r.BusQueue)
				}
			case store.BusKindLog:
				if r.BusLog > 0 {
					cut = now.Add(-r.BusLog)
				}
			}
			if !cut.IsZero() && e.created.Before(cut) {
				n++
				continue
			}
			kept = append(kept, e)
		}
		s.busEntries = kept
	}
	if r.AsyncTools > 0 {
		cut := now.Add(-r.AsyncTools)
		for k, rec := range s.asyncTools {
			if rec.UpdatedAt.Before(cut) {
				delete(s.asyncTools, k)
				n++
			}
		}
	}
	if r.SandboxSnapshots > 0 {
		cut := now.Add(-r.SandboxSnapshots)
		for k, snap := range s.dpSnapshots {
			if snap.meta.AccessedAt.Before(cut) {
				delete(s.dpSnapshots, k)
				n++
			}
		}
	}
	if r.Tasks > 0 {
		cut := now.Add(-r.Tasks)
		for k, t := range s.dpTasks {
			if t.Terminal && t.LastUpdatedAt.Before(cut) {
				delete(s.dpTasks, k)
				n++
			}
		}
	}
	// Drop expired locks that have been expired for more than 1h.
	for k, lk := range s.locks {
		if lk.ExpiresAt.Before(now.Add(-time.Hour)) {
			delete(s.locks, k)
			n++
		}
	}
	return n, nil
}

func sessCompositeKey(tenant, agent, ns, sid string) string {
	return tenant + "\x00" + agent + "\x00" + ns + "\x00" + sid
}

func cloneSession(s *store.Session) *store.Session {
	c := *s
	if s.TaskContext != nil {
		c.TaskContext = append([]byte(nil), s.TaskContext...)
	}
	if s.AgentTaskID != nil {
		id := *s.AgentTaskID
		c.AgentTaskID = &id
	}
	if s.Busy != nil {
		b := *s.Busy
		c.Busy = &b
	}
	return &c
}

func nextID(counter *int64) int64 {
	return atomic.AddInt64(counter, 1)
}
