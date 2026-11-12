// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

import (
	"strings"
	"testing"
)

func TestExecutionEnvironmentModes(t *testing.T) {
	for _, test := range []struct {
		name, typ string
		config    map[string]any
		valid     bool
	}{
		{"local", "", map[string]any{"type": "local"}, true},
		{"ACS", "", map[string]any{"type": "cloud", "domain": "sandbox.example.com", "templateId": "python"}, true},
		{"borrowed", "self_hosted", map[string]any{"domain": "sandbox.example.com", "apiKey": "tenant-key"}, true},
		{"conflicting modes", "local", map[string]any{"type": "cloud"}, false},
		{"wrong cloud provider", "cloud", map[string]any{"provider": "daytona"}, false},
		{"missing template", "cloud", map[string]any{"domain": "sandbox.example.com"}, false},
		{"platform credentials cannot serve borrowed instances", "self_hosted", map[string]any{"domain": "sandbox.example.com"}, false},
		{"shared scope", "local", map[string]any{"isolationScope": "GLOBAL"}, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			typ, config, err := executionEnvironmentConfig(test.typ, test.config)
			if (err == nil) != test.valid {
				t.Fatalf("unexpected validation: %v", err)
			}
			if err == nil && config["type"] != typ {
				t.Fatal("mode not preserved")
			}
		})
	}
}

func TestExecutionEnvironmentMigrationPreservesConfiguration(t *testing.T) {
	s := resourceTestServer(t)
	owner, local, cloud := shortID("owner_"), shortID("env_"), shortID("env_")
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,config_json,created_at,updated_at) VALUES($1,$2,'old-local','self_hosted','{}',1,1)`, local, owner)
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,config_json,created_at,updated_at) VALUES($1,$2,'old-cloud','sandbox','{"templateId":"custom"}',1,1)`, cloud, owner)
	var migration string
	for _, candidate := range productMigrations {
		if candidate.version == "0005_environment_execution_modes" {
			migration = candidate.sql
		}
	}
	if migration == "" {
		t.Fatal("execution environment migration is missing")
	}
	resourceSQL(t, s, migration)
	a, err := s.loadEnv(t.Context(), local)
	if err != nil || a.Type != "local" {
		t.Fatalf("local migration: %v %#v", err, a)
	}
	b, err := s.loadEnv(t.Context(), cloud)
	if err != nil || b.Type != "cloud" {
		t.Fatalf("cloud migration: %v %#v", err, b)
	}
	if !strings.Contains(deref(b.ConfigJSON), "custom") {
		t.Fatal("template lost")
	}
}
