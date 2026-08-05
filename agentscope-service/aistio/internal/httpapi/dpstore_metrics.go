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

package httpapi

import (
	"time"

	"github.com/prometheus/client_golang/prometheus"
	ctrlmetrics "sigs.k8s.io/controller-runtime/pkg/metrics"
)

var (
	dpstoreRequestsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "aistio_dpstore_requests_total",
		Help: "Total hosted-store API requests by capability and result.",
	}, []string{"capability", "result"})

	dpstoreRequestDuration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "aistio_dpstore_request_duration_seconds",
		Help:    "Hosted-store API request duration by capability.",
		Buckets: prometheus.DefBuckets,
	}, []string{"capability"})

	dpLocksHeld = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "aistio_dp_locks_held",
		Help: "Best-effort count of currently held hosted locks (acquire/release).",
	})

	dpTasksSweptTotal = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "aistio_dp_tasks_swept_total",
		Help: "Total hosted subagent tasks marked failed by the orphan sweep worker.",
	})
)

func init() {
	ctrlmetrics.Registry.MustRegister(
		dpstoreRequestsTotal,
		dpstoreRequestDuration,
		dpLocksHeld,
		dpTasksSweptTotal,
	)
}

func observeDPStore(capability, result string, start time.Time) {
	dpstoreRequestsTotal.WithLabelValues(capability, result).Inc()
	dpstoreRequestDuration.WithLabelValues(capability).Observe(time.Since(start).Seconds())
}

// AddDPTasksSwept increments the orphan sweep counter (used by TaskSweepWorker).
func AddDPTasksSwept(n int) {
	if n > 0 {
		dpTasksSweptTotal.Add(float64(n))
	}
}
