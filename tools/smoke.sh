#!/usr/bin/env bash
# A venv is created, a requirement is installed and a command runs inside it: no network, no site-packages.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"
printf '{"venv": ".venv", "command": "python -c \\"import sys;print(sys.prefix)\\""}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ -x "$dir/.venv/bin/python" ] || fail "no virtualenv was created"
grep -q "$dir/.venv" "$dir/atom-python-env.log" || grep -q "$dir/.venv" "$dir/log.txt" \
  || fail "the command did not run inside the virtualenv"
[ "$(output "$dir" VIRTUAL_ENV)" = "$dir/.venv" ] || fail "VIRTUAL_ENV: $(cat "$dir/output.json")"
case "$(output "$dir" PATH)" in
  "$dir/.venv/bin:"*) : ;;
  *) fail "PATH must start with the venv bin dir" ;;
esac

# a missing requirements file is a clear user error
dir="$(mktemp -d)"
printf '{"requirements": "nope.txt"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing requirements file must fail"
grep -q "no requirements file" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# a failing command fails the step unless told otherwise
dir="$(mktemp -d)"
printf '{"command": "exit 4"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "exit 4 must fail the atom"
grep -q "exited 4" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: venv, VIRTUAL_ENV/PATH outputs, missing requirements, failing command"
