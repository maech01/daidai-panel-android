package handler

import (
	"fmt"
	"io"
	"mime/multipart"
	"os"
	"path/filepath"
	"strings"

	"daidai-panel/pkg/pathutil"
	"daidai-panel/pkg/response"
	"daidai-panel/service"

	"github.com/gin-gonic/gin"
)

type scriptUploadTarget struct {
	header     *multipart.FileHeader
	targetPath string
	fullPath   string
}

func resolveScriptUploadPath(targetPath string) (string, string, error) {
	normalizedPath, err := normalizeScriptRelativePath(targetPath)
	if err != nil {
		return "", "", err
	}

	baseDir, err := filepath.Abs(scriptsDir())
	if err != nil {
		return "", "", fmt.Errorf("??????")
	}

	fullPath := filepath.Clean(filepath.Join(baseDir, filepath.FromSlash(normalizedPath)))
	if !pathutil.IsWithinBase(baseDir, fullPath) {
		return "", "", fmt.Errorf("???????")
	}

	return normalizedPath, fullPath, nil
}

func validateScriptLeafName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", fmt.Errorf("??????")
	}
	if strings.ContainsAny(name, "/\\") {
		return "", fmt.Errorf("???????????")
	}
	if name == "." || name == ".." {
		return "", fmt.Errorf("????? . ? ..")
	}
	if invalidScriptPathCharsPattern.MatchString(name) {
		return "", fmt.Errorf("????????")
	}
	return name, nil
}

func resolveScriptDestinationPath(targetDir, name string) (string, string, error) {
	validatedName, err := validateScriptLeafName(name)
	if err != nil {
		return "", "", err
	}

	targetPath := validatedName
	if strings.TrimSpace(targetDir) != "" {
		targetPath = filepath.ToSlash(filepath.Join(targetDir, validatedName))
	}

	return resolveScriptUploadPath(targetPath)
}

