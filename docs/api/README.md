# Recorded opencode API samples

Fixtures for the app's decoders, reducers and notification rules. They live in `core/testing/src/main/resources/fixtures/`. What the recordings show about payload shapes is under "Shapes" below.

## What this is

| Item | Value |
|---|---|
| Server | opencode 2.2.5, `opencode serve` |
| Model | **None.** Every model answer was scripted through `OPENCODE_SIMULATE` |
| Network | Empty network namespace; loopback only |
| Provider credentials | None; the server's environment is built from an allow-list |

Simulation replaces only the model. The session loop, tools, forms, permissions, snapshots, storage and event stream are the real server code. So the server's own behavior is real: event names, payload shapes, the order the server emits things for a given model stream, and the message list.

**Simulation-only, may differ from a real model:**

- The order and timing of model output. The script decided that reasoning comes before text and how the chunks were split. A fixture shows how the server handles that stream, not what a provider sends.
- Token counts and costs: the script supplied the usage numbers.
- Text content: `Simulated ...` strings, not real prose.
- Provider errors: the invalid-request and dropped-stream cases were forced by hand.
- Timing values: the server merged deltas sent 60 ms apart, so `text` and `reasoning` pace the chunks 400 ms apart to show separate deltas. A real stream can merge differently.
- Everything goes through the OpenAI-compatible chat route. Provider-specific reasoning fields (`native`, encrypted reasoning) were not exercised beyond `reasoningField`.

## Layout

```
fixtures/
  manifest.json            version, source, and per-flow status and event types
  spec/openapi.json        byte-identical to the server's spec
  <flow>/events.jsonl      one raw `data:` payload per line, in arrival order
  <flow>/exchanges.jsonl   one line per HTTP call: label, method, path, request,
                           status, after_event, response file
  <flow>/http/NN-label.*   raw response body, unformatted
  startup|text/sse-wire.txt  the stream as sent, with `: heartbeat` comments
```

`after_event` is the number of the flow's events seen when the response arrived, so a test can place a response inside the stream. Events are sliced from one stream per flow, so a flow can hold a late event of the previous one, such as `worktree.resolved`.

## Flows

All 20 flows recorded.

| Flow | Shows |
|---|---|
| `startup` | First events, heartbeat, `/api/info`, a 404 |
| `git-setup` | `POST /api/vcs/init`, the shell tool committing |
| `text` | Text deltas, usage, message pagination |
| `reasoning` | Reasoning deltas, cached and reasoning tokens, an empty reasoning delta |
| `tool` | `write`, `read` ok, `read` failing, `shell`, streamed tool input |
| `subagent` | Child session, its own stream, `/api/session/active`, `parentID` list |
| `subagent-permission` | A child session's permission |
| `form-question` | Question form: lists, answer, invalid answer, settled form, dismissal |
| `form-fields` | Every field type, `when`, limits, `external` |
| `permission` | `once`, `reject`, `always`, saved rules |
| `lifecycle` | Rename, metadata, permissions, archive, unarchive, fork, delete |
| `interrupt` | Active status, interrupt mid-stream |
| `retry` | Dropped stream, scheduled retry, recovery |
| `error` | Provider error, unavailable model |
| `delivery` | `steer`, `queue`, inbox list, cancel and re-delivery |
| `auto-title` | A session with no title gets one from a model call |
| `diff-revert` | Turn diff with `from`, `to`, `context`; revert stage, clear, commit |
| `compaction` | Failed and successful compaction |
| `commands` | Agent and model switch, command, shell, context |
| `catalog` | Agents, models, providers, plugins, MCP, skills, commands, VCS, files, config |

71 of the 96 event types in the spec were recorded. Not recorded: `session.status`, `session.idle`, `session.tool.input.delta`, `session.skill.activated`, `session.moved`, `filesystem.changed`, and the `pty.*`, `persistent-pty.*`, `tui.*`, `credential.*`, `installation.*`, `artifact.updated`, `config.updated`, `models-dev.refreshed`, `mcp.resources.changed`, `shell.deleted` types. "Shapes" covers the first three. `manifest.json` has the exact list per flow.

## Masking

