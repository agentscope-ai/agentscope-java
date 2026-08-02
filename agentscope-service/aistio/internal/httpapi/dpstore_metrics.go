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
