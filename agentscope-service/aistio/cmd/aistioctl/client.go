package main

import (
	"net/http"
)

func newAPIClient() *http.Client {
	return &http.Client{
		Transport: &tokenTransport{
			token: apiToken,
			base:  http.DefaultTransport,
		},
	}
}

type tokenTransport struct {
	token string
	base  http.RoundTripper
}

func (t *tokenTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if t.token != "" {
		req.Header.Set("Authorization", "Bearer "+t.token)
	}
	return t.base.RoundTrip(req)
}
