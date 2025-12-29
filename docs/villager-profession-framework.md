# Villager Profession Modification Framework

This document outlines the new framework for building villager profession upgrades around paired blocks.
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

### Behavior registry
`VillagerProfessionBehaviorRegistry` wires professions to behaviors and special modifiers.

- **Profession behavior**: handles chest/crafting/special modifier callbacks.
- **Special modifier**: defines a block plus the max range it must be from the job site + chest pair.

The registry lives in:
- `src/main/java/dev/sterner/guardvillagers/common/villager/VillagerProfessionBehaviorRegistry.java`

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
2. Register it in `VillagerProfessionBehaviors.register()`.
3. Implement profession-specific logic in the callbacks.

Example skeleton:

```java
public final class FarmerBehavior implements VillagerProfessionBehavior {
    @Override
    public void onChestPaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        // Enable chest storage + job block interactions.
    }

    @Override
    public void onCraftingTablePaired(ServerWorld world, VillagerEntity villager, BlockPos jobPos, BlockPos chestPos, BlockPos craftingTablePos) {
        // Unlock farmer-specific crafting recipes.
    }
}
```

Registration entry point:
- `src/main/java/dev/sterner/guardvillagers/common/villager/VillagerProfessionBehaviors.java`

```java
public static void register() {
    VillagerProfessionBehaviorRegistry.registerBehavior(VillagerProfession.FARMER, new FarmerBehavior());
}
```

## How to add a special modifier block

1. Create a `SpecialModifier` with an ID, block, and range.
2. Register it in `VillagerProfessionBehaviors.register()`.
3. Handle it in `onSpecialModifierPaired`.

```java
public static void register() {
    VillagerProfessionBehaviorRegistry.registerSpecialModifier(
        new SpecialModifier(GuardVillagers.id("shepherd_banner"), Blocks.WHITE_BANNER, 6.0D)
    );
}
```

When the banner is placed within the modifier range of both the job site and the paired chest, the registry dispatches `onSpecialModifierPaired`.

## Where to hook new functionality

- **Registry wiring**: `VillagerProfessionBehaviors.register()`
- **Pairing event dispatch**: `JobBlockPairingHelper`
- **Placement trigger**: `JobBlockPlacementHandler`

## Notes on design intent

- Chest pairing unlocks core “functional” behavior for the job block and storage usage.
- Crafting table pairing enables profession-specific crafting.
- Special modifiers enable richer behavior (e.g., a shepherd guiding animals between banners).

Each profession can opt into any subset of these callbacks.
