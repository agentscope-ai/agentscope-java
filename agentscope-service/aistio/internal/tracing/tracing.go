// Package tracing provides OpenTelemetry tracing for the control plane.
// When an OTLP endpoint is configured, spans are exported via gRPC; otherwise
// a zero-overhead noop provider is used.
package tracing

import (
	"context"
	"fmt"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	sdkresource "go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
	"go.opentelemetry.io/otel/trace/noop"
)

var tracer trace.Tracer

func init() {
	tracer = noop.NewTracerProvider().Tracer("aistio")
}

// Init initializes the OpenTelemetry tracing provider.
// When endpoint is non-empty, it configures an OTLP gRPC exporter with
// batched span processing. When endpoint is empty, it remains a noop
// with zero overhead. The returned function flushes and shuts down the
// provider; call it on process exit.
func Init(ctx context.Context, endpoint string, samplingRate float64) (func(), error) {
	if endpoint == "" {
		return func() {}, nil
	}

	exporter, err := otlptracegrpc.New(ctx,
		otlptracegrpc.WithEndpoint(endpoint),
		otlptracegrpc.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("create OTLP exporter: %w", err)
	}

	res, err := sdkresource.New(ctx,
		sdkresource.WithAttributes(
			semconv.ServiceName("aistio"),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("create resource: %w", err)
	}

	var sampler sdktrace.Sampler
	if samplingRate >= 1.0 {
		sampler = sdktrace.AlwaysSample()
	} else if samplingRate <= 0.0 {
		sampler = sdktrace.NeverSample()
	} else {
		sampler = sdktrace.TraceIDRatioBased(samplingRate)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sampler),
	)

	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	tracer = tp.Tracer("aistio")

	return func() {
		_ = tp.Shutdown(context.Background())
	}, nil
}

// Tracer returns the package-level tracer for creating spans.
func Tracer() trace.Tracer {
	return tracer
}
