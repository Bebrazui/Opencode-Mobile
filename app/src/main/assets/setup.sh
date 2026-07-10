#!/bin/bash
echo '[SETUP] START'

ALPINE_ROOT="${HOME}/alpine/alpine/rootfs"
ROOTFS_TAR="${HOME}/../alpine-rootfs.tar"
OPENCODE_PORT=4096

PREFIX="${HOME}/../usr"
PROOT="$PREFIX/bin/proot-static"
export LD_LIBRARY_PATH="$PREFIX/lib:$LD_LIBRARY_PATH"

PROOT_TMP_DIR="$PREFIX/tmp"
mkdir -p "$PROOT_TMP_DIR"
chmod 1777 "$PROOT_TMP_DIR" 2>/dev/null
export PROOT_TMP_DIR

TEXEC="$PREFIX/lib/libtermux-exec-ld-preload.so"
export LD_PRELOAD="$TEXEC"
export LD_LIBRARY_PATH="$ALPINE_ROOT/lib:$PREFIX/lib:$LD_LIBRARY_PATH"
export PATH="$PREFIX/bin:$PATH"

echo "[SETUP] HOME=$HOME"
echo "[SETUP] pwd=$(pwd)"
echo "[SETUP] uname=$(uname -a 2>&1)"
echo "[SETUP] PREFIX=$PREFIX"
echo "[SETUP] PROOT=$PROOT exists=$(test -f "$PROOT" && echo yes || echo no)"
echo "[SETUP] PROOT_TMP_DIR=$PROOT_TMP_DIR"

echo '[SETUP] Step 1: Extracting Alpine rootfs...'
if [ ! -d "$ALPINE_ROOT" ]; then
  mkdir -p "${HOME}/alpine"
  cd "${HOME}/alpine"
  tar xzf "$ROOTFS_TAR" 2>&1
  echo "[SETUP] tar exit=$?"
else
  echo '[SETUP] Rootfs exists, skip'
fi

chmod -R 755 "$ALPINE_ROOT" 2>/dev/null

mkdir -p "${ALPINE_ROOT}/tmp" "${ALPINE_ROOT}/proc" "${ALPINE_ROOT}/dev" "${ALPINE_ROOT}/sys" 2>/dev/null
chmod 1777 "${ALPINE_ROOT}/tmp" 2>/dev/null

echo "[SETUP] ALPINE_ROOT=$ALPINE_ROOT"
ls -la "$ALPINE_ROOT/bin/sh" 2>&1

echo "[SETUP] DIAG proot test:"
"$PROOT" -r "$ALPINE_ROOT" /bin/busybox echo hi 2>&1
echo "[SETUP] proot test exit=$?"

echo "[SETUP] DIAG proot --version:"
"$PROOT" --version 2>&1 | head -2

run() {
  "$PROOT" -r "$ALPINE_ROOT" -w /root --link2symlink -b /dev -b /proc -b /sys "$@"
}

echo '[SETUP] Step 2: Testing PRoot...'
PROOT_TEST=$(run /bin/sh -c "echo MUSL_OK" 2>&1)
PROOT_EXIT=$?
echo "[SETUP] proot test exit=$PROOT_EXIT output=$PROOT_TEST"

if ! echo "$PROOT_TEST" | grep -q "MUSL_OK"; then
  echo "[SETUP] FATAL: PRoot does not work. Cannot continue."
  exit 1
fi

echo "[SETUP] PRoot WORKS!"

echo '[SETUP] Step 3: Testing Alpine tools...'
echo "[SETUP] sh: $(run /bin/sh -c 'echo sh_ok' 2>&1)"
echo "[SETUP] ls: $(run /bin/ls / 2>&1 | head -5)"
echo "[SETUP] git: $(run /usr/bin/git --version 2>&1)"
echo "[SETUP] node: $(run /usr/bin/node --version 2>&1)"
echo "[SETUP] npm: $(run /usr/bin/npm --version 2>&1)"
echo "[SETUP] curl: $(run /usr/bin/curl --version 2>&1 | head -1)"

echo '[SETUP] Step 4: Installing OpenCode...'
OPENCODE_DIR="${ALPINE_ROOT}/root/opencode"

if [ ! -d "$OPENCODE_DIR" ]; then
  echo '[SETUP] Downloading OpenCode...'
  run /usr/bin/git clone --depth 1 -b dev https://github.com/anomalyco/opencode.git "$OPENCODE_DIR" 2>&1 | tail -5
  echo "[SETUP] git exit=$?"
else
  echo '[SETUP] OpenCode dir exists'
fi

echo "[SETUP] Step 5: npm install..."
cd "$OPENCODE_DIR" 2>/dev/null
if [ -f "package.json" ]; then
  run /usr/bin/npm install 2>&1 | tail -5
  echo "[SETUP] npm exit=$?"
else
  echo '[SETUP] No package.json'
fi

echo "[SETUP] Step 6: Starting OpenCode server on port $OPENCODE_PORT..."
run /usr/bin/node packages/opencode/src/index.js serve --port $OPENCODE_PORT --hostname 0.0.0.0 2>&1 &
SERVER_PID=$!
echo "[SETUP] Server PID=$SERVER_PID"

sleep 3
echo '[SETUP] Checking port...'
run /usr/bin/curl -s "http://127.0.0.1:$OPENCODE_PORT" > /dev/null 2>&1 && echo "[SETUP] OK port $OPENCODE_PORT" || echo "[SETUP] FAIL port $OPENCODE_PORT"

echo '[SETUP] OpenCode server started'
wait