# The checks testflight.sh makes before it builds, as functions its tests call directly.
# Source it; it defines functions only.

# True only inside a build-slot job: this process sits in a build-slot unit (lane-<id>.service)
# and the process holding the WSL build lock sits in that same unit, so build-slot itself holds
# it for this job (not another session's job).
in_build_slot() { # LOCK_FILE
  local lock=$1 mine inode pid holder
  mine=$(cat /proc/self/cgroup 2>/dev/null) || return 1
  [[ $mine =~ /lane-[A-Za-z0-9-]+\.service$ ]] || return 1
  inode=$(stat -c %i "$lock" 2>/dev/null) || return 1
  pid=$(awk -v ino="$inode" '$2 == "FLOCK" { n = split($6, f, ":"); if (f[n] == ino) { print $5; exit } }' /proc/locks)
  [ -n "$pid" ] || return 1
  holder=$(cat "/proc/$pid/cgroup" 2>/dev/null) || return 1
  [ "$holder" = "$mine" ]
}

# One field of the open reservation, or nothing.
reservation_field() { # ASC_HOME FIELD
  python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1/reserved.json" "$2" 2>/dev/null || true
}

# The commit the kept unsigned build was made from, or nothing.
unsigned_commit() { # WORK
  [ -f "$1/unsigned.from" ] && awk '{print $1}' "$1/unsigned.from" || true
}

# The ios folder and the store assets workflow of one commit, with Linux line endings.
export_tree() { # REPO SHA DEST
  mkdir -p "$3"
  git -c safe.directory='*' -C "$1" archive "$2" ios .github/workflows/ios-store-assets.yml | tar -x -C "$3"
}

# Text files with carriage returns, outside the store assets (whose bytes are the Mac's).
cr_files() { # DIR
  grep -rlI $'\r' --include='*.swift' --include='*.plist' --include='*.yml' --include='*.json' \
    --include='*.entitlements' "$1" 2>/dev/null | grep -v '/Resources/StoreAssets/' || true
}
