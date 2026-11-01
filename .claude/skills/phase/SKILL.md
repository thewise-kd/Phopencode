---
description: Run every unfinished task of a phase from docs/PHASES.md, group by group, with independent tasks in parallel.
argument-hint: <phase-number, e.g. 2>
disable-model-invocation: true
---

Run phase `$ARGUMENTS`.

Stay in this main session and call `coder`, `reviewer` and `security` yourself with the Agent tool. Do not spawn `orchestrator` as a subagent.

## Before the first group

1. Read `.claude/agents/orchestrator.md` and follow its workflow for every task. It owns the order of the steps, so if this session already runs as `orchestrator`, go on to the next step.
2. Read the phase in `docs/PHASES.md` and list its parallel groups in order. Stop and say so if the phase is missing or its groups are unclear.
3. Drop every task that `git log --oneline -E --grep='^<id>([^0-9]|$)'` already finds.
4. Stop and ask if `git status --short` shows changes, because they would end up in a task's commit.

Give every agent the task ID and the task's full text from `docs/PHASES.md`. Each commit subject starts with the task ID, followed by a colon, because step 3 finds finished tasks that way.

## Each group

Finish a group completely before starting the next one, because later groups build on its commits.

Run the tasks left in the group concurrently. `coder` always works in its own worktree, because tasks would otherwise share one index and one set of uncommitted changes.

1. Start one `coder` per task in a single message. Record each task's agent ID, worktree path and branch from the result.
2. Run each later step of the workflow for all tasks that have reached it in a single message.
3. Give `reviewer` and `security` the task's worktree path, so that each one checks that task's changes alone.
4. Send fixes and the commit request to the same `coder` with SendMessage and its agent ID. A new Agent call would start in a different worktree.
5. When a task's commit exists on its branch, leave the worktree in place until the whole group has reached this point.

Then bring the commits to the main checkout one at a time, in the order `docs/PHASES.md` lists the tasks:

1. Run `git cherry-pick <branch>`. On a conflict, run `git cherry-pick --abort`, keep that worktree, and tell the same `coder` with SendMessage to rebase onto the main branch, resolve the conflict, rerun the tests and report the new commit. Then repeat this step. A conflict alone does not fail the task.
2. Remove the worktree with `git worktree remove .claude/worktrees/<name>`.
3. Delete its branch with `git branch -D <branch>`.

## When a task fails

Let the other tasks of the group finish and bring their commits in, then stop. Do not start the next group, since it may depend on the failed task.

## Report

Finish with one line per task: the commit hash, or why it stopped. Name any worktree you kept. Never push.
