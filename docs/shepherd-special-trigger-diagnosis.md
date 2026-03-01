# Shepherd special-trigger diagnosis (wheat/banner from chest)

## Symptoms reported
- Putting a **banner** in the shepherd chest does not start pen-banner placement behavior.
- Putting **wheat** in the shepherd chest does not start wheat gather behavior.

## Diagnosis

### 1) Banner trigger can dead-end when no world banners already exist
`findNearestPenTarget` only gathers fence-gate candidates around already-placed banner block entities.
If no banner blocks exist yet, `bannerCandidates` is empty and the method returns `null` immediately.
That makes the `BANNER` task abort even when a banner is present in chest/inventory.

Why this breaks both triggers:
- Banner placement cannot start without a pre-existing banner anchor.
- Wheat gather requires `hasGroundBannerNearby`, so wheat-from-chest also cannot start until a ground banner exists.

This behavior strongly matches a performance-motivated refactor that changed an expensive world scan into a banner-anchored scan, but introduced a bootstrap/circular dependency.

### 2) Chest-change wakeups are likely not firing for real chests
`updateChestListener` only registers listeners when the chest inventory is a `SimpleInventory`.
Normal chest inventories from `ChestBlock.getInventory(...)` are typically not `SimpleInventory`, so no listener is attached.
Without that listener, `onChestInventoryChanged` and immediate wake checks are skipped, making trigger responsiveness depend on periodic checks instead of chest updates.

This does not alone explain a complete failure, but it compounds issue #1 and can make behavior seem non-functional.

## Task plan to resolve (performance-aware)

### Task A — Fix banner placement bootstrap without reintroducing expensive scans
1. Introduce a two-phase pen search in `findNearestPenTarget`:
   - Phase 1 (current fast path): banner-anchored gate search.
   - Phase 2 (fallback): if no banner candidates found, run a bounded gate search around the villager/job site (same Y clamp, hard candidate cap).
2. Keep strict limits (chunk radius, max candidates, early sorting/cutoff) so fallback is predictable.
3. Cache fallback results with the existing TTL cache fields to avoid per-tick rescans.
4. Add lightweight debug logging counters (sampled/rate-limited) to confirm fallback hit rate and cost.

### Task B — Restore reliable chest-change signaling
1. Replace `SimpleInventory`-only listener strategy with a chest-block-entity based trigger path.
   - Option: add a mixin hook for chest inventory mutations (`markDirty`/`setStack`) to notify nearby paired villagers.
   - Option: maintain a low-frequency checksum poll for paired chests as a safe fallback when listener hooks are unavailable.
2. Preserve debounce/coalescing behavior to avoid thrash (`SPECIAL_GOAL_CHECK_DEBOUNCE_TICKS` pattern is good).
3. Ensure both single and double chests are covered.

### Task C — Add regression tests / reproducible checks
1. Repro 1: empty world with pen + gate + chest + shepherd, place banner in chest, verify banner is placed in pen.
2. Repro 2: after banner exists, place wheat in chest, verify shepherd equips wheat and starts gather session.
3. Repro 3: no pre-existing banners, ensure banner behavior still works via fallback gate scan.
4. Repro 4: stress with multiple shepherds/chests, verify scan caps and no major tick-time regression.

### Task D — Guardrails for future performance fixes
1. Add code comments documenting that wheat gather depends on at least one ground banner, and that banner placement must support zero-banner bootstrap.
2. Add a metric/log when `BANNER` task is selected but `penTarget` resolution fails repeatedly.
3. Add a short design note in docs describing scan strategy, candidate caps, and fallback rationale.

## Suggested implementation order
1. Task A (unblocks both user-visible triggers).
2. Task B (restores responsiveness and correctness on chest changes).
3. Task C (locks behavior in).
4. Task D (prevents regressions from future optimization passes).
