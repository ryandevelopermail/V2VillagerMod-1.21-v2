# QA checklist: Shepherd banner/wheat triggers and butcher conversion

This checklist covers profession behavior validation for:

- `ShepherdBehavior` (chest trigger intake + gather trigger setup)
- `ShepherdSpecialGoal` (pen search, banner placement, gather execution)
- `ButcherBehavior` (weapon-triggered conversion path)

Use this as a manual QA baseline when validating chest-trigger responsiveness for both single and double chests.

## Global setup

- Use a controlled flat test world.
- Ensure gamerules and mobs allow normal villager AI and pathfinding.
- Spawn one villager with profession **shepherd** and one villager with profession **butcher**.
- Ensure each villager has a valid job site and is close enough to discover its paired chest.
- For shepherd scenarios, build a valid sheep pen with:
  - Fence perimeter.
  - At least one gate.
  - A valid interior location where a banner can be placed.
- Keep test pen free of pre-existing banner blocks unless a case requires one.

## Chest-variant matrix (run each scenario twice)

For each scenario below, validate with:

1. **Single chest pair** (normal chest).
2. **Double chest pair** (two chest blocks merged as one inventory).

Record pass/fail separately for each variant to catch inventory-resolution regressions.

---

## Scenario 1: Shepherd banner bootstrap

### Preconditions

- Valid shepherd + job site + paired chest.
- Valid pen + gate in shepherd search radius.
- **No existing banner blocks** in the pen.
- Paired chest initially contains no relevant trigger items.

### Steps

1. Insert one banner item into the paired chest.
2. Wait for profession behavior scan/tick cycle.
3. Observe shepherd movement and goal selection.

### Expected

- `ShepherdBehavior` consumes/recognizes the banner trigger from chest inventory.
- `ShepherdSpecialGoal` starts or is scheduled.
- Shepherd navigates to the pen and places a banner block at a valid pen location.
- Banner placement occurs once for bootstrap and does not spam duplicate placement when one already exists.

### Traceability

- `dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior`
- `dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal`

---

## Scenario 2: Shepherd wheat gather trigger after banner exists

### Preconditions

- Scenario 1 completed (banner exists in pen).
- Paired chest has no wheat at test start.

### Steps

1. Insert wheat into the paired chest.
2. Wait for behavior tick/goal update.
3. Observe shepherd state over time.

### Expected

- `ShepherdBehavior` recognizes wheat trigger only after banner bootstrap is satisfied.
- `ShepherdSpecialGoal` enters gather session (or equivalent gather state) and progresses through gather actions over subsequent ticks.
- Gather path shows progression (navigation + sheep interaction/collection behavior), not a one-tick start/stop loop.

### Traceability

- `dev.sterner.guardvillagers.common.villager.behavior.ShepherdBehavior`
- `dev.sterner.guardvillagers.common.entity.goal.ShepherdSpecialGoal`

---

## Scenario 3: Butcher conversion weapon triggers

### Preconditions

- Valid butcher + paired chest.
- No conflicting conversion triggers in chest.

### Case 3A — axe conversion

1. Place an axe in paired chest.
2. Wait for conversion scan.
3. Confirm butcher conversion/guard spawn result.

**Expected**

- Conversion triggers from axe input path in `ButcherBehavior`.
- Spawned entity keeps axe equipped after spawn initialization completes.

### Case 3B — sword conversion

1. Reset to pre-conversion butcher state.
2. Place a sword in paired chest.
3. Wait for conversion scan.
4. Confirm butcher conversion/guard spawn result.

**Expected**

- Conversion triggers from sword input path in `ButcherBehavior`.
- Spawned entity keeps sword equipped after spawn initialization completes.

### Traceability

- `dev.sterner.guardvillagers.common.villager.behavior.ButcherBehavior`

---

## Regression notes to capture

- Trigger responsiveness parity between single and double chest variants.
- No false positives when trigger item is absent.
- No repeated conversion/placement loops once scenario success state has been reached.
- Entity equipment persistence is stable across save/reload (optional extended check).
