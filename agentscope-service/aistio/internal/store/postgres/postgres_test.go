package postgres_test

import (
	"context"
	"os"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
	"github.com/spring-ai-alibaba/aistio/internal/store/storetest"
)

func TestPostgresStore(t *testing.T) {
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set; skipping postgres store tests")
	}
	s, err := store.Open(context.Background(), store.Config{
		Driver:      store.DriverPostgres,
		PostgresDSN: dsn,
		Retention:   store.DefaultRetention(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	storetest.RunSuite(t, s)
}
