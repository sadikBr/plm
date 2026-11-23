# plm-sync

Keeps a folder on your computer in sync with a PLM workspace (roadmap 8.3). Edit the files with
any tool (CAD, Office, a text editor); `plm-sync` tells the system what changed, sends your
changes into your working copies, and brings down what others checked in. It never overwrites a
file you changed: if the system has a newer version too, that one is saved next to yours as
`name (B.1).ext`, and the workspace shows a conflict.

Needs Node 22 or newer, nothing else.

```bash
npm install -g ./clients/plm-sync
```

## Use

1. In the web UI, **My preferences → Access tokens → Make a token** (copy it: it's shown once).
2. Sign in, then sync a workspace into a folder:

```bash
plm-sync login --server http://localhost:8080 --token plm_…
plm-sync init "Seal battery pack update" ~/PLM/seal-battery-pack
cd ~/PLM/seal-battery-pack
```

| Command | What it does |
|---|---|
| `plm-sync status` | Each file's state: in sync, modified, out of date, checked out, conflict, someone else's, new |
| `plm-sync pull` | Brings out-of-date files up to date (keeps yours on a conflict) |
| `plm-sync push` | Sends changed files (checking them out) and new files; one that can't go doesn't stop the rest |
| `plm-sync checkin -m "…"` | Pushes, then checks in everything you have checked out in the workspace, all or nothing |
| `plm-sync watch` | Pushes as you save, pulls every 30 s |
| `plm-sync list` | Your workspaces |

`PLM_SERVER` and `PLM_TOKEN` in the environment replace `login` (for scripts and CI).

The folder's `.plm-sync.json` records, per file, the object, the iteration it came from and its
SHA-256: that is how changes here are told apart from changes in the system.

## Tests

```bash
npm test                                   # unit tests
PLM_ADMIN_TOKEN=$(../../scripts/token.sh) npm test   # plus the full round trip against ./plm start
```
