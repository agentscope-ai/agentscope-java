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

package product

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

func (s *Server) registerWorkspace(r gin.IRouter) {
	base := "/api/agents/:id/workspace"
	r.GET(base, s.workspaceSummary)
	r.GET(base+"/files", s.workspaceFiles)
	r.GET(base+"/file", s.workspaceReadFile)
	r.PUT(base+"/file", s.workspaceWriteFile)
	r.POST(base+"/file", s.workspaceCreateFile)
	r.POST(base+"/file/move", s.workspaceMoveFile)
	r.DELETE(base+"/file", s.workspaceDeleteFile)
	r.POST(base+"/upload", s.workspaceUpload)
	r.GET(base+"/subagents", s.listSubagents)
	r.PUT(base+"/subagents/:name", s.upsertSubagent)
	r.POST(base+"/subagents/from-agent", s.subagentFromAgent)
	r.DELETE(base+"/subagents/:name", s.deleteSubagent)
}

func (s *Server) resolveAgentWorkspace(ctx context.Context, owner, agentID string) (string, agentRow, error) {
	a, err := s.loadAgent(ctx, owner, agentID)
	if err != nil {
		return "", a, err
	}
	ws := ""
	if a.WorkspacePath != nil {
		ws = strings.TrimSpace(*a.WorkspacePath)
	}
	if ws == "" {
		ws = filepath.Join(s.cfg.WorkspaceRoot, owner, agentID)
	}
	_ = os.MkdirAll(ws, 0o755)
	return ws, a, nil
}

func cleanRelPath(p string) (string, error) {
	p = strings.TrimSpace(p)
	p = strings.TrimPrefix(p, "/")
	p = filepath.ToSlash(filepath.Clean(p))
	if p == "." {
		p = ""
	}
	if strings.HasPrefix(p, "../") || p == ".." || strings.Contains(p, "/../") {
		return "", errPathTraversal
	}
	if strings.Contains(p, "..") {
		return "", errPathTraversal
	}
	return p, nil
}

var errPathTraversal = &pathError{msg: "invalid path"}

type pathError struct{ msg string }

func (e *pathError) Error() string { return e.msg }

func joinWorkspace(root, rel string) (string, error) {
	rel, err := cleanRelPath(rel)
	if err != nil {
		return "", err
	}
	full := filepath.Join(root, filepath.FromSlash(rel))
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	absFull, err := filepath.Abs(full)
	if err != nil {
		return "", err
	}
	if absFull != absRoot && !strings.HasPrefix(absFull, absRoot+string(os.PathSeparator)) {
		return "", errPathTraversal
	}
	return absFull, nil
}

func (s *Server) workspaceSummary(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	info, err := os.Stat(ws)
	exists := err == nil && info.IsDir()
	agentsMd := fileExists(filepath.Join(ws, "AGENTS.md"))
	memoryMd := fileExists(filepath.Join(ws, "MEMORY.md"))
	skillCount := countDirs(filepath.Join(ws, "skills"))
	subagentCount := countFilesWithExt(filepath.Join(ws, "subagents"), ".md")
	if subagentCount == 0 {
		// Legacy layout fallback.
		subagentCount = countDirs(filepath.Join(ws, "agents"))
	}
	dailyMemoryCount := countFilesWithExt(filepath.Join(ws, "memory"), ".md")
	a, _ := s.loadAgent(c.Request.Context(), owner, c.Param("id"))
	wsID := ""
	if a.WorkspaceID != nil {
		wsID = *a.WorkspaceID
	}
	if wsID != "" {
		if content, ok, _ := s.getWorkspaceFile(c.Request.Context(), owner, scopeTypeWorkspace, wsID, "AGENTS.md"); ok && content != "" {
			agentsMd = true
		}
		if paths, err := s.listWorkspaceFilePaths(c.Request.Context(), owner, scopeTypeWorkspace, wsID, "skills"); err == nil {
			n := 0
			for _, p := range paths {
				if strings.HasSuffix(p, "/SKILL.md") {
					n++
				}
			}
			skillCount = n
		}
		if paths, err := s.listWorkspaceFilePaths(c.Request.Context(), owner, scopeTypeWorkspace, wsID, "subagents"); err == nil {
			n := 0
			for _, p := range paths {
				if strings.HasSuffix(p, ".md") {
					n++
				}
			}
			subagentCount = n
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"agentId":          c.Param("id"),
		"workspacePath":    ws,
		"workspaceId":      nullStr(wsID),
		"exists":           exists,
		"agentsMdExists":   agentsMd,
		"memoryMdExists":   memoryMd,
		"skillCount":       skillCount,
		"subagentCount":    subagentCount,
		"dailyMemoryCount": dailyMemoryCount,
	})
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func countDirs(dir string) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
			n++
		}
	}
	return n
}

