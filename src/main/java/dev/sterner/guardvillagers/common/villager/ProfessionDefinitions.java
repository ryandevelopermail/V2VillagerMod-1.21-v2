package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.villager.behavior.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ProfessionDefinitions {
    private static final List<SpecialModifier> GLOBAL_SPECIAL_MODIFIERS = List.of(
            new SpecialModifier(GuardVillagers.id("guard_stand_modifier"), GuardVillagers.GUARD_STAND_MODIFIER, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE),
            new SpecialModifier(GuardVillagers.id("guard_stand_anchor"), GuardVillagers.GUARD_STAND_ANCHOR, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE)
    );

    private static final List<ProfessionDefinition> DEFINITIONS = List.of(
            definition(VillagerProfession.ARMORER, Set.of(Blocks.BLAST_FURNACE), ArmorerBehavior::new),
            definition(VillagerProfession.BUTCHER, Set.of(Blocks.SMOKER), ButcherBehavior::new, ButcherBehavior::tryConvertButchersWithWeapon),
            definition(VillagerProfession.CARTOGRAPHER, Set.of(Blocks.CARTOGRAPHY_TABLE), CartographerBehavior::new),
            definition(VillagerProfession.CLERIC, Set.of(Blocks.BREWING_STAND), ClericBehavior::new),
            definition(VillagerProfession.FARMER, Set.of(Blocks.COMPOSTER), FarmerBehavior::new),
            definition(VillagerProfession.FISHERMAN, Set.of(Blocks.BARREL), FishermanBehavior::new, FishermanBehavior::tryConvertFishermenWithRod),
            definition(VillagerProfession.FLETCHER, Set.of(Blocks.FLETCHING_TABLE), FletcherBehavior::new),
            definition(VillagerProfession.LIBRARIAN, Set.of(Blocks.LECTERN), LibrarianBehavior::new),
            definition(VillagerProfession.LEATHERWORKER, Set.of(Blocks.CAULDRON), LeatherworkerBehavior::new),
            definition(VillagerProfession.MASON, Set.of(Blocks.STONECUTTER), MasonBehavior::new, MasonBehavior::tryConvertMasonsWithMiningTool),
            definition(VillagerProfession.SHEPHERD, Set.of(Blocks.LOOM), ShepherdBehavior::new),
            definition(VillagerProfession.TOOLSMITH, Set.of(Blocks.SMITHING_TABLE), ToolsmithBehavior::new),
            definition(VillagerProfession.WEAPONSMITH, Set.of(Blocks.GRINDSTONE), WeaponsmithBehavior::new)
    );

    private static final List<Consumer<ServerWorld>> UNEMPLOYED_CONVERSION_HOOKS = List.of(
            UnemployedLumberjackConversionHook::tryConvertUnemployedVillagersNearCraftingTables
    );

    private static final Map<VillagerProfession, ProfessionDefinition> DEFINITIONS_BY_PROFESSION = DEFINITIONS.stream()
            .collect(Collectors.toUnmodifiableMap(ProfessionDefinition::profession, definition -> definition));
    private static final Map<net.minecraft.util.Identifier, Set<Block>> EXTERNAL_JOB_BLOCKS_BY_PROFESSION = new HashMap<>();

    private static boolean registered;

    private ProfessionDefinitions() {
    }

    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        for (ProfessionDefinition definition : DEFINITIONS) {
            VillagerProfessionBehaviorRegistry.registerBehavior(definition.profession(), definition.behaviorFactory().get());
            for (SpecialModifier specialModifier : definition.specialModifiers()) {
                VillagerProfessionBehaviorRegistry.registerSpecialModifier(specialModifier);
            }
        }

        for (SpecialModifier specialModifier : GLOBAL_SPECIAL_MODIFIERS) {
            VillagerProfessionBehaviorRegistry.registerSpecialModifier(specialModifier);
        }
    }

    public static boolean hasDefinition(VillagerProfession profession) {
        return DEFINITIONS_BY_PROFESSION.containsKey(profession);
    }

    public static Optional<ProfessionDefinition> get(VillagerProfession profession) {
        return Optional.ofNullable(DEFINITIONS_BY_PROFESSION.get(profession));
    }

    public static boolean isExpectedJobBlock(VillagerProfession profession, BlockState blockState) {
        if (profession == VillagerProfession.NONE) {
            return blockState.isOf(Blocks.CRAFTING_TABLE);
        }

        boolean vanillaMatch = get(profession)
                .map(definition -> definition.expectedJobBlocks().contains(blockState.getBlock()))
                .orElse(false);
        if (vanillaMatch) {
            return true;
        }
        net.minecraft.util.Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
        return professionId != null
                && EXTERNAL_JOB_BLOCKS_BY_PROFESSION.getOrDefault(professionId, Set.of()).contains(blockState.getBlock());
    }

    /** Shared job-block knowledge for safety scans that are not tied to one villager profession. */
    public static boolean isKnownJobBlock(BlockState blockState) {
        if (blockState.isOf(Blocks.CRAFTING_TABLE)) {
            return true;
        }
        Block block = blockState.getBlock();
        if (DEFINITIONS.stream().anyMatch(definition -> definition.expectedJobBlocks().contains(block))) {
            return true;
        }
        return EXTERNAL_JOB_BLOCKS_BY_PROFESSION.values().stream()
                .anyMatch(jobBlocks -> jobBlocks.contains(block));
    }

    /** Registers a soft-dependency profession/job-block pair for the shared V1→V2 path. */
    public static void registerExternalJobBlock(net.minecraft.util.Identifier professionId, Block jobBlock) {
        EXTERNAL_JOB_BLOCKS_BY_PROFESSION
                .computeIfAbsent(professionId, ignored -> new HashSet<>())
                .add(jobBlock);
    }

    public static Optional<Block> resolveJobBlock(VillagerProfession profession, BlockState currentJobState) {
        if (!currentJobState.isAir()) {
            return Optional.of(currentJobState.getBlock());
        }

        Optional<Block> vanilla = get(profession)
                .flatMap(definition -> definition.expectedJobBlocks().stream().findFirst());
        if (vanilla.isPresent()) {
            return vanilla;
        }
        net.minecraft.util.Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
        return professionId == null
                ? Optional.empty()
                : EXTERNAL_JOB_BLOCKS_BY_PROFESSION.getOrDefault(professionId, Set.of()).stream().findFirst();
    }

    public static void runConversionHooks(ServerWorld world) {
        for (ProfessionDefinition definition : DEFINITIONS) {
            Consumer<ServerWorld> conversionHook = definition.conversionHook();
            if (conversionHook != null) {
                conversionHook.accept(world);
            }
        }
        runUnemployedConversionHooks(world);
    }

    public static void runUnemployedConversionHooks(ServerWorld world) {
        for (Consumer<ServerWorld> conversionHook : UNEMPLOYED_CONVERSION_HOOKS) {
            conversionHook.accept(world);
        }
    }

    public static void markFallbackCandidates(ServerWorld world) {
        int chunkRadius = 8;
        for (PlayerEntity player : world.getPlayers()) {
            ChunkPos center = player.getChunkPos();
            for (int chunkX = center.x - chunkRadius; chunkX <= center.x + chunkRadius; chunkX++) {
                for (int chunkZ = center.z - chunkRadius; chunkZ <= center.z + chunkRadius; chunkZ++) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    VillagerConversionCandidateIndex.markCandidatesInChunk(world, chunkX, chunkZ);
                }
            }
        }
    }

    private static ProfessionDefinition definition(VillagerProfession profession, Set<Block> expectedJobBlocks, java.util.function.Supplier<VillagerProfessionBehavior> behaviorFactory) {
        return definition(profession, expectedJobBlocks, behaviorFactory, null);
    }

    private static ProfessionDefinition definition(VillagerProfession profession, Set<Block> expectedJobBlocks, java.util.function.Supplier<VillagerProfessionBehavior> behaviorFactory, Consumer<ServerWorld> conversionHook) {
        return new ProfessionDefinition(
                Registries.VILLAGER_PROFESSION.getId(profession),
                profession,
                expectedJobBlocks,
                behaviorFactory,
                conversionHook,
                List.of()
        );
    }
}
