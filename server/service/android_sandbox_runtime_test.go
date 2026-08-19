package service

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSandboxGuestPathMapsMountedHostPath(t *testing.T) {
	tmp := t.TempDir()
	rootfs := filepath.Join(tmp, "rootfs")
	proot := filepath.Join(tmp, "libproot.so")
	scripts := filepath.Join(tmp, "data", "scripts")
	if err := os.MkdirAll(filepath.Join(scripts, "nested"), 0o755); err != nil {
		t.Fatalf("mkdir scripts: %v", err)
	}
	if err := os.MkdirAll(rootfs, 0o755); err != nil {
		t.Fatalf("mkdir rootfs: %v", err)
	}
	if err := os.WriteFile(proot, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatalf("write proot: %v", err)
	}

	env := map[string]string{
		"DAIDAI_RUNTIME_MODE":   sandboxRuntimeMode,
		"DAIDAI_SANDBOX_ROOTFS": rootfs,
		"DAIDAI_SANDBOX_PROOT":  proot,
		"DAIDAI_SANDBOX_MOUNTS": scripts + ":/panel/scripts",
	}

	guest, err := sandboxGuestPath(filepath.Join(scripts, "nested", "job.py"), env)
	if err != nil {
		t.Fatalf("map guest path: %v", err)
	}
	if guest != "/panel/scripts/nested/job.py" {
		t.Fatalf("guest path = %q", guest)
	}
}

func TestCreateSandboxScriptCommandBuildsProotInvocation(t *testing.T) {
	tmp := t.TempDir()
	rootfs := filepath.Join(tmp, "rootfs")
	proot := filepath.Join(tmp, "libproot.so")
	scripts := filepath.Join(tmp, "scripts")
	script := filepath.Join(scripts, "job.py")
	for _, dir := range []string{rootfs, scripts} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", dir, err)
		}
	}
	if err := os.WriteFile(proot, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatalf("write proot: %v", err)
	}
	if err := os.WriteFile(script, []byte("print('ok')\n"), 0o644); err != nil {
		t.Fatalf("write script: %v", err)
	}

	env := map[string]string{
		"DAIDAI_RUNTIME_MODE":   sandboxRuntimeMode,
		"DAIDAI_SANDBOX_ROOTFS": rootfs,
		"DAIDAI_SANDBOX_PROOT":  proot,
		"DAIDAI_SANDBOX_MOUNTS": scripts + ":/panel/scripts",
	}
	cmd, cleanup, err := createSandboxScriptCommand("python3", script, []string{"--flag"}, scripts, env)
	if err != nil {
		t.Fatalf("create command: %v", err)
	}
	defer cleanup()

	joined := strings.Join(cmd.Args, " ")
	for _, want := range []string{proot, "--link2symlink", rootfs, scripts + ":/panel/scripts", "python3", "/panel/scripts/job.py", "--flag"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("command %q missing %q", joined, want)
		}
	}
}

func TestBuildSandboxEnvExportsUsesRequestedMirrors(t *testing.T) {
	exports := buildSandboxEnvExports(map[string]string{
		"PATH":      "/host/bin",
		"NODE_PATH": "/host/node_modules",
	})
	for _, want := range []string{
		"PIP_INDEX_URL='https://mirrors.aliyun.com/pypi/simple/'",
		"PIP_TRUSTED_HOST='mirrors.aliyun.com'",
		"npm_config_registry='https://registry.npmmirror.com'",
		"PATH='/panel/data/deps/nodejs/node_modules/.bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin'",
		"NODE_PATH='/panel/data/deps/nodejs/node_modules'",
	} {
		if !strings.Contains(exports, want) {
			t.Fatalf("exports %q missing %q", exports, want)
		}
	}
	if strings.Contains(exports, "/host/bin") || strings.Contains(exports, "/host/node_modules") {
		t.Fatalf("host runtime paths leaked into sandbox env: %q", exports)
	}
}

func TestCheckAndroidSandboxHealthDisabledByDefault(t *testing.T) {
	t.Setenv("DAIDAI_RUNTIME_MODE", "")
	health := CheckAndroidSandboxHealth()
	if health.Enabled || health.Status != "disabled" {
		t.Fatalf("expected disabled sandbox health, got %#v", health)
	}
}
