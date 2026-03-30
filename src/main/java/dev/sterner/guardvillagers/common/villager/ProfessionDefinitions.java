package dev.sterner.guardvillagers.common.villager;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.util.JobBlockPairingHelper;
import dev.sterner.guardvillagers.common.util.TickWorkGuard;
import dev.sterner.guardvillagers.common.villager.behavior.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ProfessionDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProfessionDefinitions.class);
    private static final int FALLBACK_CHUNK_RADIUS = 8;
    private static final int FALLBACK_CHUNK_SCAN_BUDGET = 24;
    private static final int FALLBACK_CHUNK_ITERATION_BUDGET = 24;
    private static final long FALLBACK_QUEUE_REFRESH_INTERVAL_TICKS = 200L;
    private static final long FALLBACK_CHUNK_COOLDOWN_TICKS = 400L;
    private static final long FALLBACK_SCAN_WARMUP_TICKS = 200L;
    private static final int FALLBACK_MAX_ITERATIONS_PER_TICK = 128;
    private static final long FALLBACK_MAX_ELAPSED_MS_PER_TICK = 4L;

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
    private static final Map<RegistryKey<World>, FallbackScanState> FALLBACK_SCAN_STATE = new HashMap<>();

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

        return get(profession)
                .map(definition -> definition.expectedJobBlocks().contains(blockState.getBlock()))
                .orElse(false);
    }

    public static Optional<Block> resolveJobBlock(VillagerProfession profession, BlockState currentJobState) {
        if (!currentJobState.isAir()) {
            return Optional.of(currentJobState.getBlock());
        }

        return get(profession)
                .flatMap(definition -> definition.expectedJobBlocks().stream().findFirst());
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

    /**
     * Must be bounded per tick to avoid draining the entire fallback queue in one invocation.
     */
    public static void markFallbackCandidates(ServerWorld world) {
        long now = world.getTime();
        FallbackScanState state = stateFor(world);
        if (now - state.lastQueueRefreshTick >= FALLBACK_QUEUE_REFRESH_INTERVAL_TICKS) {
            refreshChunkQueueNearPlayers(world, state);
            state.lastQueueRefreshTick = now;
        }
        if (now < FALLBACK_SCAN_WARMUP_TICKS) {
            // Warmup mode: queue chunk work but defer entity scans until the world has
            // settled for a few seconds after load/join.
            return;
        }

        int remainingChunkIterations = FALLBACK_CHUNK_ITERATION_BUDGET;
        int remainingSuccessfulScans = FALLBACK_CHUNK_SCAN_BUDGET;
        int dequeuedChunks = 0;
        int skippedUnloaded = 0;
        int skippedCooldown = 0;
        int scanned = 0;
        TickWorkGuard guard = new TickWorkGuard(FALLBACK_MAX_ITERATIONS_PER_TICK, FALLBACK_MAX_ELAPSED_MS_PER_TICK);

        while (remainingChunkIterations > 0
                && remainingSuccessfulScans > 0
                && !state.pendingChunks.isEmpty()
                && guard.shouldContinue(dequeuedChunks)) {
            long packedChunkPos = state.pendingChunks.removeFirst();
            state.enqueuedChunks.remove(packedChunkPos);
            remainingChunkIterations--;
            dequeuedChunks++;

            int chunkX = ChunkPos.getPackedX(packedChunkPos);
            int chunkZ = ChunkPos.getPackedZ(packedChunkPos);
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                skippedUnloaded++;
                continue;
            }

            long lastScanTick = state.lastScannedChunkTicks.getOrDefault(packedChunkPos, Long.MIN_VALUE);
            if (now - lastScanTick < FALLBACK_CHUNK_COOLDOWN_TICKS) {
                skippedCooldown++;
                continue;
            }

            VillagerConversionCandidateIndex.markCandidatesInChunk(world, chunkX, chunkZ);
            state.lastScannedChunkTicks.put(packedChunkPos, now);
            remainingSuccessfulScans--;
            scanned++;
        }

        boolean hasLeftover = !state.pendingChunks.isEmpty();
        if (hasLeftover && (guard.hitTimeCap() || guard.hitIterationCap(dequeuedChunks, true))) {
            LOGGER.warn("fallback candidate queue guard tripped: world={} reason={} queueSize={} dequeued={} scanned={} skippedUnloaded={} skippedCooldown={}",
                    world.getRegistryKey().getValue(),
                    guard.hitTimeCap() ? "elapsed-time" : "iteration-cap",
                    state.pendingChunks.size(),
                    dequeuedChunks,
                    scanned,
                    skippedUnloaded,
                    skippedCooldown);
        }

        if (LOGGER.isDebugEnabled() && dequeuedChunks > 0) {
            LOGGER.debug("Fallback candidate scan tick: dequeued={} skippedUnloaded={} skippedCooldown={} scanned={} remainingQueue={}", 
                    dequeuedChunks,
                    skippedUnloaded,
                    skippedCooldown,
                    scanned,
                    state.pendingChunks.size());
        }
    }

    public static void onWorldUnload(ServerWorld world) {
        FALLBACK_SCAN_STATE.remove(world.getRegistryKey());
    }

    private static FallbackScanState stateFor(ServerWorld world) {
        return FALLBACK_SCAN_STATE.computeIfAbsent(world.getRegistryKey(), key -> new FallbackScanState());
    }

    private static void refreshChunkQueueNearPlayers(ServerWorld world, FallbackScanState state) {
        for (PlayerEntity player : world.getPlayers()) {
            ChunkPos center = player.getChunkPos();
            for (int chunkX = center.x - FALLBACK_CHUNK_RADIUS; chunkX <= center.x + FALLBACK_CHUNK_RADIUS; chunkX++) {
                for (int chunkZ = center.z - FALLBACK_CHUNK_RADIUS; chunkZ <= center.z + FALLBACK_CHUNK_RADIUS; chunkZ++) {
                    enqueueChunk(state, chunkX, chunkZ);
                }
            }
        }
    }

    private static void enqueueChunk(FallbackScanState state, int chunkX, int chunkZ) {
        long packedChunkPos = ChunkPos.toLong(chunkX, chunkZ);
        if (state.enqueuedChunks.add(packedChunkPos)) {
            state.pendingChunks.addLast(packedChunkPos);
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

    private static final class FallbackScanState {
        private final Deque<Long> pendingChunks = new ArrayDeque<>();
        private final Set<Long> enqueuedChunks = new HashSet<>();
        private final Map<Long, Long> lastScannedChunkTicks = new HashMap<>();
        private long lastQueueRefreshTick = Long.MIN_VALUE;
    }
}
