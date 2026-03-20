# CLAUDE.md — V2VillagerMod-1.21 (GuardVillagers)

## Project overview

**V2VillagerMod-1.21** (mod id: `guardvillagers`) is a Minecraft 1.21 **Fabric mod** that overhauls villager AI with specialized guard and worker entities. Vanilla villagers are converted to typed guards/workers that perform profession-appropriate tasks: farming, fishing, stonecutting, lumberjacking, butchering, and combat.

**Key author/group:** `dev.sterner` / MrSterner
**License:** MIT

---

## Technology stack

| Component | Version |
|-----------|---------|
| Minecraft | 1.21 |
| Fabric Loader | ≥ 0.15.11 |
| Fabric API | 0.100.1+1.21 |
| Yarn Mappings | 1.21+build.1 |
| Java | 21 (compile target), source/target compat 17 |
| Build system | Gradle 8 + Fabric Loom 1.6-SNAPSHOT |
| Config library | MidnightLib 1.5.7-fabric (bundled via `include`) |
| Test framework | JUnit Jupiter 5.10.2 |

---

## Build & run

```bash
# Build the mod jar
./gradlew build

# Run unit tests only (no Minecraft required)
./gradlew test

# Run the Minecraft client with the mod loaded (dev environment)
./gradlew runClient

# Run the Minecraft server
./gradlew runServer
```

Build artifacts are placed in `build/libs/`. CI runs `./gradlew build` on Linux (ubuntu-22.04) and Windows (windows-2022) via GitHub Actions (`.github/workflows/build.yml`).

The **access widener** at `src/main/resources/guardvillagers.accesswidener` opens private Minecraft internals needed by the mod.

---

## Repository layout

```
V2VillagerMod-1.21-v2/
├── build.gradle                  # Fabric Loom build config
├── gradle.properties             # Version pins (MC, Fabric, MidnightLib)
├── settings.gradle               # Gradle plugin repos
├── docs/                         # Design docs & QA checklists (markdown)
├── logs/                         # Dev-session logs
└── src/
    ├── main/
    │   ├── java/dev/sterner/guardvillagers/
    │   │   ├── GuardVillagers.java           # Mod initializer (server + common)
    │   │   ├── GuardVillagersClient.java     # Client-side initializer
    │   │   ├── GuardVillagersConfig.java     # MidnightLib config entries
    │   │   ├── client/
    │   │   │   ├── model/                   # Entity models (GuardVillagerModel, GuardSteveModel, GuardArmorModel)
    │   │   │   ├── renderer/                # GuardRenderer
    │   │   │   └── screen/                  # GuardVillagerScreen
    │   │   ├── common/
    │   │   │   ├── entity/                  # Guard entity classes
    │   │   │   │   ├── GuardEntity.java     # Base guard (combat, inventory, variants)
    │   │   │   │   ├── AxeGuardEntity.java
    │   │   │   │   ├── ButcherGuardEntity.java
    │   │   │   │   ├── FishermanGuardEntity.java
    │   │   │   │   ├── LumberjackGuardEntity.java
    │   │   │   │   ├── MasonGuardEntity.java
    │   │   │   │   ├── GuardEntityLootTables.java
    │   │   │   │   └── goal/                # All AI goals (~80 classes)
    │   │   │   ├── entity/task/             # Villager brain tasks
    │   │   │   ├── event/                   # Fabric event handlers
    │   │   │   ├── handler/                 # Armor stand conversion, job block placement
    │   │   │   ├── network/                 # GuardFollowPacket, GuardPatrolPacket, GuardData
    │   │   │   ├── screenhandler/           # GuardVillagerScreenHandler
    │   │   │   ├── util/                    # Village managers, distribution routing, state
    │   │   │   └── villager/                # Profession framework
    │   │   │       ├── ProfessionDefinitions.java  # Single registration source of truth
    │   │   │       ├── VillagerProfessionBehavior.java
    │   │   │       ├── VillagerProfessionBehaviorRegistry.java
    │   │   │       └── behavior/            # Per-profession behavior implementations
    │   │   └── mixin/                       # Mixins into vanilla Minecraft classes
    │   └── resources/
    │       ├── fabric.mod.json
    │       ├── guardvillagers.mixins.json
    │       ├── guardvillagers.accesswidener
    │       └── assets/guardvillagers/       # Textures, models, blockstates, sounds, lang
    └── test/
        └── java/dev/sterner/guardvillagers/ # JUnit 5 unit tests
```