Masking works on the raw text, so key order and number spelling stay as sent.

| Value | Becomes |
|---|---|
| Project folder | `/workspace/project` |
| Temporary home | `/home/fixture` |
| Other temporary paths | `/tmp/capture/...` |
| IDs (`ses_`, `msg_`, ...) | Same prefix and length, numbered by first appearance across all flows |
| 32 to 64 digit hex hashes | Same length, numbered by first appearance |
| Pagination cursors | Decoded, masked, encoded again |
| Timestamps of the run | Shifted so the run starts at `1700000000000`; differences stay |
| Hostname, username | `fixture-host`, `fixture` |
| Port, PID | `4096`, `4242` |
| Session slugs | `slug-NNNN` |
| Every `apiKey` value | `placeholder`, including the server's built-in `public` value |

The same ID gets the same mask everywhere, so `parentID`, `sessionID` and cursor contents still connect. Masked IDs do not preserve the server's sort order: use the order of the list response and never sort by ID.

Short git object IDs inside diff text are left alone: they hash the fixture files, not anything of this machine.

The spec gets only secret and path masking, because its words are vocabulary, not identity. Recording refuses to run if the username or hostname is an ordinary word, such as `root`, or appears as a word in the spec: masking it would rewrite normal text.

## Re-record

```sh
python3 scripts/capture_fixtures.py             # needs unshare, ip and opencode
python3 scripts/capture_fixtures.py --check     # leak and JSON check
python3 scripts/capture_fixtures.py --self-test # masker, checker and fixture tests
python3 scripts/check_boundaries.py
```

Options: `--flows a,b` records only those flows and leaves `manifest.json` alone, `--binary` picks the opencode binary, and `--work-dir` picks the parent of the temporary folder. The run takes about a minute. A recording exits non-zero if a flow failed, an expected event is missing, or the leak check finds anything.

The temporary run folder holds the server log and database, so it is removed on every exit, including a failed start and SIGINT, SIGTERM or SIGHUP. Use a whole re-record for committed fixtures: mask numbers depend on the flows in the run, so a `--flows` run does not match the rest of the tree.

The script runs in an empty network namespace and refuses to record if a route exists. It never falls back to a real model: with the empty namespace and the simulator, no provider can be reached. Without simulation a prompt fails, so a broken simulator shows up as a failed flow.

The simulator is an internal opencode feature, not a public API: `OPENCODE_DRIVE`, the instance manifest and the control socket can change in any release. The client-side protocol is in the `Simulator` class of the script.

## Shapes

Payload shapes come from the real server. Anything about model output order or usage values comes from simulation, as listed above. Items marked **Spec only** were read from `openapi.json` and were not seen on the wire.

`core/api` and `core/model` guessed some of these shapes; the differences are listed in "Changes for the API modules".

### Events

Envelope: `{id, created?, type, data, location?, metadata?, durable?}`. `durable` is `{aggregateID, seq, version}` and appears on events that persist. `server.connected` has no `created` and is not in the spec's `types` enum.

Wire format: `data: <json>` lines and `: heartbeat` comments. There are no `event:` or `id:` lines, although the spec describes an `event` field. A heartbeat arrives right after connecting.

