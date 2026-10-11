# Prompt library

Prompts that shape our code are source code, so they live here under version control and change
only through a reviewed commit (CS5013 Module 2, §2.23).

## Conventions

- **One prompt per file**, numbered, with a header: `id`, `version`, `used for`, `model and
  settings`, `acceptance check`.
- **Structure**: the notes' five parts (Role, Task, Context, Constraints, Examples) plus the slides'
  output format. XML-style tags once a prompt is longer than a screen, with the task restated at the end.
- **Context is curated, not dumped**: the failing method and what it calls, or a diff, never the whole
  repository.
- **Every prompt names its acceptance check**: the test or command that must pass before anything it
  produced is committed.
- **Changelog at the bottom.** Bump the version whenever a change could alter what comes back, and
  say why in the commit.
- **Never paste real marks or roll numbers into a prompt.** Use `samples/` (synthetic) only; the
  data-sharing note forbids anything else leaving the machine.

| File | Used for |
| --- | --- |
| [`01-plan-a-change.md`](01-plan-a-change.md) | Planning a change across modules before any code |
| [`02-test-first-module.md`](02-test-first-module.md) | Generating a module: tests first, then the smallest implementation |
| [`03-explain-then-fix.md`](03-explain-then-fix.md) | Debugging: explain and rank causes, then the smallest patch |
| [`04-edge-cases.md`](04-edge-cases.md) | Listing edge cases for a parser or rule before testing it |
| [`05-review-a-diff.md`](05-review-a-diff.md) | Reviewing a diff against the project's invariants |
| [`06-commit-message.md`](06-commit-message.md) | Drafting a commit message whose *why* we then write ourselves |
| [`07-document-for-a-reader.md`](07-document-for-a-reader.md) | Javadoc and docs written for a named reader |
