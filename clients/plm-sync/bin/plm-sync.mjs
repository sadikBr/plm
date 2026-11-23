#!/usr/bin/env node
// plm-sync: a folder on your computer, kept in sync with a PLM workspace (roadmap 8.3).
import { resolve } from "node:path";
import { Api, checkin, init, loadConfig, pull, push, saveConfig, status, watch } from "../lib/sync.mjs";

const HELP = `plm-sync: keep a folder in sync with a workspace

  plm-sync login --server <url> --token <token>   sign in with a personal token (My preferences → Access tokens)
  plm-sync list                                   your workspaces
  plm-sync init <workspace> [folder]              sync a workspace (by name or id) into a folder
  plm-sync status [folder]                        what changed here and on the server
  plm-sync pull [folder]                          bring out-of-date files up to date
  plm-sync push [folder]                          send changed and new files
  plm-sync checkin [folder] -m "comment"          push, then check in everything you have checked out
  plm-sync watch [folder]                         push changes as you save, pull new versions every 30 s

Environment: PLM_SERVER and PLM_TOKEN instead of login.`;

function args(argv) {
  const positional = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) flags[a.slice(2)] = argv[i + 1]?.startsWith("-") ? true : argv[++i];
    else if (a === "-m") flags.m = argv[++i];
    else positional.push(a);
  }
  return { positional, flags };
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  const { positional, flags } = args(rest);
  if (!command || command === "help" || flags.help) return console.log(HELP);
  if (command === "login") {
    if (!flags.server || !flags.token) throw new Error("Give --server and --token");
    const api = new Api(flags.server, flags.token);
    const me = await api.get("/api/v1/me");
    await saveConfig({ server: api.server, token: flags.token });
    return console.log(`Signed in to ${api.server} as ${me.user.displayName}`);
  }
  const config = await loadConfig();
  const api = new Api(config.server, config.token);
  const dir = resolve(positional[command === "init" ? 1 : 0] ?? ".");
  switch (command) {
    case "list": {
      for (const w of await api.get("/api/v1/workspaces")) {
        const n = Object.values(w.counts ?? {}).reduce((a, b) => a + b, 0);
        console.log(`${w.name}  (${n} files)  ${w.id}`);
      }
      return;
    }
    case "init":
      if (!positional[0]) throw new Error("Which workspace? plm-sync init <workspace> [folder]");
      await init(api, positional[0], resolve(positional[1] ?? positional[0]));
      return;
    case "status": {
      const { workspace, rows } = await status(api, dir);
      console.log(workspace.name);
      const w = Math.max(5, ...rows.map((r) => r.state.length));
      const n = Math.max(6, ...rows.map((r) => r.number.length));
      for (const r of rows) console.log(`  ${r.state.padEnd(w)}  ${r.number.padEnd(n)}  ${r.version.padEnd(10)}  ${r.file}`);
      return;
    }
    case "pull":
      console.log(`${await pull(api, dir)} file(s) brought up to date`);
      return;
    case "push":
      console.log(`${await push(api, dir)} file(s) sent`);
      return;
    case "checkin":
      await checkin(api, dir, flags.m);
      return;
    case "watch": {
      const controller = new AbortController();
      process.on("SIGINT", () => controller.abort());
      await watch(api, dir, { signal: controller.signal, every: Number(flags.every ?? 30) * 1000 });
      return;
    }
    default:
      throw new Error(`Unknown command “${command}”\n\n${HELP}`);
  }
}

main().catch((e) => {
  console.error(`plm-sync: ${e.message}`);
  process.exit(1);
});
