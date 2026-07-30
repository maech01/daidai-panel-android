package handler

import (
	"strconv"
	"strings"
)

// Version is the panel version. Default is "dev" so that local builds
// without ldflags injection do not report a misleading stale version.
// CI / release builds inject the real value via -ldflags "-X daidai-panel/handler.Version=$VERSION".
var Version = "dev"

// IsDevBuild reports whether the running binary is a development build
// (i.e. Version has not been injected via ldflags). Dev builds must not
// participate in auto-update checks, otherwise local `go run` would
// silently overwrite itself with the latest release.
func IsDevBuild() bool {
	v := strings.TrimSpace(Version)
	return v == "" || v == "dev" || strings.HasPrefix(v, "0.0.0-")
}

func compareVersions(current, latest string) bool {
	cur := parseVersion(current)
	lat := parseVersion(latest)
	for i := 0; i < 3; i++ {
		if cur[i] < lat[i] {
			return true
		}
		if cur[i] > lat[i] {
			return false
		}
	}
	return false
}

func parseVersion(v string) [3]int {
	var parts [3]int
	segs := strings.SplitN(v, ".", 3)
	for i, s := range segs {
		if i >= 3 {
			break
		}
		parts[i], _ = strconv.Atoi(s)
	}
	return parts
}