| Event | `data` beyond `sessionID` |
|---|---|
| `session.created` | Flat: `slug, version, projectID, location, subpath, title, parentID?, agent?, metadata?`. There is no nested session |
| `session.renamed` | `title` |
| `session.archived`, `session.unarchived` | Nothing |
| `session.forked` | `parentID, boundary{type, messageID}, instructions, instructionEntries?`; `type` is `through` or `before` |
| `session.execution.failed` | `error{type, message, status?, response?}` |
| `session.execution.interrupted` | `reason`: `user` from interrupt, `shutdown` after dismissing a question |
| `session.retry.scheduled` | `assistantMessageID, attempt, at, error{...}`; the first value of `attempt` is 2 and `at` is in milliseconds |
| `session.step.started` | `assistantMessageID, agent, model{id, providerID}, snapshot, started` |
| `session.step.ended` | `assistantMessageID, finish, rawFinish, tokens, cost, snapshot, files` |
| `session.step.failed` | `assistantMessageID, error{...}, rawFinish?, tokens?, cost?, snapshot, files` |
| `session.text.*`, `session.reasoning.*` | `assistantMessageID, ordinal`, then `delta` or `text`. Reasoning `started` and `ended` add `native?` |
| `session.tool.input.started` | `assistantMessageID, id, name` |
| `session.tool.input.ended` | `assistantMessageID, id, text` (raw JSON string) |
| `session.tool.called` | `assistantMessageID, id, input, executed`. No `name`: take it from `input.started` |
| `session.tool.progress` | `assistantMessageID, id, metadata` |
| `session.tool.success` | `assistantMessageID, id, content[], metadata, executed` |
| `session.tool.failed` | `assistantMessageID, id, error{type, message}, executed` |
| `session.usage.updated` | `cost, tokens`: running totals for the session, not a step's values |
| `session.compaction.*` | `started{reason, inputID}`, `delta{text}`, `ended{reason, model, text, cost, tokens}`, `failed{reason, error, inputID, cost, tokens}` |
| `session.revert.*` | `staged{revert}`, `cleared{}`, `committed{to}` |
| `session.shell.*` | `started{shell}`, `ended{shell, output}` |
| `session.inbox.*` | `enqueued{inboxID, item}`, `delivered{inboxID}`, `cancelled`, `delivery.changed` |
| `session.agent.selected`, `session.model.selected` | `agent, previous?`, `model` |
| `form.created` | `form`: the `sessionID` is inside it |
| `form.replied`, `form.cancelled` | `id, sessionID`, plus `answer` on replied |
| `permission.asked` | `id, sessionID, action, resources, save, source{type, messageID, id}` |
| `permission.replied` | `requestID, sessionID, reply`: `once`, `always` or `reject` |

**`session archived` does not cover un-archiving.** They are two events, `session.archived` and `session.unarchived`. `PATCH {archived: false}` sends the second and removes `time.archived`.

**`session.status` and `session.idle` were never emitted** in any flow, although the spec lists them. Busy and idle come from `session.execution.started`, the three `session.execution.*` endings, and `/api/session/active`.

**`session.tool.input.delta` was never emitted**, even with input chunks 400 ms apart: only `input.started` and `input.ended` arrived. This may be specific to the simulated chat route, so keep the decoder for it.

`session.usage.recorded` from the notes does not exist in 2.2.5.

`text.ended` and `reasoning.ended` carry the whole text. `ordinal` counts per block kind, so a message's first reasoning and first text are both 0.

`session.text.started` can arrive before `session.reasoning.ended`: blocks overlap. In the `tool` flow a failing tool's `session.tool.failed` arrived before a sibling's `session.tool.success`. Both depend on the server's handling; the model chunks themselves were scripted.

An assistant message is one step. A turn with three tool rounds gives four assistant messages, each with its own `assistantMessageID`.

### Sessions and messages

`GET /api/session/active` returns `{data: {<sessionID>: {type: "running"}}}`, or `{data: {}}`. Idle sessions are absent, a subagent's child session is included, `running` is the only status, and there is no `location` wrapper.

Session fields beyond the notes: `outcome` (`succeeded`, `failed`, `interrupted`), `time{created, updated, busy?, idle?, archived?}`, `revert?`, `fork?{sessionID, boundary}`, `agent?`, `model?{id, providerID, variant}`.

**The default session list includes archived sessions.** They carry `time.archived`. Pass `archived=false` to hide them; that value itself was not recorded.

`GET .../message` is newest first unless `order=asc`. Its `cursor` is `{previous, next}`, not a string. A user message has `text` at the top level, while the inbox item returned by `prompt` has `payload.text`.

Message types seen: `user`, `assistant`, `idle` (`outcome`, `error?`), `synthetic`, `compaction`, `shell`, `agent-switched`, `model-switched`.

`POST .../prompt` returns the inbox item, not an empty body. `delivery` defaults to `steer`. Interrupt returns `200 {"interrupted": true}`.

The first prompt after startup can block for about 10 seconds while the location starts. In that window other routes return `503` with `_tag: "ServiceUnavailableError"` or `code: "service_starting"`. The empty network namespace may lengthen this.

