#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
helper="$repo_root/meta-dstack/recipes-core/dstack-persistent-journal/files/dstack-persist-journal"
recipe="$repo_root/meta-dstack/recipes-core/dstack-persistent-journal/dstack-persistent-journal.bb"
unit_dir="$repo_root/meta-dstack/recipes-core/dstack-persistent-journal/files"
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

fake_bin="$tmp_dir/bin"
command_log="$tmp_dir/commands.log"
mkdir -p "$fake_bin"

cat >"$fake_bin/fake-command" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
command_name=$(basename "$0")
rendered="$command_name${*:+ $*}"
printf '%s\n' "$rendered" >>"$COMMAND_LOG"

if [[ ${FAIL_MATCH:-} == "$rendered" ]]; then
	exit 42
fi

case "$command_name" in
mountpoint)
	[[ ${MOUNTPOINT_PRESENT:-0} == 1 ]]
	;;
stat)
	printf '%s\n' "${STAT_IDENTITY:-1:1}"
	;;
esac
EOF
chmod +x "$fake_bin/fake-command"
for command_name in chown chmod mount mountpoint stat journalctl; do
	ln -s fake-command "$fake_bin/$command_name"
done

run_helper() {
	COMMAND_LOG="$command_log" PATH="$fake_bin:$PATH" "$helper" "$@"
}

persistent_dir="$tmp_dir/persistent/var/log/journal"
mountpoint_dir="$tmp_dir/var/log/journal"
run_helper "$persistent_dir" "$mountpoint_dir"

test -d "$persistent_dir"
test -d "$mountpoint_dir"
expected_log="$tmp_dir/expected.log"
cat >"$expected_log" <<EOF
chown root:systemd-journal $persistent_dir $mountpoint_dir
chmod 2755 $persistent_dir $mountpoint_dir
mountpoint -q $mountpoint_dir
mount --rbind $persistent_dir $mountpoint_dir
stat -Lc %d:%i $persistent_dir
stat -Lc %d:%i $mountpoint_dir
journalctl --flush
journalctl --sync
EOF
diff -u "$expected_log" "$command_log"

: >"$command_log"
MOUNTPOINT_PRESENT=1 COMMAND_LOG="$command_log" PATH="$fake_bin:$PATH" \
	"$helper" "$persistent_dir" "$mountpoint_dir"
if grep -q '^mount --rbind ' "$command_log"; then
	echo "helper remounted an existing journal mount" >&2
	exit 1
fi

: >"$command_log"
set +e
FAIL_MATCH="mount --rbind $persistent_dir $mountpoint_dir" \
	COMMAND_LOG="$command_log" PATH="$fake_bin:$PATH" \
	"$helper" "$persistent_dir" "$mountpoint_dir"
mount_status=$?
set -e
if [[ $mount_status -ne 42 ]]; then
	echo "mount failure did not propagate" >&2
	exit 1
fi
if grep -q '^journalctl ' "$command_log"; then
	echo "journal was flushed after the persistent mount failed" >&2
	exit 1
fi

: >"$command_log"
set +e
FAIL_MATCH='journalctl --flush' COMMAND_LOG="$command_log" \
	PATH="$fake_bin:$PATH" "$helper" "$persistent_dir" "$mountpoint_dir"
flush_status=$?
set -e
if [[ $flush_status -ne 42 ]]; then
	echo "journal flush failure did not propagate" >&2
	exit 1
fi
if grep -q '^journalctl --sync$' "$command_log"; then
	echo "journal sync ran after flush failed" >&2
	exit 1
fi

grep -Fq '    dstack-persistent-journal ' \
	"$repo_root/meta-dstack/recipes-core/images/dstack-rootfs-base.inc"
grep -Fq "SYSTEMD_SERVICE:\${PN} = \"dstack-persistent-journal.service\"" "$recipe"
grep -Fq 'Storage=auto' "$unit_dir/20-dstack-persistent-journal.conf"
grep -Fq 'Requires=dstack-prepare.service var-volatile.mount' \
	"$unit_dir/dstack-persistent-journal.service"
grep -Fq 'Before=app-compose.service containerd.service docker.service dstack-guest-agent.service wg-checker.service' \
	"$unit_dir/dstack-persistent-journal.service"

bash -n "$helper" "$repo_root/tests/test-persistent-journal.sh"
echo "persistent journal validation passed"