func countFilesWithExt(dir, ext string) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(strings.ToLower(e.Name()), ext) {
			n++
		}
	}
	return n
}

type fileNode struct {
	Name     string      `json:"name"`
	Path     string      `json:"path"`
	Type     string      `json:"type"`
	Size     *int64      `json:"size,omitempty"`
	Children []*fileNode `json:"children,omitempty"`
}

func (s *Server) workspaceFiles(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	recursive := c.Query("recursive") != "false"
	nodes, err := buildFileTree(ws, "", recursive)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if nodes == nil {
		nodes = []*fileNode{}
	}
	c.JSON(http.StatusOK, nodes)
}

func buildFileTree(root, rel string, recursive bool) ([]*fileNode, error) {
	dir := root
	if rel != "" {
		dir = filepath.Join(root, filepath.FromSlash(rel))
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return []*fileNode{}, nil
		}
		return nil, err
	}
	out := []*fileNode{}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		childRel := e.Name()
		if rel != "" {
			childRel = rel + "/" + e.Name()
		}
		if e.IsDir() {
			node := &fileNode{Name: e.Name(), Path: childRel, Type: "dir"}
			if recursive {
				kids, err := buildFileTree(root, childRel, true)
				if err != nil {
					return nil, err
				}
				node.Children = kids
			}
			out = append(out, node)
			continue
		}
		info, err := e.Info()
		var size *int64
		if err == nil {
			sz := info.Size()
			size = &sz
		}
		out = append(out, &fileNode{Name: e.Name(), Path: childRel, Type: "file", Size: size})
	}
	return out, nil
}

func (s *Server) workspaceReadFile(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	rel := c.Query("path")
	scopeType, scopeID := a.resolveDefinitionScope()
	if content, ok, _ := s.getWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID, rel); ok {
		c.Data(http.StatusOK, "text/plain; charset=utf-8", []byte(content))
		return
	}
	full, err := joinWorkspace(ws, rel)
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid path")
		return
	}
	b, err := os.ReadFile(full)
	if err != nil {
		writeErr(c, http.StatusNotFound, "file not found")
		return
	}
	c.Data(http.StatusOK, "text/plain; charset=utf-8", b)
}

