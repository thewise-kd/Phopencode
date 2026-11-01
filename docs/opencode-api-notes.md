# opencode API notes

Date: 2026-10-31. The plan is based on these notes.

## Sources and confidence level

| Version | How it was verified | Confidence |
|---|---|---|
| 1.19.5 (on this machine) | `opencode serve` was run, `/doc` was fetched, live requests were sent. No LLM calls were made | High; except the LLM stream |
| 2.2.5 (2026-10-30, latest) | `packages/protocol/openapi.json`, `packages/schema/src/*`, `migrate-v1.mdx` at tag `v2.2.5` were read. The binary was **not run** | Medium; live verification `P1.7` |

The live trial used an empty folder, a temporary data directory with no credentials, and `noReply`. The server was shut down; process and port closure were verified.

## Most important finding: V1 and V2 are separate APIs

- Official guide: "Integrations that call the V1 server API must migrate to the V2 API."
- The V1 paths `/session`, `/event`, `/global/event`, `/file`, `/vcs`, `/question`, `/permission` are not in the 2.2.5 spec. The V1 package (`packages/opencode`) has also been removed from the server code.
- The `/api/*` in 1.19.5 is not the same as in 2.2.5 either. Example: the prompt body changed, `/api/session/{id}/question/*` was removed, a question is now a `form`.
- V1 and V2 are not installed side by side by default; the curl installer replaces the V1 binary.
- V2 is changing fast (5 releases on October 29-30). The app must ignore JSON fields it does not know and skip unknown events.

**Plan assumption:** the target is V2 `/api/*`, minimum version 2.2. This is a user decision (`PHASES.md`, D1). The tables below cover both versions; the V1 column is used if the decision changes.

## Connection and identity

| Topic | V2 (2.2.5) | V1 (1.19.5, verified live) |
|---|---|---|
| Password | `OPENCODE_PASSWORD` (`OPENCODE_SERVER_PASSWORD` also works) | `OPENCODE_SERVER_PASSWORD` |
| Username | Fixed `opencode` | `opencode` (changed with `OPENCODE_SERVER_USERNAME`) |
| If there is no password | Generated randomly, `server password ...` is written to stdout | Authentication disabled, a warning is written |
| Authentication | HTTP Basic; a pairing token also works in place of the password | HTTP Basic; all paths including `/doc` return 401 |
| Health/version | `GET /api/info` → `{version, pid, urls, capabilities}` | `GET /global/health` → `{healthy, version}` |
| Spec | `GET /openapi.json` | `GET /doc` |
| Ready line | `server listening on <url>` | `opencode server listening on <url>` |
| Folder scope | `location[directory]=<path>` (verified on 1.19.5 `/api/*`) or `location` in the body | `?directory=<path>` or the `x-opencode-directory` header |
| Background service | `opencode service start/status/get/set` | None |

Notes:

- In V1, `/session` returns empty if `directory` is not given (verified). A single server serves multiple folders.
- On this machine the binary is under `~/.opencode/bin/opencode`. It may not be on `PATH` in non-interactive SSH; the lookup must account for this.
- The password must not be passed on the command line: it must be read from stdin and put into an environment variable.

## Session

