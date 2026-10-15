# Contributing to PluginsKers/Folia

This guide applies to this fork. Pull requests target `kers/26.3.x`. Upstream contributions follow the conventions of the target Folia revision and contain only the relevant server change.

Use English Conventional Commit titles for commits and pull requests. Keep each change independently reviewable and reversible. Reuse Folia's existing patch structure, fully qualified helper calls, and `Folia - region threading` markers. Small ownership fixes belong in the existing Region Threading Base patch.

A PR description starts with the concrete trigger, failure and resulting behavior. Explain the responsible subsystem, affected callers and existing cleanup or retry path. Include the complete relevant call chain, evidence tied to exact source revisions, and numbered reproduction steps with expected behavior. Distinguish captured production stacks from controlled reproductions and state unverified conditions. Link large fixtures and logs through immutable commits.

Use concise paragraphs, numbered reproduction steps and collapsible stacks. Do not add checkboxes, large headings, generic build/lint/test summaries or unrelated changes. State meaningful AI assistance accurately. Do not claim a person performed a review or verification they did not perform.

Pull requests require a successful `Patch and Build` run and maintainer review before integration. Merge only with the maintainer's explicit authorization. Release instructions are in the [README](README.md#builds-and-releases).
