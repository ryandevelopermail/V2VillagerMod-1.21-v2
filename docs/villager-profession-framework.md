# Villager Profession Modification Framework

This document outlines the profession framework for villager upgrades around paired blocks.
It focuses on three event categories:

1. **Chest pairing** within 3 blocks of a villager’s job site.
2. **Crafting table pairing** within 3 blocks of both the job site and the paired chest.
3. **Special modifier blocks** within a configurable range of both the job site and the paired chest.

## Core concepts

### Pairing events
Pairing events are emitted by `JobBlockPairingHelper` when a block is placed:

- **Chest pairing**: `handlePairingBlockPlacement` triggers when a chest is placed near a villager job site.
- **Crafting table pairing**: `handleCraftingTablePlacement` triggers when a crafting table is placed near both the job site and chest.
- **Special modifiers**: `handleSpecialModifierPlacement` triggers when a registered modifier block is placed near both the job site and chest.

All three events play a pairing animation and then notify the profession registry.

### Single source of truth: `ProfessionDefinitions`
`ProfessionDefinitions` is the canonical registration path for profession behavior metadata.
Each `ProfessionDefinition` contains:

- Profession id.
- Profession instance.
- Expected job block set.
- Behavior factory.
- Optional conversion hook (run from server tick loop).
- Optional profession-specific modifier definitions.

The registration bootstrap lives in:
- `src/main/java/dev/sterner/guardvillagers/common/villager/ProfessionDefinitions.java`

`GuardVillagers.onInitialize()` now calls:

```java
ProfessionDefinitions.registerAll();
```

This single bootstrap registers all profession behaviors and all special modifiers (including global ones).

### Behavior registry
`VillagerProfessionBehaviorRegistry` remains the runtime dispatcher.
`ProfessionDefinitions.registerAll()` populates it once.

- **Profession behavior**: handles chest/crafting/special modifier callbacks.
- **Special modifier**: defines a block plus the max range it must be from the job site + chest pair.

### Behavior interface
Implement `VillagerProfessionBehavior` to handle profession-specific logic.

Location:
- `src/main/java/dev/sterner/guardvillagers/common/villager/VillagerProfessionBehavior.java`

Callbacks:
- `onChestPaired(...)`
- `onCraftingTablePaired(...)`
- `onSpecialModifierPaired(...)`

## How to add a profession behavior

1. Create a behavior implementation per profession.
2. Add a `ProfessionDefinition` entry in `ProfessionDefinitions`.
3. Implement profession-specific logic in the callbacks.

Example definition entry:

```java
definition(VillagerProfession.FARMER, Set.of(Blocks.COMPOSTER), FarmerBehavior::new)
```

## How to add a special modifier block

1. Create a `SpecialModifier` with an ID, block, and range.
2. Register it either in:
   - `GLOBAL_SPECIAL_MODIFIERS` (applies for all professions), or
   - the `specialModifiers` list inside a specific `ProfessionDefinition`.
3. Handle it in `onSpecialModifierPaired` inside profession behavior(s).

## Where to hook new functionality

- **Single registration/bootstrap**: `ProfessionDefinitions.registerAll()`
- **Definition source of truth**: `ProfessionDefinitions`
- **Pairing event dispatch**: `JobBlockPairingHelper`
- **Placement trigger**: `JobBlockPlacementHandler`

## Notes on design intent

- Chest pairing unlocks core “functional” behavior for the job block and storage usage.
- Crafting table pairing enables profession-specific crafting.
- Special modifiers enable richer behavior (e.g., a shepherd guiding animals between banners).

Each profession can opt into any subset of callbacks and an optional conversion hook.

## QA checklist: armorer chest-gated golem healing

Use this checklist for manual validation of the armorer golem-healing path introduced via profession framework behavior.

### Test setup

- Spawn or locate an **armorer** villager with a valid armorer job site.
- Ensure a **paired chest** exists for that armorer job-site pair.
- Prepare an **iron golem with missing health** (pre-damaged).
- Place known iron ingot stack counts in the paired chest (for consumption checks).

### Scenario checks

1. **Armorer + paired chest with iron + damaged golem => armorer approaches and heals**
   - Preconditions:
     - Armorer has a paired chest.
     - Paired chest contains iron ingots.
     - Damaged iron golem is in valid interaction range.
   - Expected:
     - Armorer selects golem-healing behavior and moves to/engages the golem.
     - Golem receives healing ticks while iron is available.

2. **Each heal consumes one ingot from the paired chest**
   - Preconditions:
     - Same as scenario (1), with a measurable stack count.
   - Expected:
     - After each successful heal event, chest iron count decreases by exactly 1.
     - Over multiple heals, total consumption equals the number of successful heal events.

3. **No iron in paired chest => armorer does not start golem healing**
   - Preconditions:
     - Armorer has a paired chest.
     - Paired chest has zero iron ingots.
     - Damaged golem present.
   - Expected:
     - Armorer does not enter golem-healing action path.
     - No heal effects are applied to the golem.

4. **Unpaired/missing chest => no healing attempt for armorer path**
   - Preconditions:
     - Remove pairing or remove/invalidate the paired chest.
     - Damaged golem present.
   - Expected:
     - Armorer does not attempt chest-gated golem healing.
     - No heal action should begin from the armorer profession logic.

### Observable tester signals

- **Successful heal emits sound:** golem repair sound plays whenever a heal is successfully applied.
- **Resource consumption is visible:** iron stack count in the paired chest decreases after each successful heal.
- **Healing stops when resources are exhausted:** once iron runs out, no further heal events occur.

### Profession-safety regression checks

Confirm existing non-armorer smith behavior remains unchanged:

- **Weaponsmith golem-healing behavior remains unchanged** relative to previous baseline expectations.
- **Toolsmith golem-healing behavior remains unchanged** relative to previous baseline expectations.
- Verify there is no unintended cross-profession routing where armorer-only chest-gated logic alters weaponsmith/toolsmith behavior.
