# phopencode

Public Android app for managing, from a phone, opencode running on a server with `opencode serve`.

Stack: Kotlin + Jetpack Compose, Android only.

Build and test commands are added here once the relevant task is done.

## Tasks

The task list is in `docs/PHASES.md`; IDs have the form `P<phase>.<number>` (e.g. `P2.3`).

## Agents

- `orchestrator`: writes no code; assigns tasks and runs the flow.
- `coder`: writes tests and code, fixes findings, commits.
- `reviewer`: reviews code with GPT, read-only (`.claude/scripts/run-review.sh`).
- `security`: runs the security check with GLM, read-only (`.claude/scripts/run-security.sh`).

`reviewer` and `security` do not write long reports: they briefly report negative findings only, and return a single line `OK` when clean.

## Task flow

1. `coder`: test first, then code.
2. `reviewer`: reviews.
3. If there are findings and a fix is needed, `coder` fixes them, then `reviewer` runs again.
4. `security`: security check at the very end; `coder` fixes any findings.
5. When the task is done, make one commit: per task, not per test. Do not push; the user does the pushing.

## Rules

- Language: everything in the repository is in English: code, commit messages, agent, skill and hook instructions, and documents. The app's user-facing strings ship in Turkish and English, because `docs/PHASES.md` requires TR/EN string parity.
- Secrets: the repository is public. Never commit server addresses, keys, passwords or the ntfy topic.
