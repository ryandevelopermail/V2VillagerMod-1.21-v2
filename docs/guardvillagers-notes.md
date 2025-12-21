# Guard Villagers quick reference

Purpose-built pointers for adjusting guard hostility and creation behavior.

## Hostile response logic
- `GuardEntity` registers target goals for raiders, witches, ravagers, zombies, players that anger them, and (optionally) **all monsters** when `GuardVillagersConfig.attackAllMobs` is enabled. The targeting setup lives in `setupGoals` inside `src/main/java/dev/sterner/guardvillagers/common/entity/GuardEntity.java`.
- Target filtering is handled by `canTarget` in the same class. Guards refuse targets that:
  - Appear in `GuardVillagersConfig.mobBlackList`.
  - Have **Hero of the Village**.
  - Are their owner, another guard, an iron golem, or a villager.

## New guard creation paths
- **Natural conversion from villagers:** `GuardVillagers` subscribes to `ENTITY_LOAD` and, for natural villagers, randomly spawns a guard at the villager’s position based on `spawnChancePerVillager`. This logic is near the start of `src/main/java/dev/sterner/guardvillagers/GuardVillagers.java`.
- **Player-triggered conversion:** In the same class, `villagerConvert` lets a sneaking player right-click a nitwit/unemployed villager with a sword or crossbow to convert it. The check optionally requires **Hero of the Village** via `GuardVillagersConfig.convertVillagerIfHaveHotv`.

## Where to tweak behaviors
- Targeting and hostility rules: `src/main/java/dev/sterner/guardvillagers/common/entity/GuardEntity.java` (goal registrations and `canTarget`).
- Spawn/config values: `src/main/java/dev/sterner/guardvillagers/GuardVillagersConfig.java` (flags like `attackAllMobs`, `mobBlackList`, `spawnChancePerVillager`, and HOTV gates).
- Villager conversion & spawn hook: `src/main/java/dev/sterner/guardvillagers/GuardVillagers.java`.