| Action | V2 | V1 |
|---|---|---|
| Create | `POST /api/session` `{parentID?, title?, agent?, model?, location?, metadata?, permissions?}` → `{data: Session.Info}` | `POST /session` `{parentID?, title?, agent?, model?, ...}` → `Session` |
| List | `GET /api/session?limit&order&search&parentID&archived&directory&project&subpath&cursor` → `{data, cursor}` | `GET /session?roots&start&search&limit` |
| Get | `GET /api/session/{id}` | `GET /session/{id}` |
| Delete | `DELETE /api/session/{id}` → 204; child sessions are deleted too | `DELETE /session/{id}` → `true`; a second delete returns 404 (verified) |
| Update | `PATCH /api/session/{id}` `{title?, metadata?, permissions?, archived?}` → 204 | `PATCH /session/{id}` `{title?, metadata?, permission?, time?}` |
| Stop | `POST /api/session/{id}/interrupt` | `POST /session/{id}/abort` |
| Fork | `POST /api/session/{id}/fork` `{before?}` | `POST /session/{id}/fork` `{messageID?}` |
| Compact | `POST /api/session/{id}/compact` | `POST /session/{id}/summarize` |
| Turn diff | `GET /api/session/{id}/diff?from&to&context` | `GET /session/{id}/diff?messageID` |
| Revert | `POST /api/session/{id}/revert/stage`, `/revert/commit`, `DELETE .../revert` | `POST /session/{id}/revert`, `/unrevert` |
| Status | `session.status` event, `GET /api/session/active` | `GET /session/status`, `session.status` event |

## Sending and reading messages

| Action | V2 | V1 |
|---|---|---|
| Send | `POST /api/session/{id}/prompt` `{text, files?, agents?, skills?, delivery?, resume?}` → returns immediately | `POST /session/{id}/prompt_async` `{parts[], agent?, model?, noReply?, ...}` → 204 |
| Delivery | `delivery`: `steer` (interject while running) or `queue` | None |
| Read messages | `GET /api/session/{id}/message?limit&order&cursor&type` → `{data, cursor}` | `GET /session/{id}/message?limit&before` → `[{info, parts}]` |
| Command | `POST /api/session/{id}/command` `{name, text, ...}` → 204 | `POST /session/{id}/command` `{command, arguments, ...}` |
| Shell | `POST /api/session/{id}/shell` `{command}` → 204 | `POST /session/{id}/shell` `{agent, command}` |
| Change agent/model | `POST /api/session/{id}/agent`, `/model` | `agent`, `model` on each message |

V2 message types: `user`, `assistant`, `synthetic`, `system`, `skill`, `shell`, `compaction`, `idle`, `agent-switched`, `model-switched`, `location-switched`. Assistant content is `content[]`: `text`, `reasoning`, `tool`.

V1 part types: `text`, `reasoning`, `tool`, `file`, `subtask`, `agent`, `step-start`, `step-finish`, `snapshot`, `patch`, `retry`, `compaction`.

## SSE event stream

| Topic | V2 | V1 (verified) |
|---|---|---|
| Stream | `GET /api/event?types=...` | `GET /event?directory=...` (bound to a folder), `GET /global/event` (all) |
| Format | `{id, created, type, data, location?, metadata?}` | `{id, type, properties}`; global: `{directory, project, payload}` |
| First event | `server.connected` | `server.connected` |
| Heartbeat | SSE comment every 15 s (`: heartbeat`) | `server.heartbeat` event every 10 s; not in the spec |
| Reconnection | No replay. The stream drops on a slow client (capacity 4096) | No `id:` line, no replay |

App rules:

- On every reconnection, the `message` list and pending forms/permissions are fetched again.
- Unknown events are skipped. V1 sent 90 `plugin.added` on startup (verified).
- V1 `/global/event` also forwards each event in a `sync` wrapper; the two must be separated.
- The V2 `types` filter is used to save data. The notification service requests only the types it needs.

Main V2 events (`packages/schema/src/session-event.ts`):

| Group | Events |
|---|---|
| Lifecycle | `session.created`, `session.deleted`, `session.renamed`, `session.archived`, `session.forked` |
| Execution | `session.status`, `session.execution.started/succeeded/failed/interrupted`, `session.retry.scheduled` |
| Step | `session.step.started/streamed/ended/failed` |
| Text | `session.text.started/delta/ended` |
| Thinking | `session.reasoning.started/delta/ended` |
| Tool | `session.tool.input.started/delta/ended`, `session.tool.called/progress/success/failed` |
| Usage | `session.usage.updated`, `session.usage.recorded` |
| Interaction | `form.created/replied/cancelled`, `permission.asked/replied` |
| Other | `session.compaction.*`, `session.revert.*`, `session.shell.started/ended` |