Switching to a model that does not exist is accepted with 204. The failure comes at the next prompt: `session.execution.failed` with `provider.no-route`.

Errors are `{_tag, message, ...}`. Seen: `SessionNotFoundError` 404, `FormInvalidAnswerError` 400, `FormAlreadySettledError` 409, `PermissionNotFoundError` 404. Replying twice to a permission gives 404, not 409.

Turn diff: `GET .../diff?from&to&context`. `from` and `to` are **user message IDs**. With no `from`, it is the newest user turn. `to` is a later user message whose turn ends the range. Items are `{file, patch, additions, deletions, status}`.

Revert: `POST .../revert/stage` body is `{messageID, files?}`, where `messageID` is a user message and `files` is a boolean. Response: `{data: {messageID, snapshot, files: [FileDiff]}}`. The session then carries the same object as `revert`. `DELETE .../revert` and `POST .../revert/commit` return 204; commit removes the message and everything after it. Events arrive after the 204. Clearing a revert also runs a short execution with no model request, so wait for its end before reading the session.

After `POST .../shell`, the result is enqueued as a `synthetic` inbox item after `session.shell.ended`. It starts no execution.

Compaction: the summary must contain the headings from the compaction prompt. A summary without them is retried once, then fails with `compaction.failed`. Cost and tokens are still reported on failure.

### Tokens and pricing

From `reasoning`: the script reported 200 prompt tokens (50 cached) and 40 completion tokens (25 reasoning). The server stored `input: 150, output: 15, reasoning: 25, cache.read: 50` and cost `0.0004725`.

- `input` excludes cached tokens, and `output` excludes reasoning tokens.
- Cost = (150 × 1.5 + 50 × 0.15 + 40 × 6) / 1,000,000, so reasoning tokens are priced as output.
- A model's `cost` is a **list** of tiers, `[{tier?{type, size}, input, output, cache?{read, write}}]`, in **USD per million tokens** (`Money.USDPerMillionTokens`). There is no separate reasoning price.
- A step with no usage chunk stores zeros.

The list prices are the fixture's configuration; the formula is the server's.

### Forms

`Form.Info`: `{id, sessionID, title, metadata?, fields}`. The detail route adds `state`: `{status: pending}`, `{status: answered, answer}` or `{status: cancelled, message?}`.

A question form has `title: "Questions"` and `metadata{kind: "question", tool{messageID, id}}`. Each field has `key: q<n>`, `title` (the short header), `description` (the question) and `custom: true`.

Common field keys: `key, type, title?, description?, required?, hidden?, when?`.

| Type | Extra keys |
|---|---|
| `string` | `format?` (`email`, `uri`, `date`, `date-time`), `minLength?, maxLength?, pattern?, placeholder?, default?, options?, custom?` |
| `number`, `integer` | `minimum?, maximum?, default?` |
| `boolean` | `default?` |
| `multiselect` | `options, minItems?, maxItems?, custom?, default?` |
| `external` | `url`, and only `key, title?, description?` beside it: no `when`, `required` or `hidden` |

- Option: `{value, label, description?}`. Answers carry `value`.
- `when` is a **list** of `{key, op, value}`, with `op` being `eq` or `neq` and `value` a string, number or boolean.
- `external` answer: `{<key>: true}`. An empty answer gives 400 "External form field must be acknowledged". The app has to open `url` itself.
- A form can be created directly with `POST /api/session/{id}/form`, with no model involved.

**Dismissing a question ends the whole execution.** After `DELETE` on a question form: `form.cancelled`, `session.tool.failed` (`aborted`), `session.step.failed`, `session.execution.interrupted` with `reason: shutdown`. No further model request is sent. The session accepts a new prompt afterwards.

### Permissions

`GET /api/permission/request` has the `location` wrapper; `GET /api/session/{id}/permission` does not. Permissions asked by a child session carry the child's `sessionID`.

`save` was `["*"]` for reading `.env`. **Replying `always` saved `{action: "read", resource: "*", projectID: "global"}`**, so later reads stopped asking in the recording. The app should warn before sending `always`, and can list and delete rules with `/api/permission/saved`.

