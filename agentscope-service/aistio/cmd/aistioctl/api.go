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
