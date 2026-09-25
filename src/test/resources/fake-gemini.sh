#!/bin/sh
# Stands in for the gemini CLI in tests: records how it was called, then answers as `gemini -o stream-json` does.
# SCENARIO:fail in the prompt ends with an error result.
if [ "$1" = "--version" ]; then
  echo "0.61.0"
  exit 0
fi
printf '%s\n' "$@" > fake-gemini.args
env > fake-gemini.env
prompt=$(cat)
printf '%s' "$prompt" > fake-gemini.prompt

session=unknown
previous=
for arg in "$@"; do
  case "$previous" in
    --session-id|--resume) session=$arg ;;
  esac
  previous=$arg
done
printf '{"type":"init","timestamp":"t","session_id":"%s","model":"gemini-2.5-pro"}\n' "$session"
case "$prompt" in
  *SCENARIO:fail*)
    printf '%s\n' '{"type":"result","timestamp":"t","status":"error","error":{"type":"api_error","message":"model overloaded"}}'
    exit 1
    ;;
esac
case " $* " in
  *"JSON Schema"*)
    printf '%s\n' '{"type":"message","timestamp":"t","role":"assistant","content":"{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}","delta":true}'
    ;;
  *)
    printf '%s\n' '{"type":"message","timestamp":"t","role":"assistant","content":"done","delta":true}'
    ;;
esac
printf '%s\n' '{"type":"result","timestamp":"t","status":"success"}'
