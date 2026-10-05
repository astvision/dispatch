#!/bin/sh
# Stands in for the GitHub CLI in tests: records how it was called and prints the new pull request's URL. fake-gh.args
# holds the last call; fake-gh.calls every call, each ended by "--". `pr view` prints fake-gh.state, else OPEN.
# `pr view … headRefOid` prints fake-gh.pr, else an open pull request at abc123. `pr checks` prints fake-gh.checks and
# exits with fake-gh.checks.exit (0 when absent); without fake-gh.checks it reports no checks as gh does. `run view`
# prints fake-gh.log, else fails.
printf '%s\n' "$@" > fake-gh.args
printf '%s\n' "$@" -- >> fake-gh.calls
env > fake-gh.env
case "$*" in
  *GH:fail*)
    echo "GraphQL: Resource not accessible by personal access token (createPullRequest)" >&2
    exit 1
    ;;
esac
if [ "$1 $2" = "pr checks" ]; then
  if [ -f fake-gh.checks ]; then
    cat fake-gh.checks
    if [ -f fake-gh.checks.exit ]; then exit "$(cat fake-gh.checks.exit)"; fi
    exit 0
  fi
  echo "no checks reported on the 'dispatch/7' branch" >&2
  exit 1
fi
if [ "$1 $2" = "run view" ]; then
  if [ -f fake-gh.log ]; then cat fake-gh.log; exit 0; fi
  echo "run not found" >&2
  exit 1
fi
case "$1 $2 $*" in
  "pr view "*headRefOid*)
    if [ -f fake-gh.pr ]; then cat fake-gh.pr; else echo '{"headRefOid":"abc123","state":"OPEN"}'; fi
    exit 0
    ;;
esac
if [ "$1 $2" = "pr view" ]; then
  if [ -f fake-gh.state ]; then cat fake-gh.state; else echo OPEN; fi
  exit 0
fi
echo "https://github.com/acme/autoland-management/pull/7"
