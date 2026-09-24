#!/usr/bin/env python3
"""Runs the taskmanager evals in the assistant's real harness: the bot home's CLAUDE.md, with or without the skill,
Claude Code with the exact flags Dispatch uses, and a stand-in `dispatch ask` that serves fixture data.

usage: run_evals.py OUT_DIR [--model haiku] [--only ID,...]
"""
import json, os, re, shutil, subprocess, sys, pathlib, time, concurrent.futures

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]
HOME_SRC = REPO / "src/main/resources/assistant"
SCHEMA = json.dumps(json.load(open(REPO / "src/main/resources/assistant-schema.json")))
TOOLS = "Read,Grep,Glob,Bash,Skill"  # as ClaudeCodeAgent launches the assistant
CLAIMS = re.compile(r"(хариуллаа|цуцаллаа|үүсгэлээ|зөвшөөрлөө|татгалзлаа|илгээлээ|нэмлээ)")

def home(dir, with_skill, fixtures):
    shutil.copy(HOME_SRC / "CLAUDE.md", dir / "CLAUDE.md")
    if with_skill:
        shutil.copytree(HOME_SRC / ".claude", dir / ".claude")
    bin = dir / "bin"; bin.mkdir()
    (dir / "fixtures.json").write_text(json.dumps(fixtures))
    (bin / "dispatch").write_text(f"""#!/usr/bin/env python3
import json, sys
f = json.load(open({str(dir / 'fixtures.json')!r}))
key = " ".join(sys.argv[2:])
if sys.argv[1:2] != ["ask"]: sys.exit(2)
if key == "tasks": print(json.dumps({{"active": f["snapshot"], "finished": []}})); sys.exit(0)
if key in f["ask"]: print(json.dumps(f["ask"][key])); sys.exit(0)
print(json.dumps({{"error": "not_found"}})); sys.exit(1)
""")
    (bin / "dispatch").chmod(0o755)

def run(case, snapshot, fixtures, out, with_skill, model):
    run_dir = out / case["name"] / ("with_skill" if with_skill else "without_skill")
    work = run_dir / "home"; work.mkdir(parents=True)
    home(work, with_skill, {"snapshot": snapshot, "ask": fixtures})
    prompt = f"<dispatch-now>\n{json.dumps(snapshot, ensure_ascii=False)}\n</dispatch-now>\n\n<owner-message>\n{case['prompt']}\n</owner-message>\n"
    env = dict(os.environ, PATH=f"{work / 'bin'}:{os.environ['PATH']}")
    cmd = ["claude", "-p", "--output-format", "stream-json", "--verbose", "--permission-mode", "dontAsk",
           "--permission-prompts", "none", "--setting-sources", "project", "--strict-mcp-config", "--no-session-persistence",
           "--model", model, "--tools", TOOLS, "--json-schema", SCHEMA, "--allowedTools", "Bash(dispatch ask *)"]
    start = time.time()
    p = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=work, env=env, timeout=240)
    (run_dir / "transcript.jsonl").write_text(p.stdout)
    events = [json.loads(l) for l in p.stdout.splitlines() if l.startswith("{")]
    result = next((e for e in reversed(events) if e.get("type") == "result"), {})
    output = result.get("structured_output") or {}
    outputs = run_dir / "outputs"; outputs.mkdir()
    (outputs / "answer.json").write_text(json.dumps(output, ensure_ascii=False, indent=2))
    (run_dir / "timing.json").write_text(json.dumps({"total_tokens": (result.get("usage") or {}).get("output_tokens", 0),
        "duration_ms": int((time.time() - start) * 1000), "total_duration_seconds": round(time.time() - start, 1)}))
    grade(case, output, snapshot, run_dir)

def grade(case, output, snapshot, run_dir):
    actions, reply = output.get("actions", []), output.get("reply", "")
    projects = {p["name"] for p in snapshot["projects"]} | {p["alias"] for p in snapshot["projects"] if p["alias"]}
    def check(a):
        v, c = a.get("values", []), a["check"]
        if c == "no_actions": return not actions, f"actions: {actions}"
        if c == "reply_mentions": return all(str(x) in reply for x in v), reply
        if c == "action_types": return [x.get("type") for x in actions] == v, f"actions: {actions}"
        if c == "draft_project": return any(x.get("type") == "draft" and x.get("project") in v for x in actions), f"actions: {actions}"
        if c == "has_answer": return any(x.get("type") == "answer" and x.get("task") == v[0] and x.get("question") == v[1] and x.get("option") == v[2] for x in actions), f"actions: {actions}"
        if c == "no_type": return all(x.get("type") not in v for x in actions), f"actions: {actions}"
        if c == "has_task_action": return any(x.get("type") == v[0] and x.get("task") == v[1] for x in actions), f"actions: {actions}"
        if c == "no_claim": m = CLAIMS.search(reply); return m is None, f"reply: {reply}"
        if c == "asks": return "?" in reply, f"reply: {reply}"
        if c == "no_invented_project": return all(x.get("project") in (None, *projects) for x in actions), f"actions: {actions}"
        raise ValueError(c)
    exps = [{"text": a["text"], "passed": ok, "evidence": ev} for a in case["assertions"] for ok, ev in [check(a)]]
    passed = sum(e["passed"] for e in exps)
    (run_dir / "grading.json").write_text(json.dumps({"expectations": exps, "summary": {"passed": passed, "failed": len(exps) - passed,
        "total": len(exps), "pass_rate": passed / len(exps)}}, ensure_ascii=False, indent=2))

def main():
    out = pathlib.Path(sys.argv[1]); model = "haiku"; only = None
    if "--model" in sys.argv: model = sys.argv[sys.argv.index("--model") + 1]
    if "--only" in sys.argv: only = {int(x) for x in sys.argv[sys.argv.index("--only") + 1].split(",")}
    spec = json.load(open(HERE / "evals.json"))
    cases = [c for c in spec["evals"] if only is None or c["id"] in only]
    for c in cases:
        d = out / c["name"]; d.mkdir(parents=True, exist_ok=True)
        (d / "eval_metadata.json").write_text(json.dumps({"eval_id": c["id"], "eval_name": c["name"], "prompt": c["prompt"],
            "assertions": [a["text"] for a in c["assertions"]]}, ensure_ascii=False))
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        jobs = [pool.submit(run, c, spec["snapshot"], spec["ask_fixtures"], out, w, model) for c in cases for w in (True, False)]
        for j in jobs: j.result()
    for c in cases:
        for w in ("with_skill", "without_skill"):
            g = json.load(open(out / c["name"] / w / "grading.json"))["summary"]
            print(f"{c['name']:42} {w:14} {g['passed']}/{g['total']}")

main()
