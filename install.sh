#!/bin/sh
# Installs Dispatch for the current user on macOS or Linux: downloads the latest release (or builds it from source) and puts
# `dispatch` on your PATH.
#
#   curl -fsSL https://raw.githubusercontent.com/astvision/dispatch/main/install.sh | sh
#
# While the repository is private, fetch it through the GitHub CLI instead:
#
#   gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.sh | sh
#
# Needs git and Java 25 or later. Settings: DISPATCH_REF (branch or tag, default main), DISPATCH_HOME (where the jar goes,
# default ~/.local/share/dispatch), DISPATCH_BIN (where the dispatch command goes, default ~/.local/bin),
# DISPATCH_FROM_SOURCE=1 (build from source instead of downloading).
set -eu

repo="${DISPATCH_REPO:-astvision/dispatch}"
ref="${DISPATCH_REF:-main}"
home="${DISPATCH_HOME:-$HOME/.local/share/dispatch}"
bin="${DISPATCH_BIN:-$HOME/.local/bin}"

if [ -t 1 ]; then
  step() { printf '\033[1;36m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
else
  step() { printf '==> %s\n' "$*"; }
fi
fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

command -v java >/dev/null 2>&1 || fail "Java 25 or later is needed, e.g. Temurin from https://adoptium.net"
java_version=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = \([0-9]*\).*/\1/p')
[ "${java_version:-0}" -ge 25 ] || fail "Java 25 or later is needed; java on your PATH is ${java_version:-unknown}"

# Downloads the release's jar and launcher into $1 and verifies both against SHA256SUMS; returns 1 (with the download
# command's own error on stderr) when there is no release or the download otherwise fails.
download_release() {
  dir=$1
  if [ "${ref#v}" != "$ref" ]; then release=$ref; else release=latest; fi
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if [ "$release" = latest ]; then set -- ; else set -- "$release"; fi
    gh release download "$@" --repo "$repo" --dir "$dir" --pattern dispatch.jar --pattern dispatch --pattern SHA256SUMS || return 1
  else
    if [ "$release" = latest ]; then base="https://github.com/$repo/releases/latest/download"; else base="https://github.com/$repo/releases/download/$release"; fi
    for asset in dispatch.jar dispatch SHA256SUMS; do
      curl -fsSL -o "$dir/$asset" "$base/$asset" || return 1
    done
  fi
  # Verify exactly the two files we install; SHA256SUMS also lists dispatch.cmd, which we didn't download.
  lines=$(grep -E '  (dispatch\.jar|dispatch)$' "$dir/SHA256SUMS")
  [ "$(printf '%s\n' "$lines" | grep -c .)" -eq 2 ] || fail "SHA256SUMS does not list both dispatch.jar and dispatch"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dir" && printf '%s\n' "$lines" | sha256sum -c - >/dev/null) || fail "the downloaded dispatch.jar or dispatch does not match its checksum"
  else
    (cd "$dir" && printf '%s\n' "$lines" | shasum -a 256 -c - >/dev/null) || fail "the downloaded dispatch.jar or dispatch does not match its checksum"
  fi
}

# From a checkout, or with DISPATCH_FROM_SOURCE=1: build from source. Otherwise: download the release, and build from a
# fresh clone only when there is none.
source_dir=""
if [ -f "$0" ]; then
  script_dir=$(cd "$(dirname "$0")" && pwd)
  if [ -f "$script_dir/bin/dispatch" ] && grep -q '<artifactId>dispatch</artifactId>' "$script_dir/pom.xml" 2>/dev/null; then
    source_dir=$script_dir
  fi
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar="" launcher=""
# DISPATCH_REF must resolve to a release (main -> latest, v* -> that tag) to be downloadable; any other ref names a
# branch or commit, which only a source build can produce.
attempt_download=""
case "$ref" in
  main | v*) attempt_download=1 ;;
esac
if [ -z "$source_dir" ] && [ -z "${DISPATCH_FROM_SOURCE:-}" ] && [ -n "$attempt_download" ]; then
  step "Downloading Dispatch ($repo)"
  if download_release "$work"; then
    jar="$work/dispatch.jar" launcher="$work/dispatch"
  else
    step "Could not download a release (see above); building from source instead (no web UI)"
  fi
fi
if [ -z "$jar" ]; then
  if [ -z "$source_dir" ]; then
    source_dir="$work/source"
    command -v git >/dev/null 2>&1 || fail "git is needed; install it and run this again"
    step "Cloning $repo ($ref)"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
      gh repo clone "$repo" "$source_dir" -- --quiet --depth 1 --branch "$ref"
    else
      git clone --quiet --depth 1 --branch "$ref" "https://github.com/$repo.git" "$source_dir"
    fi
  fi
  step "Building Dispatch (the first build also downloads Maven and the libraries; no web UI in a source build)"
  (cd "$source_dir" && sh ./mvnw --quiet --batch-mode -DskipTests package)
  jar=$(find "$source_dir/target" -maxdepth 1 -name 'dispatch-*.jar' ! -name 'original-*' | head -n 1)
  [ -n "$jar" ] || fail "the build made no jar in $source_dir/target"
  launcher="$source_dir/bin/dispatch"
fi

step "Installing into $home"
mkdir -p "$home" "$bin"
cp "$jar" "$home/dispatch.jar"
cp "$launcher" "$home/dispatch"
chmod 755 "$home/dispatch"
ln -sf "$home/dispatch" "$bin/dispatch"

# shellcheck disable=SC2016 # $PATH is printed unexpanded, for the person to paste
case ":$PATH:" in
  *":$bin:"*) ;;
  *) printf '\n%s is not on your PATH yet. Add it, e.g.:\n  echo '"'"'export PATH="%s:$PATH"'"'"' >> ~/.profile\n' "$bin" "$bin" ;;
esac
step "Installed. Set it up with: dispatch init, or in your browser: dispatch ui"
