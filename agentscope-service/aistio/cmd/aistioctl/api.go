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

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"

	"gopkg.in/yaml.v3"
)

func readYAMLAsJSON(filename string) ([]byte, error) {
	raw, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}
	var value any
	if err = yaml.Unmarshal(raw, &value); err != nil {
		return nil, fmt.Errorf("parse %s: %w", filename, err)
	}
	return json.Marshal(value)
}
func doAPI(method, path string, body []byte) (*http.Response, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, apiEndpoint+path, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return newAPIClient().Do(req)
}
func printResponse(resp *http.Response, err error) error {
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("request failed (%d): %s", resp.StatusCode, body)
	}
	var out bytes.Buffer
	if json.Indent(&out, body, "", "  ") == nil {
		fmt.Println(out.String())
	} else {
		fmt.Println(string(body))
	}
	return nil
}
func jsonBody(value any) []byte             { body, _ := json.Marshal(value); return body }
func bytesReader(body []byte) *bytes.Reader { return bytes.NewReader(body) }
