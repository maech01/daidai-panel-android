package service

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"daidai-panel/config"
)

const sandboxRuntimeMode = "linux-sandbox"

const (
	sandboxPipIndexURL = "https://mirrors.aliyun.com/pypi/simple/"
	sandboxNpmRegistry = "https://registry.npmmirror.com"
)

type sandboxMount struct {
	Host  string
	Guest string
}

type sandboxRuntimeConfig struct {
	Rootfs        string
	Proot         string
	ProotLoader   string
	ProotLoader32 string
	TmpDir        string
	NativeLibDir  string
	Mounts        []sandboxMount
}

type AndroidSandboxHealth struct {
	Enabled bool              `json:"enabled"`
	Status  string            `json:"status"`
	Message string            `json:"message,omitempty"`
	Checks  map[string]string `json:"checks,omitempty"`
}

func sandboxRuntimeEnabled(envVars map[string]string) bool {
	mode := strings.TrimSpace(os.Getenv("DAIDAI_RUNTIME_MODE"))
	if envVars != nil && strings.TrimSpace(envVars["DAIDAI_RUNTIME_MODE"]) != "" {
		mode = strings.TrimSpace(envVars["DAIDAI_RUNTIME_MODE"])
	}
	return strings.EqualFold(mode, sandboxRuntimeMode)
}

func IsSandboxRuntime() bool {
	return sandboxRuntimeEnabled(nil)
}

func SandboxRootfsPath() string {
	return strings.TrimSpace(os.Getenv("DAIDAI_SANDBOX_ROOTFS"))
}

func NewSandboxCommand(commandArgs []string, workDir string) (*exec.Cmd, error) {
	if len(commandArgs) == 0 || strings.TrimSpace(commandArgs[0]) == "" {
		return nil, fmt.Errorf("沙盒命令不能为空")
	}
	guestWorkDir := strings.TrimSpace(workDir)
	if guestWorkDir == "" {
		guestWorkDir = "/root"
	} else if !strings.HasPrefix(guestWorkDir, "/") || !strings.HasPrefix(guestWorkDir, "/panel/") {
		mapped, err := sandboxGuestPath(guestWorkDir, nil)
		if err == nil {
			guestWorkDir = mapped
		}
	}
	command := shellJoin(commandArgs...)
	cmd, _, err := createSandboxShellCommand(command, guestWorkDir, nil)
	return cmd, err
}

