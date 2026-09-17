#!/bin/sh
# Stands in for the GitHub CLI in tests: records how it was called and prints the new pull request's URL.
printf '%s\n' "$@" > fake-gh.args
env > fake-gh.env
case "$*" in
  *GH:fail*)
    echo "GraphQL: Resource not accessible by personal access token (createPullRequest)" >&2
    exit 1
    ;;
esac
echo "https://github.com/acme/autoland-management/pull/7"
