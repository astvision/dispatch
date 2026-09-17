#!/bin/sh
# Stands in for the claude CLI in tests: records how it was called, then behaves as the prompt asks.
# SCENARIO:<name> applies to any run. Without one, a planning run replays the recorded plan and an execution run
# (auto mode) edits README.md and replays the recorded execution.
printf '%s\n' "$@" > fake-claude.args
env > fake-claude.env
prompt=$(cat)
printf '%s' "$prompt" > fake-claude.prompt

mode=plan
case " $* " in
  *" --permission-mode auto "*) mode=auto ;;
esac

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
    printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-session","permissionMode":"plan"}'
    printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"session_id":"fake-session","total_cost_usd":0.01,"num_turns":1,"permission_denials":[],"structured_output":{"understanding":"only this"}}'
    exit 0
    ;;
  *SCENARIO:wrong-mode*)
    # What Claude Code does when the model has no auto mode: it starts anyway, in default mode.
    printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-session","permissionMode":"default"}'
    sleep 300 &
    echo $! > fake-claude.child
    wait
    ;;
  *)
    if [ "$mode" = plan ]; then
      cat "$FAKE_CLAUDE_FIXTURES/plan-success.jsonl"
      exit 0
    fi
    printf 'fixed by fake claude\n' >> README.md
    cat "$FAKE_CLAUDE_FIXTURES/execute-success.jsonl"
    exit 0
    ;;
esac