---

## Core systems

### 1. Entity types

All entity types are registered in `GuardVillagers.java`:

| Entity | Type ID | Description |
|--------|---------|-------------|
| `GuardEntity` | `guardvillagers:guard` | Base combat guard; carries sword/bow/crossbow; has inventory |
| `AxeGuardEntity` | `guardvillagers:axe_guard` | Axe-wielding variant |
| `ButcherGuardEntity` | `guardvillagers:butcher_guard` | Processes meat via smoker |
| `FishermanGuardEntity` | `guardvillagers:fisherman_guard` | Fishes; deposits to barrel |
| `LumberjackGuardEntity` | `guardvillagers:lumberjack_guard` | Chops trees; manages charcoal/furnace |
| `MasonGuardEntity` | `guardvillagers:mason_guard` | Stonecutting; wall building; mining stairs |

Spawn eggs and two special blocks (`guard_stand_modifier`, `guard_stand_anchor`) are also registered.

### 2. Guard spawning & conversion

- **Natural spawn**: When a vanilla `VillagerEntity` loads, a `GuardEntity` spawns nearby at a configurable chance (`spawnChancePerVillager`, default 0.5).
- **Conversion**: Specific profession villagers are converted to typed guard/worker entities via `ProfessionDefinitions` conversion hooks, which run on a tick schedule.
- **Goat horn**: Using a goat horn rallies nearby guards to the player's position.
- Conversion logic lives in `GuardConversionHelper` and `ArmorStandConversionHandler`.

### 3. Profession behavior framework

The village worker system is driven by **block pairing events**:

1. Place a **chest** within 3 blocks of a villager's job site → `onChestPaired()` fires.
2. Place a **crafting table** near job site + chest → `onCraftingTablePaired()` fires.
3. Place a **special modifier block** in range → `onSpecialModifierPaired()` fires.

**Registration bootstrap** (called once from `GuardVillagers.onInitialize()`):
```java
ProfessionDefinitions.registerAll();
```

`ProfessionDefinitions` is the **single source of truth** for all 13 profession definitions (armorer, butcher, cartographer, cleric, farmer, fisherman, fletcher, librarian, leatherworker, mason, shepherd, toolsmith, weaponsmith) and lumberjack unemployed conversion.

To add a new profession:
1. Create a class in `common/villager/behavior/` implementing `VillagerProfessionBehavior`.
2. Add an entry to `ProfessionDefinitions.DEFINITIONS`.
3. Implement the relevant callback(s): `onChestPaired`, `onCraftingTablePaired`, `onSpecialModifierPaired`.

See `docs/villager-profession-framework.md` for full design details.

### 4. AI goal system

Goals live in `common/entity/goal/`. Key patterns:

- `AbstractCraftingGoal` — base for all profession crafting goals.
- `AbstractInventoryDistributionGoal` — base for item routing between chests.
- Profession goals follow naming: `<Profession>CraftingGoal`, `<Profession>DistributionGoal`, `<Profession>SpecialGoal`.
- Combat goals: `RangedBowAttackPassiveGoal`, `RangedCrossbowAttackPassiveGoal`, `RaiseShieldGoal`, `KickGoal`, `HeroHurtTargetGoal`.
- Utility goals: `GuardEatFoodGoal`, `HealGolemGoal`, `HealGuardAndPlayerGoal`, `WalkBackToCheckPointGoal`.

### 5. Item distribution system

Items flow between paired chests via `AbstractInventoryDistributionGoal` subclasses. A planned `UniversalDistributionRouter` (see `docs/universal-distribution-framework.md`) will centralize routing rules so that, e.g., any chest containing seeds automatically routes them to the nearest farmer chest.

