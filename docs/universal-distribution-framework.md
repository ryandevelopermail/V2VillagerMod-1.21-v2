# Universal Distribution Framework (X -> Y Profession Rules)

## Goal
Create a **single routing framework** so every v2 villager can answer:

- "I found item **X** in my paired chest"
- "Which profession chest should receive it (**Y**)?"

without writing custom per-goal logic each time.

This document proposes a shared architecture and migration plan that keeps existing profession behavior while adding universal rules (starting with seed forwarding).

---

## Current State (Why this is needed)

The branch currently has item routing implemented in several isolated places:

- profession-specific distribution goals derived from `AbstractInventoryDistributionGoal`
- mason guard chest distribution with local item classification and recipient lookup
- direct profession recipient methods in `DistributionRecipientHelper`

This works, but adding a new global routing rule (e.g., "all seeds go to farmers") currently requires touching many classes.

---

## Proposed Architecture

## 1) Shared rule model
Introduce a central rule table with entries like:

- **match**: item/item tag/predicate
- **target profession**: e.g. `FARMER`
- **required job block**: e.g. `COMPOSTER`
- **priority**: allow conflict resolution between broad and narrow rules
- **mode**: `ALWAYS`, `OVERFLOW_ONLY`, or `NEVER` for specific goals

Example canonical rule entries:

1. `ItemTags.VILLAGER_PLANTABLE_SEEDS` -> `FARMER` (`COMPOSTER`) [ALWAYS]
2. `WHEAT` -> `SHEPHERD` (`LOOM`) [ALWAYS]
3. Mason raw stone set -> `LIBRARIAN` (`LECTERN`) [OVERFLOW_ONLY when source >= threshold]

## 2) Shared recipient resolver
Add/extend one generic helper method:

`findEligibleRecipients(world, sourceVillager, range, profession, expectedJobBlock)`

All universal rules call this API; profession-specific helper methods become convenience wrappers.

## 3) Shared routing decision flow
Every distribution goal should use the same pipeline:

1. Scan source inventory.
2. Evaluate universal rules by priority.
3. If a rule matches and recipients exist, create pending transfer from that rule.
4. Otherwise fall back to the goal's local/specialized behavior.

This gives universal behavior without breaking custom logic (e.g., fletcher guard equipment routing).

## 4) Overflow policy as framework config
Move chest fullness routing thresholds into shared config:

- default overflow trigger: `0.825` (covers the requested 80-85% target)
- goal override allowed where necessary

Any rule marked `OVERFLOW_ONLY` is only eligible when source fullness exceeds threshold.

---

## Seed Rule (Your immediate requirement)

Implement this first as the baseline universal rule:

- If any profession chest contains `ItemTags.VILLAGER_PLANTABLE_SEEDS`, route to nearest eligible **farmer paired chest**.
- Should run universally before profession-local item routing unless a goal explicitly opts out.

This ensures:

- mason-guard seed forwarding still works
- seeds in non-farmer villager chests are no longer stranded
- future professions automatically inherit seed routing

---

## Integration Strategy (Low-risk)

## Phase 1: Framework + opt-in
1. Add shared rule registry and generic recipient resolver.
2. Add helper methods to `AbstractInventoryDistributionGoal` for universal pre-check and pending transfer setup.
3. Keep all existing goal logic as-is.

## Phase 2: Incremental goal migration
For each villager distribution goal:

- call universal pre-check first (`tryUniversalTransfer`)
- if no universal transfer, execute existing specialized flow

Migrate in this order:

1. `FarmerDistributionGoal`
2. `ClericDistributionGoal`
3. `FishermanDistributionGoal`
4. `ToolsmithDistributionGoal`
5. `ArmorerDistributionGoal`
6. `LeatherworkerDistributionGoal`
7. `CartographerToLibrarianDistributionGoal`
8. `ShepherdToLibrarianDistributionGoal`
9. `MasonToLibrarianDistributionGoal` (align overflow policy)

## Phase 3: guard parity
Adapt `MasonGuardChestDistributionGoal` to consume the same shared rule registry so guard and villager behavior cannot drift.

---

## Conflict / Precedence Rules

When multiple rules match the same item:

1. highest priority wins
2. if same priority, shortest source->recipient distance wins
3. if tie, deterministic UUID or chest-pos ordering

Fallback behavior:

- no recipient available -> keep item in source chest, retry on cooldown
- recipient full -> keep pending item, retry or return to source per existing goal contract

---

## Suggested Interfaces

```java
record DistributionRouteRule(
    String id,
    Predicate<ItemStack> matcher,
    VillagerProfession targetProfession,
    Block expectedJobBlock,
    int priority,
    RoutingMode mode
) {}

enum RoutingMode {
    ALWAYS,
    OVERFLOW_ONLY
}
```

```java
interface UniversalDistributionRouter {
    Optional<ResolvedRoute> resolve(
        ServerWorld world,
        VillagerEntity source,
        Inventory inventory,
        double sourceFullness,
        double recipientRange
    );
}
```

---

## QA Acceptance Checklist

1. Place seeds in mason chest -> seeds route to farmer chest.
2. Place seeds in toolsmith chest -> seeds route to farmer chest.
3. Place seeds in farmer chest with no nearby farmer recipients (only self) -> no duplicate churn.
4. Source chest below overflow threshold does not trigger overflow-only routes.
5. Source chest above threshold triggers overflow-only route to librarian.
6. If target chest is full, source keeps item and retries without deletion.

---

## Why this approach fits your workflow

- You define X->Y routes once in one table.
- New professions or material rules become data additions, not multi-class rewrites.
- Existing behavior can be preserved during migration.
- Seed forwarding becomes truly universal immediately after Phase 2 starts.