func (h *ScriptHandler) SaveContent(c *gin.Context) {
	var req struct {
		Path    string `json:"path" binding:"required"`
		Content string `json:"content"`
		Message string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	ext := strings.ToLower(filepath.Ext(req.Path))
	if ext != "" && !allowedExtensions[ext] {
		response.BadRequest(c, "????????")
		return
	}

	if len(req.Content) > maxUploadSize {
		response.BadRequest(c, fmt.Sprintf("????(?? %dMB)", maxUploadSize/(1024*1024)))
		return
	}

	full, err := safePath(req.Path, false)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	if info, statErr := os.Stat(full); statErr == nil && info.IsDir() {
		response.BadRequest(c, "???????,???????")
		return
	}

	content := req.Content
	if ext == ".sh" {
		content = string(service.NormalizeShellLineEndings([]byte(content)))
	}

	if err := os.MkdirAll(filepath.Dir(full), 0755); err != nil {
		response.InternalError(c, fmt.Sprintf("????????: %s", err.Error()))
		return
	}
	if err := os.WriteFile(full, []byte(content), 0644); err != nil {
		response.InternalError(c, fmt.Sprintf("??????: %s", err.Error()))
		return
	}

	newVersion := recordScriptVersion(req.Path, content, req.Message)
	response.Success(c, gin.H{"message": "????", "version": newVersion})
}

func (h *ScriptHandler) Upload(c *gin.Context) {
	form, err := c.MultipartForm()
	if err != nil {
		response.BadRequest(c, "?????")
		return
	}

	headers := form.File["file"]
	if len(headers) == 0 {
		response.BadRequest(c, "?????")
		return
	}

	dir := c.PostForm("dir")
	targets := make([]scriptUploadTarget, 0, len(headers))
	for _, header := range headers {
		if header.Size > maxUploadSize {
			response.BadRequest(c, fmt.Sprintf("?? %s ??(?? %dMB)", header.Filename, maxUploadSize/(1024*1024)))
			return
		}

		filename := header.Filename
		ext := strings.ToLower(filepath.Ext(filename))
		if ext != "" && !allowedExtensions[ext] {
			response.BadRequest(c, fmt.Sprintf("?? %s ?????", header.Filename))
			return
		}

		targetPath := filename
		if dir != "" {
			targetPath = filepath.ToSlash(filepath.Join(dir, filename))
		}

		normalizedTargetPath, full, err := resolveScriptUploadPath(targetPath)
		if err != nil {
			response.BadRequest(c, err.Error())
			return
		}

		targets = append(targets, scriptUploadTarget{
			header:     header,
			targetPath: normalizedTargetPath,
			fullPath:   full,
		})
	}

	uploadedPaths := make([]string, 0, len(targets))
	for _, target := range targets {
		if err := os.MkdirAll(filepath.Dir(target.fullPath), 0755); err != nil {
			response.InternalError(c, "??????")
			return
		}
		file, err := target.header.Open()
		if err != nil {
			response.InternalError(c, fmt.Sprintf("????????: %s", target.header.Filename))
			return
		}

		content, err := io.ReadAll(file)
		file.Close()
		if err != nil {
			response.InternalError(c, fmt.Sprintf("????????: %s", target.header.Filename))
			return
		}

		if strings.ToLower(filepath.Ext(target.targetPath)) == ".sh" {
			content = service.NormalizeShellLineEndings(content)
		}

		if err := os.WriteFile(target.fullPath, content, 0644); err != nil {
			response.InternalError(c, fmt.Sprintf("??????: %s", target.header.Filename))
			return
		}
		uploadedPaths = append(uploadedPaths, target.targetPath)
	}

	message := "????"
	if len(uploadedPaths) > 1 {
		message = fmt.Sprintf("???? %d ???", len(uploadedPaths))
	}

	response.Created(c, gin.H{
		"message":        message,
		"path":           uploadedPaths[0],
		"paths":          uploadedPaths,
		"uploaded_count": len(uploadedPaths),
	})
}

func (h *ScriptHandler) Delete(c *gin.Context) {
	path := c.Query("path")
	fileType := c.DefaultQuery("type", "file")

	full, err := safePath(path, true)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	if fileType == "directory" {
		if err := os.RemoveAll(full); err != nil {
			response.InternalError(c, "??????")
			return
		}
	} else {
		if err := os.Remove(full); err != nil {
			response.InternalError(c, "??????")
			return
		}
	}

	response.Success(c, gin.H{"message": "????"})
}

func (h *ScriptHandler) CreateDirectory(c *gin.Context) {
	var req struct {
		Path string `json:"path" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	full, err := safePath(req.Path, false)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	if err := os.MkdirAll(full, 0755); err != nil {
		response.InternalError(c, "??????")
		return
	}

	response.Created(c, gin.H{"message": "????"})
}

func (h *ScriptHandler) Rename(c *gin.Context) {
	var req struct {
		OldPath string `json:"old_path" binding:"required"`
		NewName string `json:"new_name" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	validatedName, err := validateScriptLeafName(req.NewName)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	full, err := safePath(req.OldPath, true)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	newFull := filepath.Join(filepath.Dir(full), validatedName)
	if err := os.Rename(full, newFull); err != nil {
		response.InternalError(c, "?????")
		return
	}

	response.Success(c, gin.H{"message": "?????", "new_path": relPath(newFull)})
}

func (h *ScriptHandler) Move(c *gin.Context) {
	var req struct {
		SourcePath string `json:"source_path" binding:"required"`
		TargetDir  string `json:"target_dir"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	srcFull, err := safePath(req.SourcePath, true)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	targetBase := scriptsDir()
	if req.TargetDir != "" && req.TargetDir != "/" {
		targetBase, err = safePath(req.TargetDir, true)
		if err != nil {
			response.BadRequest(c, "??????")
			return
		}
	}

	absTarget, _ := filepath.Abs(targetBase)
	absSrc, _ := filepath.Abs(srcFull)
	if strings.HasPrefix(absTarget, absSrc+string(filepath.Separator)) {
		response.BadRequest(c, "??????????")
		return
	}

	destFull := filepath.Join(targetBase, filepath.Base(srcFull))
	if err := os.Rename(srcFull, destFull); err != nil {
		response.InternalError(c, "????")
		return
	}

	response.Success(c, gin.H{"message": "????", "new_path": relPath(destFull)})
}

func (h *ScriptHandler) Copy(c *gin.Context) {
	var req struct {
		SourcePath string `json:"source_path" binding:"required"`
		TargetDir  string `json:"target_dir"`
		NewName    string `json:"new_name"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	srcFull, err := safePath(req.SourcePath, true)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	name := filepath.Base(srcFull)
	if req.NewName != "" {
		name = req.NewName
	}

	normalizedDestPath, destFull, err := resolveScriptDestinationPath(req.TargetDir, name)
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	if err := os.MkdirAll(filepath.Dir(destFull), 0o755); err != nil {
		response.InternalError(c, "????????")
		return
	}

	info, _ := os.Stat(srcFull)
	if info != nil && info.IsDir() {
		if err := copyDir(srcFull, destFull); err != nil {
			response.InternalError(c, "??????")
			return
		}
	} else {
		if err := copyFile(srcFull, destFull); err != nil {
			response.InternalError(c, "??????")
			return
		}
	}

	response.Created(c, gin.H{"message": "????", "new_path": normalizedDestPath})
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	os.MkdirAll(filepath.Dir(dst), 0755)
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, in)
	return err
}

func copyDir(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, path)
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		return copyFile(path, target)
	})
}

func (h *ScriptHandler) BatchDelete(c *gin.Context) {
	var req struct {
		Paths []struct {
			Path string `json:"path"`
			Type string `json:"type"`
		} `json:"paths" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "??????")
		return
	}

	successCount := 0
	failedCount := 0
	failedItems := []string{}

	for _, item := range req.Paths {
		full, err := safePath(item.Path, true)
		if err != nil {
			failedCount++
			failedItems = append(failedItems, item.Path)
			continue
		}
		if item.Type == "directory" {
			err = os.RemoveAll(full)
		} else {
			err = os.Remove(full)
		}
		if err != nil {
			failedCount++
			failedItems = append(failedItems, item.Path)
		} else {
			successCount++
		}
	}

	response.Success(c, gin.H{
		"message":       fmt.Sprintf("????: ?? %d, ?? %d", successCount, failedCount),
		"success_count": successCount,
		"failed_count":  failedCount,
		"failed_items":  failedItems,
	})
}
