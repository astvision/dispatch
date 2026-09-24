#!/bin/sh
# Stands in for the claude CLI in tests: records how it was called, then behaves as the prompt asks.
# SCENARIO:<name> applies to any run. Otherwise a split replays the recorded three topics (one-topic: a single topic), a
# planning run replays the recorded plan, and an execution run (auto mode) edits README.md and replays the recorded
# execution unless an exec scenario (exec-fail, exec-fail-once, nochange, leaky-summary) says otherwise; those names never match the
# general scenarios, so such a task still gets its plan first.
# A worker's readiness check asks for --version before any run; answer it without recording anything or reading a prompt.
if [ "$1" = "--version" ]; then
  echo "2.1.280 (Claude Code)"
  exit 0
fi
printf '%s\n' "$@" > fake-claude.args
env > fake-claude.env
prompt=$(cat)
printf '%s' "$prompt" > fake-claude.prompt

mode=plan
case " $* " in
  *" --permission-mode auto "*) mode=auto ;;
  *" --no-session-persistence "*) mode=split ;;
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
    if [ "$mode" = split ]; then
      case "$prompt" in
        *SCENARIO:one-topic*)
          printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-split","permissionMode":"plan"}'
          printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"session_id":"fake-split","total_cost_usd":0.014,"num_turns":2,"permission_denials":[],"structured_output":{"topics":["Fix the login timeout"]}}'
          ;;
        *)
          cat "$FAKE_CLAUDE_FIXTURES/split-success.jsonl"
          ;;
      esac
      exit 0
    fi
    if [ "$mode" = plan ]; then
      cat "$FAKE_CLAUDE_FIXTURES/plan-success.jsonl"
      exit 0
    fi
    case "$prompt" in
      *SCENARIO:exec-fail-once*)
        # Fails the building session's first run only, so a retry that resumes it succeeds.
        case " $* " in
          *" --resume "*) ;;
          *)
            echo "fatal: model overloaded" >&2
            exit 1
            ;;
        esac
        ;;
      *SCENARIO:exec-fail*)
        echo "fatal: model overloaded" >&2
        exit 1
        ;;
      *SCENARIO:nochange*)
        cat "$FAKE_CLAUDE_FIXTURES/execute-success.jsonl"
        exit 0
        ;;
      *SCENARIO:exec-busy*)
        # Reports one real tool call, then hangs (like SCENARIO:sleep) instead of finishing: a test that needs a
        # genuinely RUNNING execution with real activity for /status to show — and, for another member, to withhold.
        printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-session","permissionMode":"auto"}'
        printf '%s\n' '{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"tool_use","name":"Bash","input":{"command":"cat internal-notes.txt"}}]}}'
        sleep 300 &
        echo $! > fake-claude.child
        wait
        ;;
      *SCENARIO:leaky-summary*)
        # Split, so secret scanners never see a token-shaped literal in this file.
        token="gh""p_0123456789abcdefghijABCDEFGHIJ012345"
        printf 'fixed by fake claude\n' >> README.md
        printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-session","permissionMode":"auto"}'
        printf '{"type":"result","subtype":"success","is_error":false,"session_id":"fake-session","total_cost_usd":0.2,"num_turns":3,"permission_denials":[],"result":"Configured the client with %s from the old script."}\n' "$token"
        exit 0
        ;;
    esac
    printf 'fixed by fake claude\n' >> README.md
    cat "$FAKE_CLAUDE_FIXTURES/execute-success.jsonl"
    exit 0
    ;;
esac
