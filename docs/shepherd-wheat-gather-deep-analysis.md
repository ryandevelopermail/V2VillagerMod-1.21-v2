# Deep analysis: Shepherd wheat gather trigger + circular herding behavior

## Scope

This analysis reviews the current branch implementation for shepherd special behavior, with focus on:

1. Triggering when wheat is loaded into the paired chest.
2. Requiring at least one paired pen before consuming wheat.
3. Executing a new gather flow: circular route around pen banner center at ~20 blocks, alignment with gate, move toward gate/banner, then exit + close gate.
4. Identifying leftovers/partial logic from prior attempts.

## Current trigger and behavior flow (as implemented)

### Entry point and chest wake-ups

- `ShepherdBehavior.onChestPaired(...)` creates/updates `ShepherdSpecialGoal`, `ShepherdToLibrarianDistributionGoal`, and optional `ShepherdCraftingGoal`, then registers chest watch positions (single + double chest halves).  
- `ChestBlockEntityMixin` invokes `ShepherdBehavior.onChestInventoryMutated(...)` on chest mutation (`setStack`, `removeStack` overloads).  
- `ShepherdBehavior.triggerChestWakeups(...)` debounces/coalesces special-goal checks and requests immediate special/crafting/distribution checks.

**Assessment**: chest-trigger responsiveness is present and likely functional for single and double chests.

### Task selection order in `ShepherdSpecialGoal`

`findTaskType(...)` priority:
1. `BANNER` if banner in villager inv/hand or chest.
2. `SHEARS` if shears in chest/inventory.
3. `WHEAT_GATHER` if wheat in chest/inventory/offhand AND `hasGroundBannerNearby(...)`.

**Assessment**:
- Wheat trigger is currently coupled to *ground banner existence*, not to *paired pen availability*.
- Banner task preempts wheat and shears preempt wheat.

### Wheat gather start behavior

- `start()` for `WHEAT_GATHER` calls `equipWheatForGathering(world)`.
- `equipWheatForGathering` pulls wheat from villager inventory first; then from chest (1 item) into offhand.
- Then `resolveGatherBanner(world)` resolves a banner via:
  - `ShepherdBannerTracker` for this villager if still a banner block.
  - else nearest banner block in range.
- If no banner found, goal ends and defers.

**Assessment**:
- Wheat can be consumed from chest if banner exists but pen/gate resolution later fails.
- No explicit “at least one paired pen” gate before chest extraction.
- Gather anchor may become any nearby banner, not strictly guaranteed as a currently valid paired pen with gate.

### Current wheat gather movement stages

Current stages:
- `GATHER_WANDER`: random herd-anchored wandering, waits session duration, checks whether herd is following.
- `GUIDE_TO_PEN_CENTER`: opens gate and guides herd toward pen center midpoint logic.
- `RUSH_TO_GATE_AND_CLOSE`: once herd deemed inside, villager rushes to gate and closes it.

**Assessment**:
- Current behavior is *wander + midpoint guidance*, not circular patrol around banner at fixed radius.
- No geometric “full circle complete” condition.
- No explicit “line up with gate direction from circle path then proceed inward” transition.
- Offhand wheat is cleared when herd is inside in `GUIDE_TO_PEN_CENTER` (good), but not tied to arrival at banner.

## Gaps relative to requested behavior

Requested:
1. Wheat in paired chest + at least one paired pen -> shepherd picks up wheat.
2. Otherwise keep wheat in chest and log message (analogous to no eligible pen for banner placement).
3. Start at nearest paired pen banner center.
4. Walk complete circle at 20-block radius around center.
5. When aligned with gate, walk toward gate/banner to funnel animals inward.
6. At banner, remove wheat from hand.
7. Leave pen and close gate.

Current mismatches:
- Trigger gate is banner-presence based, not “paired pen exists”.
- Wheat extraction happens before confirming target pen/gate route viability.
- No circle path planner/state.
- No gate-alignment event derived from circular path progress.
- Wheat removal is tied to herd-inside check, not banner arrival.
- Exit/close after gather exists partially, but no explicit “enter to banner then exit” choreography.

## Leftover or suspicious implementation artifacts

1. **Unused nearest-pen cache fields**
   - `nearestPenCacheTick`, `cachedNearestPenTarget`, `cachedNearestPenGatePos` are written/reset but not read in any decision path.
   - Suggests an incomplete optimization pass or partial rollback.

2. **Dead local variable in `canStart()`**
   - `shearsAddedToChest` is computed but never used.

3. **Mixed search strategy drift**
   - `findNearestPenTarget` currently uses fallback gate search around villager/job/chest anchors and includes verbose per-candidate logging.
   - For gather, pen resolution later relies on `findNearestPenWithBanner(world, gatherBannerPos)`.
   - This is functionally workable, but indicates prior iterative attempts and inconsistent abstractions between banner workflow and gather workflow.