`*.delta` events are live only; the definitive values are `*.ended` and the message list.

Main V1 events: `message.updated`, `message.part.updated`, `message.part.delta` (`{field, delta}`), `message.removed`, `session.status`, `session.idle`, `session.error`, `question.asked`, `permission.asked`, `todo.updated`, `file.edited`, `vcs.branch.updated`.

## Thinking (reasoning)

| | V2 | V1 |
|---|---|---|
| Persistent | In assistant `content[]`: `{type:"reasoning", text, time{created, completed}}` | `ReasoningPart {text, time{start, end}}` |
| Live | `session.reasoning.delta` `{assistantMessageID, ordinal, delta}` | `message.part.delta` |

Visibility depends on the model provider: `text` may come back empty. The live LLM stream was not verified (`P1.7`).

## Tokens and cost

`TokenUsage = {input, output, reasoning, cache: {read, write}}`

| Level | V2 | V1 |
|---|---|---|
| Session total | `Session.Info.cost`, `.tokens` | `Session.cost`, `.tokens` |
| Message | `Assistant.cost`, `.tokens` | `AssistantMessage.cost`, `.tokens` |
| Step | `session.step.ended` `{cost, tokens, finish}` | `StepFinishPart` |
| Live total | `session.usage.updated` | `session.updated` |
| Context ratio | `GET /api/model` → `limit.context/input/output` | `GET /provider` |

## Subagent

| | V2 | V1 (`tool/task.ts`) |
|---|---|---|
| Tool name | `subagent` | `task` |
| Link | Child session carries `parentID` | Child session carries `parentID`; title `<description> (@<agent> subagent)` |
| Tool metadata | `session.tool.progress` → `{sessionID, status}` | `state.metadata` → `{parentSessionId, sessionId, model, background?}` |
| Listing | `GET /api/session?parentID=<id>` | `GET /session/{id}/children` |
| Detail | The child session's own messages and events | Same |

Child session events arrive on the same stream with their own `sessionID`; forms and permissions may also belong to the child session.

## Answering questions and permissions

**In V2 a question is a form.** The `question` tool opens a form carrying `metadata.kind = "question"`.

| Action | V2 | V1 |
|---|---|---|
| Pending | `GET /api/form`, `GET /api/session/{id}/form` | `GET /question` |
| Event | `form.created` `{form}` | `question.asked` |
| Reply | `POST /api/session/{s}/form/{f}/reply` `{answer: {q0: "label", q1: ["a","b"]}}` → 204 | `POST /question/{id}/reply` `{answers: [["label"]]}` |
| Reject | `DELETE /api/session/{s}/form/{f}` | `POST /question/{id}/reject` |

V2 question fields: each question is a field keyed `q<index>`. If `multiple`, it is `multiselect`, otherwise a `string` with `options`; `custom: true` allows a free-form answer. General form field types: `string`, `number`, `integer`, `boolean`, `multiselect`, `external`; `when` provides conditional visibility.

| Action | V2 | V1 |
|---|---|---|
| Permission list | `GET /api/permission/request`, `GET /api/session/{id}/permission` | `GET /permission` |
| Event | `permission.asked` `{id, sessionID, action, resources, save?, metadata?, source?, message?}` | `permission.asked` `{id, sessionID, permission, patterns, always, tool?}` |
| Reply | `POST /api/session/{s}/permission/{r}/reply` `{decision: once\|always\|reject, message?}` | `POST /permission/{id}/reply` `{reply, message?}` |

## Skills, commands, agents and other lists

