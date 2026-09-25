#!/bin/sh
# Stands in for the codex CLI in tests: records how it was called, then answers as `codex exec --json` does.
# SCENARIO:fail in the prompt fails the turn; SCENARIO:sleep hangs until cancelled (the child's pid in fake-codex.child).
if [ "$1" = "--version" ]; then
  echo "codex-cli 0.155.1"
  exit 0
fi
printf '%s\n' "$@" > fake-codex.args
env > fake-codex.env
prompt=$(cat)
printf '%s' "$prompt" > fake-codex.prompt

printf '%s\n' '{"type":"thread.started","thread_id":"01a0d798-5aab-74d2-b4c8-a8c6ea63d8c1"}'
printf '%s\n' '{"type":"turn.started"}'
case "$prompt" in
  *SCENARIO:fail*)
    printf '%s\n' '{"type":"turn.failed","error":{"message":"model overloaded"}}'
    exit 1
    ;;
  *SCENARIO:sleep*)
    sleep 300 &
    echo $! > fake-codex.child
    wait
    ;;
esac
case " $* " in
  *" --output-schema "*)
    printf '%s\n' '{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}"}}'
    ;;
  *)
    printf '%s\n' '{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"done"}}'
    ;;
esac
printf '%s\n' '{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1}}'
