// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/sync/errgroup"
	ctrl "sigs.k8s.io/controller-runtime"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/channels"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/cronexpr"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

type schedulerRuntime struct {
	server       *Server
	replyTimeout time.Duration
	pollInterval time.Duration
	api          http.Handler
	mu           sync.Mutex
	providers    map[string]*channels.Provider
	streams      map[string]*channelStream
	group        sync.WaitGroup
}
type channelStream struct {
	provider *channels.Provider
	cancel   context.CancelFunc
	token    string
	done     chan struct{}
}

func newSchedulerRuntime(s *Server) *schedulerRuntime {
	api := gin.New()
	// Calls stay within the process and use exactly the existing authority checks.
	api.POST("/inbound", s.receiveChannelInbound)
	api.POST("/deliveries/claim", s.claimChannelDelivery)
	api.POST("/deliveries/:deliveryId/receipt", s.channelDeliveryReceipt)
	return &schedulerRuntime{server: s, replyTimeout: 120 * time.Second, pollInterval: time.Second, api: api, providers: map[string]*channels.Provider{}, streams: map[string]*channelStream{}}
}

type localResponse struct {
	header http.Header
	body   bytes.Buffer
	status int
}

func (r *localResponse) Header() http.Header { return r.header }
func (r *localResponse) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
}
func (r *localResponse) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = 200
	}
	if r.body.Len()+len(b) > 2<<20 {
		return 0, fmt.Errorf("internal response exceeds limit")
	}
	return r.body.Write(b)
}
func (s *schedulerRuntime) call(ctx context.Context, path string, input, out any) error {
	raw, err := json.Marshal(input)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, "POST", "http://internal"+path, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	response := &localResponse{header: http.Header{}}
	s.api.ServeHTTP(response, req)
	if response.status == 204 {
		return pgx.ErrNoRows
	}
	if response.status/100 != 2 {
		return fmt.Errorf("internal scheduler operation %s returned %d", path, response.status)
	}
	if out != nil && json.Unmarshal(response.body.Bytes(), out) != nil {
		return fmt.Errorf("invalid internal scheduler response")
	}
	return nil
}
func (s *schedulerRuntime) provider(ctx context.Context, id string) (*channels.Provider, error) {
	ch, err := s.server.loadChannel(ctx, id)
	if err != nil {
		return nil, err
	}
	if ch.Disabled {
		return nil, store.ErrForbidden
	}
	cfg := channels.Config{Type: ch.Type, Properties: propsAsMap(parseJSONRaw(deref(ch.PropertiesJSON)))}
	fingerprint := mustJSON(cfg)
	s.mu.Lock()
	defer s.mu.Unlock()
	if p := s.providers[id]; p != nil && mustJSON(p.Config) == fingerprint {
		return p, nil
	}
	p, err := channels.New(id, cfg, nil)
	if err != nil {
		return nil, err
	}
	s.providers[id] = p
	return p, nil
}

// RegisterChannelTransports mounts unauthenticated provider callbacks separately
// from account APIs. Each provider verifies its own credential before intake.
func (s *Server) RegisterChannelTransports(r gin.IRouter) {
	if s.scheduler == nil {
		return
	}
	for _, typ := range []string{"dingtalk", "feishu", "wecom", "github", "gitlab"} {
		callback := func(c *gin.Context) {
			provider, err := s.scheduler.provider(c.Request.Context(), c.Param("channelId"))
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) || errors.Is(err, store.ErrForbidden) {
					c.Status(404)
				} else {
					c.Status(503)
				}
				return
			}
			if provider.Config.Type != typ {
				c.Status(404)
				return
			}
			provider.Callback(c.Writer, c.Request, s.scheduler.accept)
		}
		path := "/api/channels/" + typ + "/:channelId/"
		if typ == "github" || typ == "gitlab" {
			r.POST(path+"webhook", callback)
		} else {
			r.POST(path+"callback", callback)
			if typ == "wecom" {
				r.GET(path+"callback", callback)
			}
		}
	}
	r.POST("/api/outbound", s.scheduler.outbound)
}