Current routing helpers: `DistributionRecipientHelper`, `DistributionRouteEngine`, `DistributionInventoryAccess`.

### 6. Village state management

Persistent per-world state is managed by several managers:

| Class | Responsibility |
|-------|---------------|
| `VillageAnchorState` | QM chest as village anchor |
| `VillageMappedBoundsState` | Village spatial bounds |
| `BellChestMappingState` | Bell→chest mappings |
| `VillageGuardStandManager` | Guard stand placement and equipment sync |
| `VillagePenRegistry` | Animal pen locations (shepherd) |
| `VillageLumberjackSpawnManager` | Lumberjack population management |
| `VillageMembershipTracker` | Tracks which villagers belong to which bell/village |
| `VillagerBellTracker` | Bell ring → villager station assignment |
| `ConvertedWorkerJobSiteReservationManager` | Prevents vanilla POI re-claim of converted worker job sites |

### 7. Mixins

All mixins are in `dev.sterner.guardvillagers.mixin` (registered in `guardvillagers.mixins.json`):

| Mixin | Purpose |
|-------|---------|
| `BellBlockMixin` | Trigger village logic on bell ring |
| `VillagerEntityMixin` | Hook villager brain tasks |
| `VillagerTaskListProviderMixin` | Inject custom brain tasks |
| `TakeJobSiteTaskMixin` | Block converted workers from reclaiming job sites |
| `FindPointOfInterestTaskMixin` | Diagnostics injection |
| `ChestBlockEntityMixin` | Pairing event on chest placement/load |
| `BarrelBlockEntityMixin` | Same for barrels |
| `ArmorStandEntityMixin` | Guard stand equipment sync |
| `MobEntityMixin` | Target acquisition hooks |
| `ServerWorldMixin` | World-level hooks |
| `StructureTemplateMixin` | Structure processing hooks |

### 8. Networking

Two custom packets (both C2S and S2C):
- `GuardFollowPacket` — toggles guard follow mode for a player.
- `GuardPatrolPacket` — sets a guard patrol point.

Registered via Fabric's `PayloadTypeRegistry`.

### 9. Configuration

`GuardVillagersConfig` (MidnightLib) exposes all tunable values. Key entries:

| Config key | Default | Description |
|-----------|---------|-------------|
| `spawnChancePerVillager` | 0.5 | Chance a guard spawns when a villager loads |
| `healthModifier` | 20.0 | Guard max health |
| `speedModifier` | 0.5 | Guard movement speed |
| `followRangeModifier` | 20.0 | Guard follow/goat-horn rally range |
| `guardVillagerHelpRange` | 50 | Range at which guards respond to attacked villagers |
| `reputationRequirement` | 15 | Minimum player reputation to receive guard items |
| `masonTableDailyCraftLimit` | 4 | Max crafts per day for mason table |
| `farmerWheatSeedReserveCap` | 64 | Max seeds farmer retains for planting |
| `villagerConversionExecutionIntervalTicks` | 40 | How often conversion hooks run (min 20) |

---

## Key conventions

### Logging
- Use `LOGGER.debug(...)` for diagnostic/per-tick messages — **never** `LOGGER.info(...)` in hot paths.
- Info-level is reserved for startup registration and meaningful lifecycle events.
- Prefix log messages with `[system-name]` for easy grep (e.g., `[recipe-demand-index]`).

### Performance
- **Never** scan world bounds or use `getWorldBounds()` — use player-proximity boxes (`Box`) instead.
- Cache scan results with TTL counters (e.g., farmland scan TTL = 200 ticks).
- All O(n²) or large-radius scans must be justified and bounded.

### Server-only code
- All entity logic, goal evaluation, and state management is server-side only.
- Use `world.isClient()` guards before any client-only calls.
- Entity goals run only on `ServerWorld`.

### NBT persistence
- Guard entities serialize their inventory and paired block positions in `writeCustomDataToNbt` / `readCustomDataFromNbt`.
- Clamp extracted stacks to `maxCount` before NBT writes to prevent overflow.