### Catalog

| Item | Shape |
|---|---|
| Provider | `{id, canonical?, integrationID?, name, activation, package?, compatibility?, settings?, headers?, body?}`. `activation`: `auto`, `enabled`, `disabled` |
| Model | `{id, modelID, providerID, type?, family?, name, compatibility?, package, settings?, capabilities, variants[{id, settings}], time{released}, cost[], status, enabled, limit{context, input?, output}}` |
| Plugin | `{id, source{type}, features{server}, state{status}}`. No `name` or `version` |
| MCP server | `{name, status{status, error?}}`. Status is a nested object |
| Agent | `{id, name, request, description?, mode, hidden, permissions[{action, resource, effect}], model?, system?, color?, steps?}` |
| Skill | `{id, name, description, path, content}` |

- MCP status values seen: `failed`, `disabled`. **Spec only:** `connected`, `pending`, `needs_auth` (with `error`).
- Agent modes seen: `primary`, `subagent`. **Spec only:** `all`. The list includes hidden agents (`compaction`, `title`, `summary`): filter on `hidden`.
- The agent's `id` is what the API takes, while `name` is the display form (`build`, `Build`).

**`/api/provider`, `/api/model` and `/api/config` return `settings.apiKey` in clear.** The test config's key came back unchanged, and the built-in provider has the value `public`. A key a user puts in their config can be expected to come back the same way. The app must drop `settings` and `headers` when decoding and never log these bodies. The fixtures show `placeholder` for every value.

The `opencode` provider's free models are built in and are the default when nothing is configured. A server with no provider set up can therefore still run real model calls.

### Tool status values

Message list `state.status`: `completed` and `error` were seen. **Spec only:** `streaming`, `running`.

- `completed`: `{status, input, content[], metadata}`.
- `error`: `{status, input, error{type, message}}`.
- Time: `{created, ran?, completed?}`.

The subagent tool's `metadata` is `{sessionID, status}`, with `status` `running` in progress and `completed` at the end. Its `content` text wraps the child's answer in `<subagent sessionID=... state=...>`.

### VCS and files

| Route | `data` |
|---|---|
| `GET /api/vcs` | `{provider, branch: {current}}`. With no repository: `{branch: {}}` |
| `GET /api/vcs/branch` | **A list of strings**, `["main"]`, with no `current` flag |
| `GET /api/vcs/base` | `{name, ref, source}` or null. `source`: `default` or `reflog` |
| `GET /api/vcs/status` | `{file, additions, deletions, status}`; `status` is `added`, `deleted` or `modified`, and an untracked file is `added` |
| `GET /api/fs/list` | `{path, type}`; directories end in `/`; ignored and `.env` files are listed |
| `GET /api/fs/find` | `{path, type}`, same as list; `path` is relative and a directory ends in `/` |
| `GET /api/fs/read/*` | Raw bytes, not JSON |

Most location-bound routes wrap the result as `{location, data}`. `/api/project` returns a bare list.

### Changes for the API modules

Against the P1.1 and P1.2 commits:

- `ServerEvent.SessionCreated`: no nested `Session`; build from the flat fields.
- Add `SessionUnarchived`.
- `SessionForked`: `parentID` rather than `forkedFrom`, plus `boundary`.
- `ExecutionFailed`, `StepFailed`, `RetryScheduled`: `error` is an object, not a string.
- `ToolInputStarted` and the other tool events: the key is `id`, not `callId`.
- `SessionStatusChanged` has no recorded source.
- `Plugin`: use `id` and `state.status`; `name` and `version` do not exist.
- `McpStatus`: add `Pending`; decode from the nested `status.status`.
- `VcsBranch`: a plain name; `current` cannot come from this route. `VcsBranchInfo.defaultBranch` needs `/api/vcs/base`.
- `activeStatuses`: only `running` exists today.
- `ModelPricing`: `cost` is a tier list and has no `reasoning` price. Reasoning tokens use the output price.
- `Form`: `when` is a list, so `visibleWhen: FieldCondition?` holds too little. `external` has its own limited keys and a `url`. The form has no top-level `description`.