| List | V2 | V1 |
|---|---|---|
| Skill | `GET /api/skill` → `{id, name, description?, autoinvoke?, path, content}` | `GET /skill` → `{name, description?, location, content}` |
| Command | `GET /api/command` → `{name, description?}` | `GET /command` |
| Agent | `GET /api/agent` | `GET /agent` |
| Model | `GET /api/model`, `GET /api/model/default` | `GET /config/providers` |
| Provider | `GET /api/provider` | `GET /provider` |
| MCP | `GET /api/mcp` | `GET /mcp` |
| Plugin | `GET /api/plugin` | None |

`/skill` carries the full content: 14 skills, 178 KB (verified). The list should be fetched once and cached.

## Reading files

| Action | V2 | V1 |
|---|---|---|
| List | `GET /api/fs/list?location&path` (also works outside the project) → `{path, type}` | `GET /file?path` (inside the project only) |
| Read | `GET /api/fs/read/*` (inside the project; ETag, Range) → raw bytes | `GET /file/content?path` → `{type, content, ...}` |
| Find file | `GET /api/fs/find?query&type&limit` | `GET /find/file?query&dirs&type&limit` |
| Search text | None | `GET /find?pattern` |
| Write | `POST /api/experimental/fs/write` (experimental) | None |

Behaviors verified in V1:

- Files covered by `.git` and `.gitignore` are visible, with an `ignored: true` flag. `.env` contents are readable.
- A path outside the project returns 500, not 403.
- `content` trims the trailing newline; this output cannot serve as a basis for editing.
- No limit was applied on a 3 MB file. Binary files come as base64.
- `/file/status` returned `[]` while there were changes; `/vcs/status` worked correctly.

Decision: the file browser uses **SFTP** for reading and writing. The API is used only for search. This gives a single path, size information and byte accuracy.

## Git status

| Action | V2 | V1 (verified) |
|---|---|---|
| Branch | `GET /api/vcs` | `GET /vcs` → `{branch, default_branch}` |
| Status | `GET /api/vcs/status` → `{file, additions, deletions, status}` | `GET /vcs/status`; an untracked file is `added` |
| Diff | `GET /api/vcs/diff?mode=working\|branch\|committed&base&context&untracked` | `GET /vcs/diff?mode=git\|branch&context`, `GET /vcs/diff/raw` |
| Branches | `GET /api/vcs/branch?search&limit` | None |
| Init | `POST /api/vcs/init` | `POST /project/git/init` |

Diff returns all files in a single response; a 3 MB file inflates the response. The API has no staged/unstaged distinction.

## Work the API does not cover

| Action | Route |
|---|---|
| File writing, upload, download, deletion, renaming, folder creation, `stat` | SFTP |
| Folder selection (browsing the server) | SFTP |
| Search in file contents (V2 has no text search) | SSH exec (`rg`, otherwise `grep`) |
| `git log`, `show`, `blame` | SSH exec |
| `add`, `restore`, `commit`, `branch`, `checkout`, `merge`, `stash`, `fetch`, `pull`, `push` | SSH exec |
| Staged/unstaged distinction | SSH exec (`git status --porcelain=v2 -z`) |
| Starting and stopping `opencode serve`, finding the port and version | SSH exec |
| Notification service installation | SFTP + SSH exec |

`session shell` is not used for git work: the output mixes into the session history and the model context.

## Unverified items

- 2.2.5 live behavior: now installed on this machine. `/api/info` and `opencode run --standalone --format json` were verified; the remaining streams are recorded in `P1.7`.
- In 2.2.5, `opencode` commands connect to the background service on `127.0.0.1` (`opencode service status`). `opencode pair` generates a one-time link for a browser or app. The app must look for this service before starting a new process; `P3.7-P3.9` must be verified accordingly.
- Live LLM stream: the actual order of reasoning, tool and subagent events. `P1.7` closes this with recorded samples.
- `OPENCODE_SIMULATE` mode exists in the 2.2.5 code; whether it is suitable for recording without an LLM has not been tried.
- No stability commitment across V2 versions was found.