### Tick budgets
- Conversion hooks: minimum 20 ticks between runs, configurable.
- Bell-chest reconciliation: every 1200 ticks (60 s).
- Reservation reconciliation: every 300 ticks.

### Goal priority ordering
Higher priority number = lower priority in Minecraft's goal selector. Combat and movement safety goals get low numbers (high priority); idle/crafting goals get higher numbers.

### Paired block pattern
Every converted worker tracks:
- `pairedChestPos` — the chest paired to their job site.
- `pairedCraftingTablePos` (where applicable) — crafting table near chest+job site.
- `pairedJobPos` / `pairedSmokerPos` — profession-specific job block.

Reservations are managed by `ConvertedWorkerJobSiteReservationManager` to prevent vanilla POI system from stealing job blocks back.

---

## Testing

Unit tests live in `src/test/java/dev/sterner/guardvillagers/`. They use JUnit 5 and **do not require a running Minecraft instance** — goals/utilities are tested via lightweight mocks and pure-logic contracts.

Test classes follow the pattern `<ClassName>Test.java`. Key coverage areas:
- Lumberjack chop/deposit/crafting goal logic
- Farmer harvest goal startup readiness
- Fisherman crafting goal recipe selection
- Mason stonecutting reserve and distribution
- Mason mining stair, table crafting
- Toolsmith crafting distribution handoff
- `RecipeDemandIndex` caching behavior

Run tests with:
```bash
./gradlew test
```

---

## Docs directory

Design documents in `docs/`:

| File | Content |
|------|---------|
| `villager-profession-framework.md` | How to add profession behaviors and special modifiers; QA checklist |
| `universal-distribution-framework.md` | Planned universal item routing architecture (X→Y routing rules) |
| `guardvillagers-notes.md` | Dev notes |
| `playtest-per-profession.md` | Per-profession playtest checklist |
| `shepherd-butcher-trigger-qa-checklist.md` | QA scenarios for shepherd/butcher triggers |
| `shepherd-special-trigger-diagnosis.md` | Diagnosis notes for shepherd special trigger |
| `shepherd-wheat-gather-deep-analysis.md` | Deep-dive analysis of shepherd wheat gathering |

QA harness scenario IDs are also in `src/main/java/dev/sterner/guardvillagers/common/villager/behavior/QA_TEST_HARNESS_NOTES.md`.

---

## Branches

| Branch | Purpose |
|--------|---------|
| `master` | Stable releases |
| `feature/village-identity-bell-system` | Bell/village identity work |
| `claude/add-claude-documentation-x1uQt` | Active development branch |

---

## Common tasks for AI assistants

### Adding a new worker profession
1. Create `<Profession>Behavior.java` in `common/villager/behavior/` implementing `VillagerProfessionBehavior`.
2. Add goals to the entity's `initGoals()` (or use behavior callback to register them dynamically).
3. Register in `ProfessionDefinitions.DEFINITIONS` with the correct job block set and behavior factory.
4. Add a conversion hook if unemployed → profession conversion is needed.
5. Add unit tests for goal start/execute/stop logic.

### Adding a new AI goal
1. Create a goal class in `common/entity/goal/` extending the appropriate Minecraft `Goal` superclass or one of the mod's abstract bases.
2. Register it in the target entity's `initGoals()` with the appropriate priority.
3. Use `LOGGER.debug(...)` for diagnostic logging; never `LOGGER.info(...)` in tick methods.
4. Add a unit test covering `canStart()` conditions.

### Adding a config entry
Add a `@Entry`-annotated static field to `GuardVillagersConfig`. MidnightLib handles serialization automatically. Use `@Entry(min=N)` for numeric lower bounds.

### Modifying item distribution routing
- Per-profession routing: modify the relevant `<Profession>DistributionGoal`.
- Global routing (future): add a rule to the `UniversalDistributionRouter` (see `docs/universal-distribution-framework.md`).

### Debugging performance issues
- Check for unbounded world scans — all entity queries should use a `Box` or `AABB`.
- Check for INFO-level logging in per-tick code — demote to DEBUG.
- Verify scan radii are bounded (e.g., shepherd pen scan ≤ 64, farmer hoe radius ≤ 10).