func (s *Server) workspaceWriteFile(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	rel := c.Query("path")
	if _, err := joinWorkspace(ws, rel); err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid path")
		return
	}
	var req struct {
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "content required")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID, rel, req.Content, ws); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if scopeType == scopeTypeWorkspace {
		_ = s.bumpWorkspaceVersion(c.Request.Context(), owner, scopeID)
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) workspaceCreateFile(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	if c.Query("type") != "" && c.Query("type") != "file" {
		writeTextErr(c, http.StatusBadRequest, "only type=file is supported")
		return
	}
	full, err := joinWorkspace(ws, c.Query("path"))
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid path")
		return
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	f, err := os.OpenFile(full, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		if os.IsExist(err) {
			writeTextErr(c, http.StatusConflict, "file already exists")
			return
		}
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	_ = f.Close()
	c.Status(http.StatusNoContent)
}

func (s *Server) workspaceMoveFile(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	var req struct {
		From string `json:"from"`
		To   string `json:"to"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.From == "" || req.To == "" {
		writeErr(c, http.StatusBadRequest, "from and to required")
		return
	}
	from, err := joinWorkspace(ws, req.From)
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid from path")
		return
	}
	to, err := joinWorkspace(ws, req.To)
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid to path")
		return
	}
	if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if err := os.Rename(from, to); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) workspaceDeleteFile(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	full, err := joinWorkspace(ws, c.Query("path"))
	if err != nil || c.Query("path") == "" || c.Query("path") == "/" {
		writeTextErr(c, http.StatusBadRequest, "invalid path")
		return
	}
	if err := os.RemoveAll(full); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) workspaceUpload(c *gin.Context) {
	owner := currentUserID(c)
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, c.Param("id"))
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	full, err := joinWorkspace(ws, c.Query("path"))
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "invalid path")
		return
	}
	file, err := c.FormFile("file")
	if err != nil {
		writeTextErr(c, http.StatusBadRequest, "file field required")
		return
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	src, err := file.Open()
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer src.Close()
	dst, err := os.Create(full)
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	defer dst.Close()
	if _, err := io.Copy(dst, src); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) listSubagents(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	ws, _, err := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	scopeType, scopeID := a.resolveDefinitionScope()
	files, err := s.listWorkspaceFileContents(c.Request.Context(), owner, scopeType, scopeID, "subagents")
	if err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	if len(files) == 0 {
		// Migrate legacy agents/<name>/AGENT.md if present on disk.
		legacyDir := filepath.Join(ws, "agents")
		if entries, rerr := os.ReadDir(legacyDir); rerr == nil {
			for _, e := range entries {
				if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
					continue
				}
				mdPath := filepath.Join(legacyDir, e.Name(), "AGENT.md")
				b, rerr := os.ReadFile(mdPath)
				if rerr != nil {
					continue
				}
				req := subagentUpsertReq{Description: e.Name(), InlineBody: string(b)}
				md := buildSubagentMarkdown(req)
				_ = s.putWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID,
					"subagents/"+e.Name()+".md", md, ws)
			}
			files, _ = s.listWorkspaceFileContents(c.Request.Context(), owner, scopeType, scopeID, "subagents")
		}
	}
	list := []gin.H{}
	for path, content := range files {
		if !strings.HasPrefix(path, "subagents/") || !strings.HasSuffix(path, ".md") {
			continue
		}
		name := strings.TrimSuffix(strings.TrimPrefix(path, "subagents/"), ".md")
		info := parseSubagentMarkdown(content)
		info["name"] = name
		list = append(list, info)
	}
	c.JSON(http.StatusOK, list)
}

func (s *Server) upsertSubagent(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	var req subagentUpsertReq
	if err := c.ShouldBindJSON(&req); err != nil {
		writeErr(c, http.StatusBadRequest, "invalid body")
		return
	}
	if req.Description == "" {
		req.Description = name
	}
	md := buildSubagentMarkdown(req)
	ws, _, _ := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	scopeType, scopeID := a.resolveDefinitionScope()
	if err := s.putWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID,
		"subagents/"+name+".md", md, ws); err != nil {
		writeErr(c, http.StatusInternalServerError, err.Error())
		return
	}
	// Remove legacy path if present.
	_ = os.RemoveAll(filepath.Join(ws, "agents", name))
	mode := req.WorkspaceMode
	if mode == "" {
		mode = "isolated"
	}
	c.JSON(http.StatusOK, gin.H{
		"name":          name,
		"description":   req.Description,
		"model":         nullStr(req.Model),
		"maxIters":      req.MaxIters,
		"tools":         req.Tools,
		"workspaceMode": mode,
		"workspacePath": nullStr(req.WorkspacePath),
		"hasInlineBody": true,
		"sourceAgentId": nullStr(req.SourceAgentID),
	})
}

func (s *Server) subagentFromAgent(c *gin.Context) {
	var req struct {
		SourceAgentID string `json:"sourceAgentId"`
		Name          string `json:"name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.SourceAgentID == "" {
		writeErr(c, http.StatusBadRequest, "sourceAgentId required")
		return
	}
	name := req.Name
	if name == "" {
		name = req.SourceAgentID
	}
	c.Params = append(c.Params, gin.Param{Key: "name", Value: name})
	src, err := s.loadAgent(c.Request.Context(), currentUserID(c), req.SourceAgentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "source agent not found")
		return
	}
	desc := ""
	if src.Description != nil {
		desc = *src.Description
	}
	body := "# " + src.Name + "\n\n" + desc + "\n"
	if src.SysPrompt != nil && *src.SysPrompt != "" {
		body += "\n" + *src.SysPrompt + "\n"
	}
	c.Request.Body = io.NopCloser(strings.NewReader(mustJSON(gin.H{
		"description":   desc,
		"inlineBody":    body,
		"sourceAgentId": req.SourceAgentID,
		"model":         deref(src.Model),
		"maxIters":      src.MaxIters,
	})))
	c.Request.Header.Set("Content-Type", "application/json")
	s.upsertSubagent(c)
}

func (s *Server) deleteSubagent(c *gin.Context) {
	owner := currentUserID(c)
	agentID := c.Param("id")
	a, err := s.loadAgent(c.Request.Context(), owner, agentID)
	if err != nil {
		writeErr(c, http.StatusNotFound, "agent not found")
		return
	}
	name := c.Param("name")
	if name == "" || strings.Contains(name, "..") {
		writeTextErr(c, http.StatusBadRequest, "invalid name")
		return
	}
	ws, _, _ := s.resolveAgentWorkspace(c.Request.Context(), owner, agentID)
	scopeType, scopeID := a.resolveDefinitionScope()
	_ = s.deleteWorkspaceFile(c.Request.Context(), owner, scopeType, scopeID, "subagents/"+name+".md", ws)
	_ = os.RemoveAll(filepath.Join(ws, "agents", name))
	c.Status(http.StatusNoContent)
}
