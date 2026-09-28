#!/bin/sh
# Stands in for the GitHub CLI in tests: records how it was called and prints the new pull request's URL. fake-gh.args
# holds the last call; fake-gh.calls every call, each ended by "--". `pr view` prints fake-gh.state, else OPEN.
printf '%s\n' "$@" > fake-gh.args
printf '%s\n' "$@" -- >> fake-gh.calls
env > fake-gh.env
case "$*" in
  *GH:fail*)
    echo "GraphQL: Resource not accessible by personal access token (createPullRequest)" >&2
    exit 1
    ;;
esac
if [ "$1 $2" = "pr view" ]; then
  if [ -f fake-gh.state ]; then cat fake-gh.state; else echo OPEN; fi
  exit 0
fi
echo "https://github.com/acme/autoland-management/pull/7"
