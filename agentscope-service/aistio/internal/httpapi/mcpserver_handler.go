package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/spring-ai-alibaba/aistio/api/v1alpha1"
)

func (s *Server) createMCPServer(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var req struct {
		Name string                 `json:"name"`
		Spec v1alpha1.MCPServerSpec `json:"spec"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	mcp := &v1alpha1.MCPServer{
		ObjectMeta: metav1.ObjectMeta{
			Name:      req.Name,
			Namespace: namespace,
		},
		Spec: req.Spec,
	}

	if err := s.client.Create(c.Request.Context(), mcp); err != nil {
		if errors.IsAlreadyExists(err) {
			c.JSON(http.StatusConflict, ErrorResponse{Error: "mcpserver already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, mcp)
}

func (s *Server) listMCPServers(c *gin.Context) {
	namespace := c.DefaultQuery("namespace", "")
	limit := parseLimit(c, 100)
	continueToken := c.Query("continue")

	var list v1alpha1.MCPServerList
	opts := []client.ListOption{}
	if namespace != "" {
		opts = append(opts, client.InNamespace(namespace))
	}
	if limit > 0 {
		opts = append(opts, client.Limit(int64(limit)))
	}
	if continueToken != "" {
		opts = append(opts, client.Continue(continueToken))
	}
	if err := s.client.List(c.Request.Context(), &list, opts...); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	resp := gin.H{"items": list.Items}
	if list.Continue != "" {
		resp["metadata"] = ListMetadata{Continue: list.Continue}
	}
	c.JSON(http.StatusOK, resp)
}

func (s *Server) getMCPServer(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mcp v1alpha1.MCPServer
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mcp); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "mcpserver not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, mcp)
}

func (s *Server) patchMCPServer(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mcp v1alpha1.MCPServer
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mcp); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "mcpserver not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	var patch v1alpha1.MCPServerSpec
	if err := c.ShouldBindJSON(&patch); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	if patch.Description != "" {
		mcp.Spec.Description = patch.Description
	}
	if patch.Remote != nil {
		mcp.Spec.Remote = patch.Remote
	}

	if err := s.client.Update(c.Request.Context(), &mcp); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, mcp)
}

func (s *Server) deleteMCPServer(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mcp v1alpha1.MCPServer
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mcp); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "mcpserver not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	if err := s.client.Delete(c.Request.Context(), &mcp); err != nil {
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.Status(http.StatusNoContent)
}

func (s *Server) listMCPTools(c *gin.Context) {
	name := c.Param("name")
	namespace := c.DefaultQuery("namespace", defaultNamespace)

	var mcp v1alpha1.MCPServer
	if err := s.client.Get(c.Request.Context(), types.NamespacedName{Name: name, Namespace: namespace}, &mcp); err != nil {
		if errors.IsNotFound(err) {
			c.JSON(http.StatusNotFound, ErrorResponse{Error: "mcpserver not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, ErrorResponse{Error: err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"tools": mcp.Status.DiscoveredTools})
}