// RunScheduler runs bounded worker pools on every replica. The database owns
// intake, work claims, stream ownership and retry coordinates.
func (s *Server) RunScheduler(ctx context.Context) error {
	if s.scheduler == nil {
		return nil
	}
	return s.scheduler.run(ctx)
}
func schedulerSlots(key string, fallback int) (int, error) {
	v := os.Getenv(key)
	if v == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 1 || n > 256 {
		return 0, fmt.Errorf("%s must be between 1 and 256", key)
	}
	return n, nil
}
func schedulerMilliseconds(key string, fallback int) (time.Duration, error) {
	raw := os.Getenv(key)
	if raw == "" {
		return time.Duration(fallback) * time.Millisecond, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < 1 || value > (1<<63-1)/int64(time.Millisecond) {
		return 0, fmt.Errorf("%s must be positive milliseconds within the supported duration range", key)
	}
	return time.Duration(value) * time.Millisecond, nil
}

func (s *schedulerRuntime) run(ctx context.Context) error {
	var err error
	s.replyTimeout, err = schedulerMilliseconds("MANAGED_AGENTS_CHANNEL_REPLY_TIMEOUT_MS", 120000)
	if err != nil {
		return err
	}
	s.pollInterval, err = schedulerMilliseconds("MANAGED_AGENTS_CHANNEL_POLL_INTERVAL_MS", 1000)
	if err != nil {
		return err
	}

	slots, err := schedulerSlots("MANAGED_AGENTS_CHANNEL_SLOTS", 8)
	if err != nil {
		return err
	}
	webhooks, err := newAgentServiceWebhookWorker(s.server.cfg.AgentServiceURL, s.server.cfg.InternalToken)
	if err != nil {
		return err
	}
	defer webhooks.close()
	group, runCtx := errgroup.WithContext(ctx)
	report := func(name string, err error) {
		if err != nil && !errors.Is(err, pgx.ErrNoRows) && runCtx.Err() == nil {
			ctrl.Log.Error(err, "integrated scheduler", "operation", name)
		}
	}
	periodic := func(name string, interval time.Duration, step func(context.Context) error) {
		group.Go(func() error {
			ticker := time.NewTicker(interval)
			defer ticker.Stop()
			for {
				stepCtx, cancel := context.WithTimeout(runCtx, 20*time.Second)
				err := step(stepCtx)
				cancel()
				report(name, err)
				select {
				case <-runCtx.Done():
					return nil
				case <-ticker.C:
				}
			}
		})
	}
	periodic("cron", time.Minute, s.scanCron)
	// Stream contexts must outlive a single refresh timeout.
	periodic("channels", 5*time.Second, func(ctx context.Context) error { return s.refreshStreams(ctx, runCtx) })
	for range slots {
		group.Go(func() error {
			for runCtx.Err() == nil {
				stepCtx, cancel := context.WithTimeout(runCtx, 20*time.Second)
				err := s.advanceJob(stepCtx)
				cancel()
				report("channel-message", err)
				if err != nil {
					select {
					case <-runCtx.Done():
						return nil
					case <-time.After(s.pollInterval):
					}
				}
			}
			return nil
		})
	}
	periodic("channel-delivery", time.Second, s.deliverWork)
	group.Go(func() error { return webhooks.run(runCtx) })
	periodic("retention", time.Hour, func(ctx context.Context) error {
		if _, err := s.server.db.Pool.Exec(ctx, `DELETE FROM scheduler_jobs WHERE state IN ('done','failed') AND updated_at<now()-interval '7 days'`); err != nil {
			return err
		}
		_, err := s.server.db.Pool.Exec(ctx, `DELETE FROM scheduler_channel_throttle WHERE updated_at<now()-interval '2 minutes'`)
		return err
	})
	err = group.Wait()
	s.mu.Lock()
	for _, stream := range s.streams {
		stream.cancel()
	}
	s.mu.Unlock()
	s.group.Wait()
	return err
}
func (s *schedulerRuntime) refreshStreams(ctx, runCtx context.Context) error {
	rows, err := s.server.db.Pool.Query(ctx, channelSelect+` WHERE disabled=false ORDER BY channel_id`)
	if err != nil {
		return err
	}
	ids := []string{}
	types := map[string]string{}
	for rows.Next() {
		ch, e := s.server.scanChannel(rows)
		if e != nil {
			rows.Close()
			return e
		}
		ids = append(ids, ch.ChannelID)
		types[ch.ChannelID] = ch.Type
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	present := map[string]bool{}
	for _, id := range ids {
		p, e := s.provider(ctx, id)
		if e != nil {
			_, _ = s.server.db.Pool.Exec(ctx, `UPDATE channels SET runtime_started=false,runtime_error='Channel configuration invalid',runtime_updated_at=$2 WHERE channel_id=$1`, id, nowMillis())
			continue
		}
		present[id] = true
		streamMode := types[id] == "dingtalk" && p.Property("mode") != "http"
		if !streamMode {
			_, e = s.server.db.Pool.Exec(ctx, `UPDATE channels SET runtime_started=true,runtime_error=NULL,runtime_updated_at=$2 WHERE channel_id=$1 AND disabled=false`, id, nowMillis())
			if e != nil {
				return e
			}
		}
		s.mu.Lock()
		old := s.streams[id]
		s.mu.Unlock()
		if old != nil && old.provider != p {
			old.cancel()
			<-old.done
			s.mu.Lock()
			delete(s.streams, id)
			s.mu.Unlock()
			old = nil
		}
		if !streamMode {
			continue
		}
		if old != nil {
			select {
			case <-old.done:
				s.mu.Lock()
				delete(s.streams, id)
				s.mu.Unlock()
			default:
				continue
			}
		}
		token := uuid.NewString()
		var acquired string
		e = s.server.db.Pool.QueryRow(ctx, `INSERT INTO scheduler_stream_leases(channel_id,token,lease_until) VALUES($1,$2,now()+interval '20 seconds') ON CONFLICT(channel_id) DO UPDATE SET token=$2,lease_until=now()+interval '20 seconds' WHERE scheduler_stream_leases.lease_until<=now() RETURNING token`, id, token).Scan(&acquired)
		if errors.Is(e, pgx.ErrNoRows) {
			continue
		}
		if e != nil {
			return e
		}
		streamCtx, cancel := context.WithCancel(runCtx)
		stream := &channelStream{provider: p, cancel: cancel, token: token, done: make(chan struct{})}
		s.mu.Lock()
		s.streams[id] = stream
		s.mu.Unlock()
		s.group.Add(1)
		go func() { defer s.group.Done(); defer close(stream.done); defer cancel(); s.runStream(streamCtx, stream) }()
	}
	s.mu.Lock()
	for id, stream := range s.streams {
		if !present[id] {
			stream.cancel()
			delete(s.streams, id)
		}
	}
	for id := range s.providers {
		if !present[id] {
			delete(s.providers, id)
		}
	}
	s.mu.Unlock()
	_, err = s.server.db.Pool.Exec(ctx, `UPDATE channels SET runtime_started=false,runtime_error=NULL,runtime_updated_at=$1 WHERE disabled=true AND runtime_started=true`, nowMillis())
	return err
}
func (s *schedulerRuntime) runStream(ctx context.Context, stream *channelStream) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = stream.provider.RunStream(ctx, func(callCtx context.Context, msg channels.Message) error {
			// Revalidate the shared fence before accepting a frame from an old socket.
			var valid bool
			if err := s.server.db.Pool.QueryRow(callCtx, `SELECT EXISTS(SELECT 1 FROM scheduler_stream_leases l JOIN channels c USING(channel_id) WHERE l.channel_id=$1 AND l.token=$2 AND l.lease_until>now() AND c.disabled=false)`, stream.provider.ID, stream.token).Scan(&valid); err != nil || !valid {
				return fmt.Errorf("stream lease unavailable")
			}
			return s.accept(callCtx, msg)
		}, func(connected bool) {
			statusCtx, stop := context.WithTimeout(context.Background(), 3*time.Second)
			defer stop()
			var failure any
			if !connected {
				failure = "Stream connection unavailable"
			}
			_, _ = s.server.db.Pool.Exec(statusCtx, `UPDATE channels SET runtime_started=$3,runtime_error=$4,runtime_updated_at=$5 WHERE channel_id=$1 AND disabled=false AND EXISTS(SELECT 1 FROM scheduler_stream_leases WHERE channel_id=$1 AND token=$2 AND lease_until>now())`, stream.provider.ID, stream.token, connected, failure, nowMillis())
		})
	}()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	defer func() {
		cancel()
		<-done
		releaseCtx, stop := context.WithTimeout(context.Background(), 3*time.Second)
		defer stop()
		_, _ = s.server.db.Pool.Exec(releaseCtx, `DELETE FROM scheduler_stream_leases WHERE channel_id=$1 AND token=$2`, stream.provider.ID, stream.token)
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-done:
			return
		case <-ticker.C:
			renewalCtx, stop := context.WithTimeout(ctx, 3*time.Second)
			tag, err := s.server.db.Pool.Exec(renewalCtx, `UPDATE scheduler_stream_leases SET lease_until=now()+interval '20 seconds' WHERE channel_id=$1 AND token=$2 AND lease_until>now()`, stream.provider.ID, stream.token)
			stop()
			if err != nil || tag.RowsAffected() != 1 {
				return
			}
		}
	}
}
func (s *schedulerRuntime) scanCron(ctx context.Context) error {
	after := ""
	for {
		rows, err := s.server.db.Pool.Query(ctx, deploySelect+` WHERE trigger_type='cron' AND enabled=true AND archived_at IS NULL AND deployment_id>$1 AND NOT EXISTS(SELECT 1 FROM scheduler_jobs j WHERE j.kind='cron' AND j.state='pending' AND j.payload->>'deploymentId'=deployments.deployment_id) ORDER BY deployment_id LIMIT 128`, after)
		if err != nil {
			return err
		}
		deployments := []deployRow{}
		for rows.Next() {
			d, e := s.server.scanDeploy(rows)
			if e != nil {
				rows.Close()
				return e
			}
			deployments = append(deployments, d)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		for _, d := range deployments {
			after = d.DeploymentID
			if d.CronExpression == nil {
				continue
			}
			schedule, e := cronexpr.Parse(*d.CronExpression)
			if e != nil {
				continue
			}
			anchor := d.CreatedAt
			if d.LastRunAt != nil {
				anchor = *d.LastRunAt
			}
			due := schedule.Next(time.UnixMilli(anchor).In(time.Local))
			if due.IsZero() || due.After(time.Now()) {
				continue
			}
			job := cronJob{DeploymentID: d.DeploymentID, Expression: *d.CronExpression, Fire: due.UTC().Format(time.RFC3339Nano)}
			if e = s.enqueue(ctx, "cron:"+d.DeploymentID+":"+job.Fire, "cron", "", job); e != nil {
				return e
			}
		}
		if len(deployments) < 128 {
			return nil
		}
	}
}
func (s *schedulerRuntime) enqueue(ctx context.Context, id, kind, channel string, payload any) error {
	_, err := s.server.db.Pool.Exec(ctx, `INSERT INTO scheduler_jobs(id,kind,channel_id,payload) VALUES($1,$2,NULLIF($3,''),$4::jsonb) ON CONFLICT DO NOTHING`, id, kind, channel, mustJSON(payload))
	return err
}
func (s *schedulerRuntime) accept(ctx context.Context, message channels.Message) error {
	if message.MessageID == "" || len(message.MessageID) > 512 || message.PeerID == "" || len(message.PeerID) > 512 || message.AccountID == "" || len(message.AccountID) > 512 || message.SenderID == "" || len(message.SenderID) > 512 || len(message.ThreadID) > 512 || len(message.ReplyToID) > 512 || strings.TrimSpace(message.Text) == "" || len(message.Text) > 32000 {
		return fmt.Errorf("invalid channel identity")
	}
	id := "channel:" + sha256Hex(mustJSON([]string{message.ChannelID, message.AccountID, message.MessageID}))
	// Serialize the peer rate check and deduplication across all replicas.
	tx, err := s.server.db.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	key := sha256Hex(mustJSON([]string{message.ChannelID, message.AccountID, message.PeerKind, message.PeerID}))
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, key); err != nil {
		return err
	}
	var seen bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM scheduler_jobs WHERE id=$1)`, id).Scan(&seen); err != nil {
		return err
	}
	if seen {
		return tx.Commit(ctx)
	}

	var now time.Time
	if err = tx.QueryRow(ctx, `SELECT now()`).Scan(&now); err != nil {
		return err
	}
	var events []time.Time
	var blocked time.Time
	err = tx.QueryRow(ctx, `SELECT events,blocked_until FROM scheduler_channel_throttle WHERE peer_key=$1`, key).Scan(&events, &blocked)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	if now.Before(blocked) {
		return tx.Commit(ctx)
	}
	recent := []time.Time{}
	for _, event := range events {
		if !event.Before(now.Add(-time.Minute)) {
			recent = append(recent, event)
		}
	}
	allowed := len(recent) < 20
	if !allowed {
		blocked = now.Add(time.Minute)
	} else {
		recent = append(recent, now)
		blocked = now.Add(-time.Second)
	}
	_, err = tx.Exec(ctx, `INSERT INTO scheduler_channel_throttle(peer_key,channel_id,events,blocked_until,updated_at) VALUES($1,$2,$3,$4,now()) ON CONFLICT(peer_key) DO UPDATE SET events=EXCLUDED.events,blocked_until=EXCLUDED.blocked_until,updated_at=now()`, key, message.ChannelID, recent, blocked)
	if err != nil {
		return err
	}
	if !allowed {
		return tx.Commit(ctx)
	}

	tag, err := tx.Exec(ctx, `INSERT INTO scheduler_jobs(id,kind,channel_id,payload) SELECT $1,'channel',$2,$3::jsonb FROM channels WHERE channel_id=$2 AND disabled=false AND ($4='' OR type=$4) ON CONFLICT DO NOTHING`, id, message.ChannelID, mustJSON(channelJob{Message: message}), message.ProviderType)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var duplicate bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM scheduler_jobs WHERE id=$1)`, id).Scan(&duplicate); err != nil {
			return err
		}
		if !duplicate {
			return fmt.Errorf("channel configuration changed during intake")
		}
	}
	return tx.Commit(ctx)
}
