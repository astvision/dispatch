/** One line of Dispatch's log (logfmt, as Log.java writes it), for the Logs page. */
export interface LogRow {
  line: string;
  ts: string | null;
  level: string | null;
  event: string | null;
  task: string | null;
  fields: [string, string][];
}

const PICKED = new Set(["ts", "level", "event", "task"]);

/** Never throws: a line that is not logfmt (a stack trace, a line the tail cut) keeps only its text. */
export function parseLogLine(line: string): LogRow {
  const pairs: [string, string][] = [];
  let i = 0;
  while (i < line.length) {
    while (line[i] === " ") i++;
    const eq = line.indexOf("=", i);
    const key = eq < 0 ? "" : line.slice(i, eq);
    if (eq < 0 || !/^[A-Za-z_][A-Za-z0-9_.]*$/.test(key)) break;
    i = eq + 1;
    let value = "";
    if (line[i] === '"') {
      i++;
      // Log.java escapes \, ", a line feed and a carriage return inside quotes; anything else stands for itself.
      while (i < line.length && line[i] !== '"') {
        if (line[i] === "\\" && i + 1 < line.length) {
          const next = line[i + 1];
          value += next === "n" ? "\n" : next === "r" ? "\r" : next;
          i += 2;
        } else {
          value += line[i++];
        }
      }
      i++;
    } else {
      const end = line.indexOf(" ", i);
      value = end < 0 ? line.slice(i) : line.slice(i, end);
      i = end < 0 ? line.length : end;
    }
    pairs.push([key, value]);
  }
  const get = (key: string) => pairs.find(([name]) => name === key)?.[1] ?? null;
  if (get("ts") === null || get("event") === null) {
    return { line, ts: null, level: null, event: null, task: null, fields: [] };
  }
  return { line, ts: get("ts"), level: get("level"), event: get("event"), task: get("task"),
    fields: pairs.filter(([key]) => !PICKED.has(key)) };
}
