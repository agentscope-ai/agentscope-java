/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package httpapi

// registerLegacyEndpointTestRoutes exercises stored pre-migration executions.
// These routes are intentionally absent from production.
func registerLegacyEndpointTestRoutes(s *Server) {
	s.router.POST("/invoke/v1/endpoints/:slug/conversations", s.invokeEndpointConversation)
	s.router.POST("/invoke/v1/conversations/:conversationId/turns", s.continueEndpointConversation)
	s.router.GET("/invoke/v1/conversations/:conversationId", s.getEndpointConversation)
	s.router.POST("/invoke/v1/endpoints/:slug/jobs", s.invokeEndpointJob)
	s.router.GET("/invoke/v1/endpoints/:slug/capabilities", s.getServiceCapabilities)
	service := s.router.Group("/invoke/v1/invocations/:invocationId")
	service.GET("", s.getServiceInvocation)
	service.GET("/snapshot", s.getServiceSnapshot)
	service.GET("/capabilities", s.getServiceInvocationCapabilities)
	service.GET("/events", s.getServiceEvents)
	service.GET("/events/stream", s.streamServiceEvents)
	service.GET("/actions", s.getServiceSnapshot)
	service.GET("/usage", s.getServiceSnapshot)
	service.GET("/artifacts", s.getServiceSnapshot)
	service.GET("/artifacts/:artifactId", s.downloadServiceArtifact)
	service.POST("/actions", s.createServiceCommand)
	service.POST("/inputs", s.createServiceCommand)
	service.POST("/cancel", s.createServiceCommand)
	service.POST("/resume", s.createServiceCommand)
	service.GET("/commands/:commandId", s.getServiceCommand)
	service.POST("/webhooks", s.createServiceWebhook)
	service.GET("/webhooks", s.listServiceWebhooks)
	service.DELETE("/webhooks/:webhookId", s.updateServiceWebhook)
	service.POST("/webhooks/:webhookId/retry", s.updateServiceWebhook)
	v1 := s.router.Group("/api/v1", s.authMiddleware(), s.scopeMiddleware(), s.namespaceAccessMiddleware(), s.authzMiddleware())
	v1.GET("/endpoints", s.listEndpoints)
	v1.POST("/endpoints", s.createEndpoint)
	v1.GET("/endpoints/:endpointId", s.getEndpoint)
	v1.PATCH("/endpoints/:endpointId", s.patchEndpoint)
	v1.DELETE("/endpoints/:endpointId", s.archiveEndpoint)
	v1.GET("/endpoints/:endpointId/readiness", s.getEndpointReadiness)
	v1.GET("/endpoints/:endpointId/invocations", s.listEndpointInvocations)
	v1.POST("/endpoints/:endpointId/publish", s.publishEndpoint)
	v1.POST("/endpoints/:endpointId/disable", s.disableEndpoint)
	v1.GET("/endpoints/:endpointId/releases", s.listEndpointReleases)
	v1.POST("/endpoints/:endpointId/releases", s.deployEndpointRelease)
	v1.POST("/endpoints/:endpointId/releases/:releaseId/rollback", s.rollbackEndpointRelease)
	v1.GET("/endpoints/:endpointId/credentials", s.listEndpointCredentials)
	v1.POST("/endpoints/:endpointId/credentials", s.createEndpointCredential)
	v1.POST("/endpoints/:endpointId/credentials/:credentialId/rotate", s.rotateEndpointCredential)
	v1.POST("/endpoints/:endpointId/credentials/:credentialId/reveal", s.revealEndpointCredential)
	v1.DELETE("/endpoints/:endpointId/credentials/:credentialId", s.revokeEndpointCredential)
}

func newLegacyEndpointTestServer(options ServerOptions) *Server {
	s := NewServer(options)
	if s.store != nil {
		registerLegacyEndpointTestRoutes(s)
	}
	return s
}