4. **Task precedence caveat**
   - `BANNER` and `SHEARS` always supersede wheat gather, which may be intended but should be explicitly documented because it affects user-visible trigger expectations.

## Recommended design for the new behavior

### A) New preflight gate before wheat pickup

Add a dedicated preflight for wheat gather, executed before `equipWheatForGathering`:

- Resolve nearest **paired pen target** as a struct (banner + center + gate) from shepherd tracker and/or discoverable banner+gate validation.
- If no valid pen target:
  - log: `Shepherd <uuid> has wheat available but no eligible paired pen found; leaving wheat in chest`
  - do not remove wheat from chest/offhand
  - back off with a short retry delay (similar to banner no-pen path)

This directly satisfies the “remain in chest and log” requirement.

### B) Introduce explicit wheat gather micro-state machine

Proposed wheat stages:
1. `WHEAT_PREPARE_ROUTE`
2. `WHEAT_CIRCLE_PATROL`
3. `WHEAT_ALIGN_TO_GATE`
4. `WHEAT_DRIVE_TO_BANNER`
5. `WHEAT_ENTER_PEN`
6. `WHEAT_EXIT_AND_CLOSE`

Keep existing shears/banner stages unchanged.

### C) Circle path algorithm (fixed-radius)

- Inputs: `center` (paired pen center from nearest paired banner), `radius = 20`.
- Build N waypoints on circle (e.g., 16 points, every 22.5°).
- Start angle from villager relative vector to center (closest waypoint index).
- Traverse waypoints in consistent direction (cw/ccw configurable).
- “Complete circle” condition: index wraps once and returns to start window (or cumulative angle >= 2π).
- Repath every few ticks if navigation idles.

### D) Gate alignment trigger

During circle patrol, compute alignment against gate radial direction:

- `gateVec = normalize(gatePos - center)`
- `villagerVec = normalize(villagerPos - center)`
- aligned when `dot(villagerVec, gateVec) >= threshold` (e.g., 0.98)

Transition rule per your requirement:
- Complete full circle first.
- After full circle, on first alignment hit, transition to `WHEAT_DRIVE_TO_BANNER`.

### E) Drive inward sequence

- Ensure gate open near approach.
- Move along gate -> banner/center path (or directly to banner if line is pathable).
- On banner reach, clear offhand wheat immediately.

### F) Exit and close

- After wheat cleared at banner:
  - move to outside-gate target (similar to existing shears inside/outside helpers).
  - close gate when within interact range and confirmed outside.
- Finish with cooldown `nextCheckTime`.

## Suggested refactor points in existing class

Primary touchpoints in `ShepherdSpecialGoal`:

1. `findTaskType(...)` and/or `start()` preflight for wheat pen eligibility.
2. `equipWheatForGathering(...)` call order (move after pen viability resolution).
3. `tick()` switch cases for replacing `GATHER_WANDER/GUIDE_TO_PEN_CENTER/RUSH_TO_GATE_AND_CLOSE` with route-driven states.
4. Add small geometry helpers:
   - `buildCircleWaypoints(center, radius, count)`
   - `isAlignedWithGate(center, gate, actorPos, dotThreshold)`
   - `hasCompletedFullCircle(...)`
5. Reuse existing gate helpers where possible:
   - `ensureGateOpen(...)`
   - `resolveInsideGateTarget(...)`
   - `resolveOutsideGateTarget(...)`
   - `openGate(...)`

## Risk checklist before implementing

1. **Pathfinding at 20-block radius**: ensure waypoints land on walkable y-level; may need height sampling.
2. **Fence collisions**: route should stay outside pen boundary when circling.
3. **Large/irregular pens**: center/gate derived from interior flood-fill may not perfectly match player intuition; logging should include center+gate.
4. **Multi-pen environments**: nearest paired banner should be stable for one session (snapshot target on start).
5. **Offhand overwrite**: avoid clobbering offhand if already occupied by non-wheat item in edge cases.

## Practical implementation order

1. Add wheat preflight + “no eligible paired pen” log, no chest extraction on fail.
2. Introduce new wheat stages and preserve existing behavior behind one temporary feature flag/constant for rollback safety.
3. Implement circle waypoints + completion check.
4. Implement gate alignment and inward drive.
5. Implement banner-reach wheat clear + exit/close.
6. Reduce logging noise and remove stale cache fields/unused locals.

## Summary

The branch currently has a functional chest wake-up pipeline and an existing wheat gather flow, but the gather logic is not yet the requested “20-block full-circle then gate-aligned drive” behavior. It also does not strictly enforce “paired pen must exist before consuming wheat.” The strongest next step is to add a wheat preflight gate (with explicit non-consumption logging) and replace wander-based gather stages with explicit geometric route stages.
