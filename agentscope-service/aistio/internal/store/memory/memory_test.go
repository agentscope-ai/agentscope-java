package memory_test

import (
	"context"
	"testing"

	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"github.com/spring-ai-alibaba/aistio/internal/store/storetest"
)

func TestMemoryStore(t *testing.T) {
	s, err := store.Open(context.Background(), store.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	storetest.RunSuite(t, s)
}
