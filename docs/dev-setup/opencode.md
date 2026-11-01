# opencode setup

Target version: OpenCode 2 (at least 2.2). Verified version: 2.2.5.

## Upgrade

```sh
opencode upgrade --major --method curl
opencode --version
opencode auth list
```

V2 and V1 are not installed side by side; the curl installer replaces the V1 binary. Settings and credentials stay in the same locations.

## Verification

```sh
opencode api GET /api/info
.claude/scripts/run-security.sh --dir <repo with a small change>
```

`run-security.sh` prints `OK`, or one `- file:line — problem` line per finding. Exit code 5 is a tool error and does not count as a pass.

## Security check

`run-security.sh` does not use opencode. It reaches GLM through Claude Code and reads the settings in `~/.claude/glm.json`. This file contains the key and stays out of the repo. For a different path, use the `GLM_SETTINGS` environment variable.

File format:

```json
{
  "env": {
    "ANTHROPIC_AUTH_TOKEN": "<Z.AI key>",
    "ANTHROPIC_BASE_URL": "https://api.z.ai/api/anthropic"
  }
}
```

If the file is missing or the address is not Z.AI, the script stops with exit code 5; it does not run the check with a Claude model. The models Z.AI allows for the Coding Plan are `glm-5.3` and `glm-5.3-flash`; the script uses `glm-5.3`.

The `1113 Insufficient balance or no resource package` error means the Coding Plan quota cannot be used even though the key is valid. On this machine, renewing the key fixed it. Z.AI says personal plan keys should be generated under Personal Coding Plan > Plan Overview.

The Z.AI key in opencode is not used for this check; update it separately with `opencode auth login`.

## What changed in V2

- `opencode` commands connect by default to a background service on `127.0.0.1`, and the service starts on its own when needed. `opencode service status` shows its address, and `opencode service stop` stops it.
- `--pure` was removed.
- Permissions are an ordered `permissions` list, and the last matching rule wins. `external_directory` is enabled by default.

## Rollback

Backup: `~/.opencode-v1-backup/` (binary, `opencode.jsonc`, data folder; contains `auth.json`, do not share).

```sh
opencode service stop
cp ~/.opencode-v1-backup/opencode-1.19.5 ~/.opencode/bin/opencode
rm -rf ~/.local/share/opencode
cp -a ~/.opencode-v1-backup/share-opencode ~/.local/share/opencode
```

The rollback steps have not been tested.
