---
description: Run one task from docs/PHASES.md through the team workflow, ending in a single commit.
argument-hint: <task-id, e.g. P2.3>
disable-model-invocation: true
---

Run task `$ARGUMENTS`.

Stay in this main session and call `coder`, `reviewer` and `security` yourself with the Agent tool. Do not spawn `orchestrator` as a subagent.

1. Read `.claude/agents/orchestrator.md` and follow its workflow. It owns the order of the steps, so if this session already runs as `orchestrator`, go on to the next step.
2. Stop and say so if `$ARGUMENTS` is not a single `P<phase>.<number>` ID, or if `docs/PHASES.md` does not list it.
3. Stop and report the commit if `git log --oneline -E --grep='^<id>([^0-9]|$)'` already finds the task.
4. Stop and report the unfinished tasks if `docs/PHASES.md` makes this task depend on tasks that have no commit yet.
5. Stop and ask if `git status --short` shows changes, because they would end up in this task's commit.
6. Give every agent the task ID and the task's full text from `docs/PHASES.md`. The commit subject starts with the task ID, followed by a colon, because step 3 finds finished tasks that way.

Finish with the commit hash and subject, or with why the task stopped. Never push.
