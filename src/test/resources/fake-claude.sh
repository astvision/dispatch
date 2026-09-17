#!/bin/sh
# Stands in for the claude CLI in tests: records how it was called, then behaves as the prompt asks.
printf '%s\n' "$@" > fake-claude.args
env > fake-claude.env
prompt=$(cat)
printf '%s' "$prompt" > fake-claude.prompt

case "$prompt" in
  *SCENARIO:fail*)
    echo "fatal: model overloaded" >&2
    exit 1
    ;;
  *SCENARIO:sleep*)
    sleep 300 &
    echo $! > fake-claude.child
    wait
    ;;
  *SCENARIO:ignore-term*)
    trap '' TERM
    echo $$ > fake-claude.child
    sleep 300
    ;;
  *SCENARIO:budget*)
    cat "$FAKE_CLAUDE_FIXTURES/plan-budget-exceeded.jsonl"
    exit 1
    ;;
  *SCENARIO:badplan*)
    printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-session"}'
    printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"session_id":"fake-session","total_cost_usd":0.01,"num_turns":1,"permission_denials":[],"structured_output":{"understanding":"only this"}}'
    exit 0
    ;;
  *)
    cat "$FAKE_CLAUDE_FIXTURES/plan-success.jsonl"
    exit 0
    ;;
esac
