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
