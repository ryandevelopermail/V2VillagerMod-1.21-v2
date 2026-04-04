# Mason Wallbuilder Regression Contract

This contract defines **non-negotiable wallbuilder invariants**. Any pull request that changes `MasonWallBuilderGoal` behavior (or related wall planning/staging code) must preserve these rules or explicitly document and justify a contract change.

## Hard invariants (must always hold)

1. **Layering order is strict:** Layer 1 must fully complete before any upper layer placement begins.
2. **Maximum wall height is 3:** No placement path may exceed a 3-block wall height.
3. **Ground-target plant replacement is deterministic:** Replaceable plants at ground wall targets must be cleared and replaced by intended wall blocks.
4. **Staging is single-entry per cycle:** Staging logic must not re-enter in loops for the same unresolved edge/target.
5. **Logging is bounded:** Wallbuilder must not emit runaway log spam; repeated events must be rate-limited/summarized.

## Pre-merge checklist (required)

- [ ] Run a wallbuilder validation scenario and collect logs.
- [ ] Confirm logs show layer-1 completion before any layer-2/3 placement.
- [ ] Confirm logs/placement evidence never exceed height 3.
- [ ] Confirm at least one replaceable-plant target was cleared then replaced with wall block.
- [ ] Confirm staging did not loop/re-enter for the same target in a tight cycle.
- [ ] Confirm no runaway/repeating wallbuilder spam pattern appears in logs during a full build cycle.

> **Merge gate:** PR reviewers should block merge unless these checks are backed by log evidence attached in the PR description or test notes.

## Change record (required when wallbuilder logic changes)

When wallbuilder logic changes, append/update an entry in this section using the following headings:

### Change Impact
- What behavior changed?
- Which invariant(s) could be affected?

### Regression Risks
- What could fail if this change regresses?
- Which worlds/terrain patterns are highest risk?

### Validation Evidence
- Exact test world/setup used.
- Relevant log excerpts (or line references) proving each invariant.
- Any known limitations or deferred follow-up checks.

## Known-safe rollback toggles (emergency mitigation)

Use these to stabilize production worlds while investigating a regression:

- **Staging bypass toggle:** set `masonWallRequireStaging=false` to bypass staging-gated placement when staging loops or deadlocks are suspected.
- **Verbose logging toggle:** set `masonWallVerboseLogging=false` to reduce wallbuilder log volume if spam pressure impacts operations.

These toggles are mitigation-only and do **not** replace invariant validation.
