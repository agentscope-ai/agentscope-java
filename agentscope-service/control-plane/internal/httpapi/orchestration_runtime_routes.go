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

package httpapi

import "github.com/gin-gonic/gin"

// The execution listener accepts service traffic only. Agent Service preserves the Host
// bearer credential; its service credential never substitutes for that identity.
func (s *Server) registerRuntimeRoutes() {
	s.router.GET("/healthz", s.healthz)
	s.router.GET("/readyz", s.readyz)
	s.registerRuntimeProtocols()
}

func (s *Server) registerRuntimeProtocols() {
	private := s.router.Group("/api/internal/runtime")
	private.Use(s.requireRuntimeProjectionToken, s.internalTokenMiddleware())
	private.GET("/version", s.version)
	if s.store != nil {
		private.POST("/sessions/bootstrap", s.bootstrapAgentServiceRuntime)
		private.POST("/sessions/lifecycle", s.applyAgentServiceRuntimeLifecycle)
		private.POST("/commands/turn", s.acceptAgentServiceRuntimeTurn)
		private.GET("/commands/turn/:session/:id", s.agentServiceRuntimeTurnReceipt)
		private.POST("/commands/interaction", s.acceptAgentServiceRuntimeInteraction)
		private.GET("/commands/interaction/:session/:id", s.agentServiceRuntimeInteractionReceipt)
		private.GET("/sessions/:session/turns/:turn/artifacts/:artifact/content", s.downloadAgentServiceRuntimeArtifact)
		mcp := private.Group("/mcp")
		mcp.Use(s.teamsAuthMiddleware(), s.runtimeTaskGrantMiddleware())
		mcp.POST("/collaboration", s.collaborationMCP)
		approvals := private.Group("/agent-tasks/:taskId/runtime-approvals")
		approvals.Use(s.teamsAuthMiddleware(), s.runtimeApprovalScopeMiddleware())
		approvals.POST("", s.requestRuntimeToolApproval)
		approvals.GET("/:approvalId/decision", s.getRuntimeToolApprovalDecision)
		approvals.POST("/:approvalId/ack", s.acknowledgeRuntimeToolApprovalDecision)
		s.registerRuntimeTaskCollaborationRoutes(private)
	}
	if s.store != nil && s.features.RuntimeHost {
		hosts := private.Group("/runtime-hosts")
		hosts.Use(s.runtimeHostCredentialMiddleware())
		s.registerRuntimeHostRoutes(hosts)
	}
	if s.store != nil && (s.hostedStore || s.agentServiceRuntime) {
		s.registerHostedStoreRoutes(private.Group("/distributed-store"))
	}
}

func (s *Server) registerRuntimeTaskCollaborationRoutes(private *gin.RouterGroup) {
	collab := private.Group("")
	collab.Use(s.teamsAuthMiddleware(), s.runtimeTaskGrantMiddleware(), s.collaborationTaskScopeMiddleware())
	tasks := collab.Group("/agent-tasks")
	tasks.GET("/:taskId", s.getAgentTask)
	tasks.GET("/:taskId/context", s.taskTokenMiddleware(), s.getAgentTaskContext)
	tasks.POST("/:taskId/progress", s.taskTokenMiddleware(), s.progressAgentTask)
	tasks.POST("/:taskId/respond", s.taskTokenMiddleware(), s.respondAgentTask)
	tasks.POST("/:taskId/children", s.taskTokenMiddleware(), s.createAgentTaskChildIssue)
	tasks.POST("/:taskId/complete", s.taskTokenMiddleware(), s.completeAgentTask)
	tasks.POST("/:taskId/fail", s.taskTokenMiddleware(), s.failAgentTask)
	tasks.GET("/:taskId/run", s.taskTokenMiddleware(), s.getTaskRun)
	tasks.GET("/:taskId/run/graph", s.taskTokenMiddleware(), s.getTaskRunGraph)
	tasks.GET("/:taskId/run/artifacts", s.taskTokenMiddleware(), s.getTaskRunArtifacts)
	tasks.POST("/:taskId/run/node/complete", s.coordinatorTaskTokenMiddleware(), s.completeTaskRunNode)
	tasks.POST("/:taskId/run/node/fail", s.coordinatorTaskTokenMiddleware(), s.failTaskRunNode)
	tasks.POST("/:taskId/run/replan", s.taskTokenMiddleware(), s.replanTaskRun)
	tasks.POST("/:taskId/run/signals/:name", s.taskTokenMiddleware(), s.signalTaskRun)
	collab.GET("/issues/:issueId", s.getIssue)
	collab.GET("/issues/:issueId/comments", s.listIssueComments)
	collab.POST("/issues/:issueId/comments", s.addIssueComment)
	collab.GET("/teams/:teamId", s.getCollaborationTeam)
}

func (s *Server) registerRuntimeHostRoutes(hosts *gin.RouterGroup) {
	hosts.POST("/register", s.registerRuntimeHost)
	hosts.POST("/:hostId/heartbeat", s.heartbeatRuntimeHost)
	hosts.POST("/:hostId/credentials", s.refreshRuntimeHostCredential)
	hosts.POST("/:hostId/state", s.setRuntimeHostState)
	hosts.POST("/:hostId/execution-attempts/claim", s.claimExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/renew", s.renewExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/credentials", s.refreshExecutionCredentials)
	hosts.POST("/:hostId/execution-attempts/:attemptId/observe", s.observeExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/preparing", s.prepareExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/running", s.startExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/checkpoint", s.checkpointExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/events", s.appendExecutionAttemptEvent)
	hosts.POST("/:hostId/execution-attempts/:attemptId/complete", s.completeExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/fail", s.failExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/cancelled", s.cancelledExecutionAttempt)
	hosts.POST("/:hostId/execution-attempts/:attemptId/physical-stop", s.confirmExecutionPhysicalStop)
}
