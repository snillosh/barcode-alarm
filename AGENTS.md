# Repository working agreement

- After completing a code change, run the relevant checks, commit the completed change,
  and push it to `origin` using SSH. The user has explicitly requested this as the default.
- Keep commits focused on a completed, tested change rather than individual edits.
- Use `main` unless the user requests another branch. Never force-push or overwrite
  unrelated work. If a push is blocked, explain the actual blocker.
- Preserve the app's native UI, existing alarm scheduling and offline operation.
- Follow the readability and testing conventions in `DEVELOPING.md`.