func createSandboxScriptCommand(interpreter, scriptPath string, scriptArgs []string, workDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	guestScript, err := sandboxGuestPath(scriptPath, envVars)
	if err != nil {
		return nil, nil, err
	}
	guestWorkDir, err := sandboxGuestPath(workDir, envVars)
	if err != nil {
		guestWorkDir = filepath.Dir(guestScript)
	}

	var command string
	switch {
	case IsPythonInterpreter(interpreter):
		command = shellJoin(append([]string{"python3", "-u", guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	case interpreter == "node":
		command = shellJoin(append([]string{"node", guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	case interpreter == "ts-node":
		command = shellJoin(append([]string{"npx", "ts-node", guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	case interpreter == "bash":
		command = shellJoin(append([]string{"bash", guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	case interpreter == "go":
		command = shellJoin(append([]string{"go", "run", guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	default:
		command = shellJoin(append([]string{interpreter, guestScript}, cleanManagedProcessArgs(scriptArgs)...)...)
	}

	return createSandboxShellCommand(command, guestWorkDir, envVars)
}

func createSandboxPythonModuleCommand(moduleName string, moduleArgs []string, workDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	if !isSafePythonModuleName(moduleName) {
		return nil, nil, fmt.Errorf("Python 模块名无效: %s", moduleName)
	}
	guestWorkDir, err := sandboxGuestPath(workDir, envVars)
	if err != nil {
		guestWorkDir = "/panel/scripts"
	}
	command := shellJoin(append([]string{"python3", "-u", "-m", moduleName}, cleanManagedProcessArgs(moduleArgs)...)...)
	return createSandboxShellCommand(command, guestWorkDir, envVars)
}

func createSandboxExecutableCommand(commandName string, commandArgs []string, workDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	if !isManagedExecutableName(commandName) {
		return nil, nil, fmt.Errorf("命令名无效: %s", commandName)
	}
	guestWorkDir, err := sandboxGuestPath(workDir, envVars)
	if err != nil {
		guestWorkDir = "/panel/scripts"
	}
	command := shellJoin(append([]string{commandName}, cleanManagedProcessArgs(commandArgs)...)...)
	return createSandboxShellCommand(command, guestWorkDir, envVars)
}

func installSandboxAutoDependency(candidate *AutoInstallCandidate, envVars map[string]string) AutoInstallResult {
	guestWorkDir, err := sandboxGuestPath(candidate.WorkDir, envVars)
	if err != nil {
		guestWorkDir = "/panel/scripts"
	}

	var command string
	switch candidate.Manager {
	case "python":
		command = shellJoin("python3", "-m", "pip", "install", "--break-system-packages", "-i", sandboxPipIndexURL, candidate.PackageName)
	case "nodejs":
		nodeDir := filepath.Join(config.C.Data.Dir, "deps", "nodejs")
		if err := ensureNodePackageManifest(nodeDir); err != nil {
			return AutoInstallResult{Error: err.Error()}
		}
		installSpec := ResolveNodeInstallPackageSpec(candidate.PackageName)
		command = shellJoin("npm", "install", "--registry", sandboxNpmRegistry, "--prefix", "/panel/data/deps/nodejs", installSpec)
		guestWorkDir = "/panel/data/deps/nodejs"
	case "go":
		command = shellJoin("go", "get", candidate.PackageName)
	default:
		return AutoInstallResult{Error: fmt.Sprintf("不支持的自动安装类型: %s", candidate.Manager)}
	}

	cmd, cleanup, err := createSandboxShellCommand(command, guestWorkDir, envVars)
	if err != nil {
		return AutoInstallResult{Error: err.Error()}
	}
	defer cleanup()
	out, runErr := cmd.CombinedOutput()
	return completeAutoInstall(candidate, out, runErr)
}

func CheckAndroidSandboxHealth() AndroidSandboxHealth {
	envVars := map[string]string{"DAIDAI_RUNTIME_MODE": os.Getenv("DAIDAI_RUNTIME_MODE")}
	if !sandboxRuntimeEnabled(envVars) {
		return AndroidSandboxHealth{Enabled: false, Status: "disabled"}
	}
	cfg, err := resolveSandboxRuntimeConfig(nil)
	if err != nil {
		return AndroidSandboxHealth{Enabled: true, Status: "error", Message: err.Error()}
	}

	checks := map[string]string{}
	probes := map[string]string{
		"shell":  "echo ok",
		"alpine": "cat /etc/alpine-release",
		"apk":    "apk --version",
		"python": "python3 --version",
		"pip":    "python3 -m pip --version",
		"node":   "node --version",
		"npm":    "npm --version",
		"bash":   "bash --version | head -1",
		"go":     "go version",
		"git":    "git --version",
	}
	status := "ok"
	message := "Linux 沙盒运行正常"
	for name, command := range probes {
		out, runErr := runSandboxHealthCommand(cfg, command)
		if runErr != nil {
			checks[name] = runErr.Error()
			status = "error"
			message = "Linux 沙盒运行时检查失败"
			continue
		}
		checks[name] = strings.TrimSpace(out)
	}

	return AndroidSandboxHealth{Enabled: true, Status: status, Message: message, Checks: checks}
}

func runSandboxHealthCommand(cfg sandboxRuntimeConfig, command string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	args := buildSandboxProotArgs(cfg, command)
	cmd := exec.CommandContext(ctx, cfg.Proot, args...)
	cmd.Env = buildSandboxHostEnv(cfg)
	out, err := cmd.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return string(out), fmt.Errorf("检查超时")
	}
	if err != nil {
		text := strings.TrimSpace(string(out))
		if text == "" {
			text = err.Error()
		}
		return string(out), fmt.Errorf("%s", text)
	}
	return string(out), nil
}

func createSandboxShellCommand(command, guestWorkDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	cfg, err := resolveSandboxRuntimeConfig(envVars)
	if err != nil {
		return nil, nil, err
	}
	if strings.TrimSpace(guestWorkDir) == "" {
		guestWorkDir = "/root"
	}

	envExports := buildSandboxEnvExports(envVars)
	shellCommand := "cd " + shellQuote(guestWorkDir) + " && " + envExports + command
	args := buildSandboxProotArgs(cfg, shellCommand)

	cmd := exec.Command(cfg.Proot, args...)
	cmd.Dir = "/"
	cmd.Env = buildSandboxHostEnv(cfg)
	setPgid(cmd)
	return cmd, func() {}, nil
}

func resolveSandboxRuntimeConfig(envVars map[string]string) (sandboxRuntimeConfig, error) {
	get := func(key string) string {
		if envVars != nil && strings.TrimSpace(envVars[key]) != "" {
			return strings.TrimSpace(envVars[key])
		}
		return strings.TrimSpace(os.Getenv(key))
	}

	cfg := sandboxRuntimeConfig{
		Rootfs:        get("DAIDAI_SANDBOX_ROOTFS"),
		Proot:         get("DAIDAI_SANDBOX_PROOT"),
		ProotLoader:   get("DAIDAI_SANDBOX_PROOT_LOADER"),
		ProotLoader32: get("DAIDAI_SANDBOX_PROOT_LOADER_32"),
		TmpDir:        get("DAIDAI_SANDBOX_TMPDIR"),
		NativeLibDir:  get("DAIDAI_SANDBOX_NATIVE_LIB_DIR"),
		Mounts:        parseSandboxMounts(get("DAIDAI_SANDBOX_MOUNTS")),
	}
	if cfg.Rootfs == "" || cfg.Proot == "" {
		return cfg, fmt.Errorf("Linux 沙盒未配置完整，缺少 rootfs 或 proot")
	}
	if !isExecutableFile(cfg.Proot) {
		return cfg, fmt.Errorf("PRoot 不可执行: %s", cfg.Proot)
	}
	if info, err := os.Stat(cfg.Rootfs); err != nil || !info.IsDir() {
		return cfg, fmt.Errorf("Linux rootfs 不可用: %s", cfg.Rootfs)
	}
	if cfg.TmpDir == "" {
		cfg.TmpDir = filepath.Join(os.TempDir(), "daidai-proot")
	}
	_ = os.MkdirAll(cfg.TmpDir, 0o700)
	return cfg, nil
}

func parseSandboxMounts(raw string) []sandboxMount {
	items := strings.Split(raw, "|")
	mounts := make([]sandboxMount, 0, len(items)+3)
	for _, item := range items {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		host, guest, ok := strings.Cut(item, ":")
		if !ok || strings.TrimSpace(host) == "" || strings.TrimSpace(guest) == "" {
			continue
		}
		mounts = append(mounts, sandboxMount{Host: strings.TrimSpace(host), Guest: strings.TrimSpace(guest)})
	}
	return mounts
}

func buildSandboxProotArgs(cfg sandboxRuntimeConfig, shellCommand string) []string {
	args := []string{
		"-0",
		"--link2symlink",
		"-r", cfg.Rootfs,
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
		"-w", "/root",
	}
	for _, mount := range cfg.Mounts {
		if strings.TrimSpace(mount.Host) == "" || strings.TrimSpace(mount.Guest) == "" {
			continue
		}
		args = append(args, "-b", mount.Host+":"+mount.Guest)
	}
	args = append(args, "/bin/sh", "-c", shellCommand)
	return args
}

func buildSandboxHostEnv(cfg sandboxRuntimeConfig) []string {
	env := []string{
		"PROOT_TMP_DIR=" + cfg.TmpDir,
	}
	if cfg.NativeLibDir != "" {
		env = append(env, "LD_LIBRARY_PATH="+cfg.NativeLibDir)
	}
	if cfg.ProotLoader != "" {
		env = append(env, "PROOT_LOADER="+cfg.ProotLoader)
	}
	if cfg.ProotLoader32 != "" {
		env = append(env, "PROOT_LOADER_32="+cfg.ProotLoader32)
	}
	if value := os.Getenv("PATH"); value != "" {
		env = append(env, "PATH="+value)
	}
	return env
}

func buildSandboxEnvExports(envVars map[string]string) string {
	merged := map[string]string{
		"PATH":                    "/panel/data/deps/nodejs/node_modules/.bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin",
		"HOME":                    "/root",
		"TMPDIR":                  "/tmp",
		"LANG":                    "C.UTF-8",
		"LC_ALL":                  "C.UTF-8",
		"TZ":                      CurrentPanelTimezone(),
		"DAIDAI_RUNTIME_MODE":     sandboxRuntimeMode,
		"DAIDAI_DATA_DIR":         "/panel/data",
		"DAIDAI_SCRIPTS_DIR":      "/panel/scripts",
		"DAIDAI_LOG_DIR":          "/panel/logs",
		"PYTHONDONTWRITEBYTECODE": "1",
		"GOMAXPROCS":              "2",
		"UV_LINK_MODE":            "symlink",
		"PIP_INDEX_URL":           sandboxPipIndexURL,
		"PIP_TRUSTED_HOST":        "mirrors.aliyun.com",
		"npm_config_registry":     sandboxNpmRegistry,
		"NODE_PATH":               "/panel/data/deps/nodejs/node_modules",
	}
	for key, value := range envVars {
		if !isValidShellEnvName(key) || isDangerousShellEnvName(key) || strings.ContainsRune(value, 0) {
			continue
		}
		if strings.HasPrefix(key, "DAIDAI_SANDBOX_") {
			continue
		}
		switch key {
		case "PATH", "HOME", "TMPDIR", "PYTHONHOME", "LD_LIBRARY_PATH", "PREFIX", "TERMUX_PREFIX", "NODE_PATH":
			continue
		}
		merged[key] = value
	}
	keys := make([]string, 0, len(merged))
	for key := range merged {
		keys = append(keys, key)
	}
	sortStrings(keys)

	var b strings.Builder
	for _, key := range keys {
		b.WriteString("export ")
		b.WriteString(key)
		b.WriteByte('=')
		b.WriteString(shellQuote(merged[key]))
		b.WriteString("; ")
	}
	return b.String()
}

func sandboxGuestPath(hostPath string, envVars map[string]string) (string, error) {
	hostPath = filepath.Clean(strings.TrimSpace(hostPath))
	if hostPath == "" || hostPath == "." {
		return "/root", nil
	}
	cfg, err := resolveSandboxRuntimeConfig(envVars)
	if err != nil {
		return "", err
	}
	for _, mount := range cfg.Mounts {
		hostRoot := filepath.Clean(mount.Host)
		if hostPath == hostRoot {
			return mount.Guest, nil
		}
		rel, relErr := filepath.Rel(hostRoot, hostPath)
		if relErr == nil && rel != "." && !strings.HasPrefix(rel, "..") {
			return filepath.ToSlash(filepath.Join(mount.Guest, rel)), nil
		}
	}
	return "", fmt.Errorf("路径未挂载到 Linux 沙盒: %s", hostPath)
}

func shellJoin(args ...string) string {
	quoted := make([]string, 0, len(args))
	for _, arg := range args {
		quoted = append(quoted, shellQuote(arg))
	}
	return strings.Join(quoted, " ")
}

func shellQuote(value string) string {
	if value == "" {
		return "''"
	}
	return "'" + strings.ReplaceAll(value, "'", `'\''`) + "'"
}

func sortStrings(items []string) {
	for i := 1; i < len(items); i++ {
		value := items[i]
		j := i - 1
		for j >= 0 && items[j] > value {
			items[j+1] = items[j]
			j--
		}
		items[j+1] = value
	}
}
