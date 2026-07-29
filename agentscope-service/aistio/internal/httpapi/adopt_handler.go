package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/spring-ai-alibaba/aistio/internal/discovery"
)

func (s *Server) adoptAgent(c *gin.Context) {
	var req struct {
		DeploymentName string `json:"deploymentName" binding:"required"`
		Namespace      string `json:"namespace"`
		AgentName      string `json:"agentName"`
		Runtime        string `json:"runtime"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	namespace := req.Namespace
	if namespace == "" {
		namespace = defaultNamespace
	}

	adopter := discovery.NewAdopter(s.client)
	result, err := adopter.Adopt(c.Request.Context(), discovery.AdoptRequest{
		DeploymentName: req.DeploymentName,
		Namespace:      namespace,
		AgentName:      req.AgentName,
		Runtime:        req.Runtime,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}
