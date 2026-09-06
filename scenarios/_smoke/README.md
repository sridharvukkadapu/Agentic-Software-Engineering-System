# `_smoke`: do not edit `requirement.md` to match a scenario

This directory exists only to keep the CLI smoke test
(`workflows/approval-demo.json`, REQUIREMENT then DOCUMENT) independent of any
real scenario's requirement text.

The smoke test's whole purpose is to prove the build, the scheduler, entry/exit
gates, the audit log, and real artifact writing work from a clean clone, no
key, no network. None of that depends on what the requirement says. Pointing
the smoke test at `scenarios/greenfield/requirement.md` was the original
mistake that made this directory necessary: amending greenfield's text for the
greenfield scenario silently changed the request hash the smoke test's
`fixtures/cli/requirement` fixture was keyed to, breaking the one path that is
supposed to always work.

**Do not edit `requirement.md` in this directory to track any scenario's
content, and do not delete this directory in favor of pointing the smoke test
back at a real scenario file.** If the smoke test's own requirement genuinely
needs to change, change it deliberately here and re-record
`fixtures/cli/requirement` and `fixtures/cli/document` against the new text;
do not let it drift by accident because some other scenario's text changed.

This warning lives in this file, not in `requirement.md` itself, so it is
never sent to the agent as part of the requirement text.
