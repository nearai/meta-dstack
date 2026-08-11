#!/usr/bin/env bash

set -euo pipefail

if [[ $(uname -s) != Linux ]]; then
	echo "Linux mount-namespace test skipped"
	exit 0
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
helper="$repo_root/meta-dstack/recipes-core/dstack-persistent-journal/files/dstack-persist-journal"
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

sudo env HELPER="$helper" TEST_ROOT="$tmp_dir/integration" \
	unshare --mount --propagation private bash -euo pipefail <<'EOF'
mkdir -p "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/journalctl" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "journalctl $*" >>"$JOURNALCTL_LOG"
SCRIPT
chmod +x "$TEST_ROOT/bin/journalctl"

persistent_dir="$TEST_ROOT/data/var/log/journal"
mountpoint_dir="$TEST_ROOT/volatile/var/log/journal"
journalctl_log="$TEST_ROOT/journalctl.log"

JOURNALCTL_LOG="$journalctl_log" PATH="$TEST_ROOT/bin:$PATH" \
	"$HELPER" "$persistent_dir" "$mountpoint_dir"
mountpoint -q "$mountpoint_dir"

marker="$persistent_dir/previous-boot-record"
printf '%s\n' retained >"$marker"
test -f "$mountpoint_dir/previous-boot-record"

# Recreate the volatile mountpoint while retaining the data-volume directory,
# matching the relevant boundary of a guest reboot.
umount "$mountpoint_dir"
rm -rf "$TEST_ROOT/volatile"
mkdir -p "$mountpoint_dir"

JOURNALCTL_LOG="$journalctl_log" PATH="$TEST_ROOT/bin:$PATH" \
	"$HELPER" "$persistent_dir" "$mountpoint_dir"
mountpoint -q "$mountpoint_dir"
test -f "$mountpoint_dir/previous-boot-record"
test "$(cat "$mountpoint_dir/previous-boot-record")" = retained

test "$(grep -c '^journalctl --flush$' "$journalctl_log")" -eq 2
test "$(grep -c '^journalctl --sync$' "$journalctl_log")" -eq 2
umount "$mountpoint_dir"
EOF

echo "persistent journal Linux mount validation passed"
