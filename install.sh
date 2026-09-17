#!/bin/sh
# Installs Dispatch for the current user on macOS or Linux: builds it from source and puts `dispatch` on your PATH.
#
#   curl -fsSL https://raw.githubusercontent.com/astvision/dispatch/main/install.sh | sh
#
# While the repository is private, fetch it through the GitHub CLI instead:
#
#   gh api -H "Accept: application/vnd.github.raw" repos/astvision/dispatch/contents/install.sh | sh
#
# Needs git and Java 25 or later. Settings: DISPATCH_REF (branch or tag, default main), DISPATCH_HOME (where the jar goes,
# default ~/.local/share/dispatch), DISPATCH_BIN (where the dispatch command goes, default ~/.local/bin).
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

command -v git >/dev/null 2>&1 || fail "git is needed; install it and run this again"
command -v java >/dev/null 2>&1 || fail "Java 25 or later is needed, e.g. Temurin from https://adoptium.net"
java_version=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = \([0-9]*\).*/\1/p')
[ "${java_version:-0}" -ge 25 ] || fail "Java 25 or later is needed; java on your PATH is ${java_version:-unknown}"

# Build from this checkout when run from one, otherwise from a fresh clone.
source_dir=""
if [ -f "$0" ]; then
  script_dir=$(cd "$(dirname "$0")" && pwd)
  if [ -f "$script_dir/bin/dispatch" ] && grep -q '<artifactId>dispatch</artifactId>' "$script_dir/pom.xml" 2>/dev/null; then
    source_dir=$script_dir
  fi
fi
if [ -z "$source_dir" ]; then
  source_dir=$(mktemp -d)
  trap 'rm -rf "$source_dir"' EXIT
  step "Cloning $repo ($ref)"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh repo clone "$repo" "$source_dir" -- --quiet --depth 1 --branch "$ref"
  else
    git clone --quiet --depth 1 --branch "$ref" "https://github.com/$repo.git" "$source_dir"
  fi
fi

step "Building Dispatch (the first build also downloads Maven and the libraries)"
(cd "$source_dir" && sh ./mvnw --quiet --batch-mode -DskipTests package)
jar=$(find "$source_dir/target" -maxdepth 1 -name 'dispatch-*.jar' ! -name 'original-*' | head -n 1)
[ -n "$jar" ] || fail "the build made no jar in $source_dir/target"

step "Installing into $home"
mkdir -p "$home" "$bin"
cp "$jar" "$home/dispatch.jar"
cp "$source_dir/bin/dispatch" "$home/dispatch"
chmod 755 "$home/dispatch"
ln -sf "$home/dispatch" "$bin/dispatch"

case ":$PATH:" in
  *":$bin:"*) ;;
  # shellcheck disable=SC2016 # $PATH is printed unexpanded, for the person to paste
  *) printf '\n%s is not on your PATH yet. Add it, e.g.:\n  echo '"'"'export PATH="%s:$PATH"'"'"' >> ~/.profile\n' "$bin" "$bin" ;;
esac
step "Installed. Set it up with: dispatch init"
