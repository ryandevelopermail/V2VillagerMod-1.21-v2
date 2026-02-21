package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandAssignment;
import dev.sterner.guardvillagers.common.util.VillageGuardStandManager.GuardStandPairingReport;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class VillagerBellTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagerBellTracker.class);
    private static final int BELL_TRACKING_RANGE = VillageGuardStandManager.BELL_EFFECT_RANGE;
    private static final long JOB_REPORT_DURATION_TICKS = 20L * 30L;
    private static final float JOB_REPORT_SPEED = 0.7F;
    private static final int JOB_REPORT_COMPLETION_RANGE = 1;
    private static final int JOB_HIGHLIGHT_PARTICLE_COUNT = 12;
    private static final double JOB_REPORT_HOLD_DISTANCE_SQUARED = 2.25D;
    private static final int GENERATED_BELL_CHEST_RADIUS = 2;
    private static final int BOOK_PAGE_CHAR_LIMIT = 255;
    private static final int BOOK_PAGE_COUNT_LIMIT = 100;
    private static final String BOOK_AUTHOR = "Guard Villagers";
    private static final Map<UUID, ReportAssignment> REPORTING_VILLAGERS = new HashMap<>();
    private static final Map<GlobalPos, Long> LAST_BOOK_WRITE_TICK = new HashMap<>();

    private VillagerBellTracker() {
    }

    public static void logBellVillagerStats(ServerWorld world, BlockPos bellPos) {
        VillageGuardStandManager.refreshBellInventory(world, bellPos);
        BellVillageReport report = buildBellVillageReport(world, bellPos);
        for (String line : report.orderedLines()) {
            LOGGER.info(line);
        }

        GuardStandPairingReport pairingReport = VillageGuardStandManager.pairGuardsWithStands(world, bellPos);
        logPairings(pairingReport);
        resetGuardsToWander(world, bellPos);
    }

    public static void writeBellReportBooks(ServerWorld world, BlockPos bellPos) {
        GlobalPos globalBellPos = GlobalPos.create(world.getRegistryKey(), bellPos);
        long currentTick = world.getTime();
        Long lastWriteTick = LAST_BOOK_WRITE_TICK.get(globalBellPos);
        if (lastWriteTick != null && lastWriteTick == currentTick) {
            return;
        }

        LAST_BOOK_WRITE_TICK.put(globalBellPos, currentTick);

        BellVillageReport report = buildBellVillageReport(world, bellPos);
        Optional<BlockPos> chestPos = findBellReportChest(world, bellPos);
        if (chestPos.isEmpty()) {
            LOGGER.warn("No chest found for bell report books near bell {}", bellPos.toShortString());
            return;
        }

        Optional<Inventory> chestInventory = getChestInventory(world, chestPos.get());
        if (chestInventory.isEmpty()) {
            LOGGER.warn("Failed to access chest inventory at {} for bell {}", chestPos.get().toShortString(), bellPos.toShortString());
            return;
        }

        ItemStack firstBook = createWrittenBook("Village Bell Report I", report.orderedLines());
        ItemStack secondBook = createWrittenBook("Village Bell Report II", report.orderedLines());
        storeBookInChestOrDrop(world, chestPos.get(), chestInventory.get(), firstBook);
        storeBookInChestOrDrop(world, chestPos.get(), chestInventory.get(), secondBook);
    }

    private static BellVillageReport buildBellVillageReport(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        int villagersWithBeds = 0;
        int villagersWithJobs = 0;
        int villagersWithoutJobs = 0;

        Map<VillagerProfession, Integer> professionCounts = new HashMap<>();
        Map<VillagerProfession, Integer> professionWithChests = new HashMap<>();
        Map<VillagerProfession, Integer> professionWithCraftingTables = new HashMap<>();

        for (VillagerEntity villager : villagers) {
            boolean hasBed = villager.getBrain().getOptionalMemory(MemoryModuleType.HOME).isPresent();
            boolean isEmployed = isEmployedVillager(villager);
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);

            if (hasBed) {
                villagersWithBeds++;
            }

            if (isEmployed) {
                villagersWithJobs++;
                VillagerProfession profession = villager.getVillagerData().getProfession();
                incrementCount(professionCounts, profession);

                if (jobSite.isPresent() && Objects.equals(jobSite.get().dimension(), world.getRegistryKey())) {
                    BlockPos jobPos = jobSite.get().pos();
                    if (hasPairedBlock(world, jobPos, JobBlockPairingHelper::isPairingBlock)) {
                        incrementCount(professionWithChests, profession);
                    }
                    if (hasPairedBlock(world, jobPos, state -> state.isOf(Blocks.CRAFTING_TABLE))) {
                        incrementCount(professionWithCraftingTables, profession);
                    }
                }
            } else {
                villagersWithoutJobs++;
            }
        }

        int ironGolems = world.getEntitiesByClass(IronGolemEntity.class, searchBox, Entity::isAlive).size();
        int guardVillagers = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive).size();
        int totalVillagers = villagers.size() + guardVillagers;
        int armorStands = VillageGuardStandManager.getGuardArmorStands(world, bellPos).size();

        int totalPairedChests = professionWithChests.values().stream().mapToInt(Integer::intValue).sum();
        int totalPairedCraftingTables = professionWithCraftingTables.values().stream().mapToInt(Integer::intValue).sum();
        int totalEmployed = villagersWithJobs + guardVillagers;

        List<String> orderedLines = new java.util.ArrayList<>();
        orderedLines.add("Bell at [" + bellPos.toShortString() + "] triggered villager summary (" + BELL_TRACKING_RANGE + " block radius)");
        orderedLines.add("Golems: " + ironGolems);
        orderedLines.add("Total villagers: " + totalVillagers);
        orderedLines.add("     Guards: " + guardVillagers);
        orderedLines.add("     Armor Stands: " + armorStands);
        orderedLines.add("     Employed: " + totalEmployed);
        orderedLines.add("     Unemployed: " + villagersWithoutJobs);
        orderedLines.add("Beds: " + villagersWithBeds);
        orderedLines.add("Workstations: " + villagersWithJobs);
        orderedLines.add("     Workstations by profession:");
        professionCounts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> Registries.VILLAGER_PROFESSION.getId(entry.getKey()).toString()))
                .forEach(entry -> orderedLines.add("     " + Registries.VILLAGER_PROFESSION.getId(entry.getKey()) + " - " + entry.getValue()));
        orderedLines.add("Paired Chests: " + totalPairedChests);
        orderedLines.add("Paired Crafting Tables: " + totalPairedCraftingTables);

        return new BellVillageReport(orderedLines);
    }

    public static void directEmployedVillagersAndGuardsToStations(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var villagers = world.getEntitiesByClass(VillagerEntity.class, searchBox, Entity::isAlive);

        for (VillagerEntity villager : villagers) {
            if (!isEmployedVillager(villager)) {
                continue;
            }

            highlightEmployedVillager(world, villager);
            Optional<GlobalPos> jobSite = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isPresent() && Objects.equals(jobSite.get().dimension(), world.getRegistryKey())) {
                BlockPos jobPos = jobSite.get().pos();
                highlightJobSite(world, jobPos);
                startVillagerReport(world, villager, jobPos);
            }
        }

        var guards = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
        for (GuardEntity guard : guards) {
            Optional<BlockPos> standPos = getGuardReportPosition(world, guard);
            standPos.ifPresent(pos -> highlightGuardStand(world, pos));
            BlockPos targetPos = standPos.orElse(bellPos);
            guard.setTarget(null);
            guard.setHornTarget(targetPos, JOB_REPORT_DURATION_TICKS);
        }
    }

    public static void resetGuardsToWander(ServerWorld world, BlockPos bellPos) {
        Box searchBox = new Box(bellPos).expand(BELL_TRACKING_RANGE);
        var guards = world.getEntitiesByClass(GuardEntity.class, searchBox, Entity::isAlive);
        for (GuardEntity guard : guards) {
            guard.clearHornTarget();
            guard.getNavigation().stop();
        }
    }

    public static void tickVillagerReports(ServerWorld world) {
        if (REPORTING_VILLAGERS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, ReportAssignment>> iterator = REPORTING_VILLAGERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ReportAssignment> entry = iterator.next();
            ReportAssignment assignment = entry.getValue();
            if (!assignment.jobSite.dimension().equals(world.getRegistryKey())) {
                continue;
            }
            if (assignment.endTime <= world.getTime()) {
                iterator.remove();
                continue;
            }

            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()) {
                iterator.remove();
                continue;
            }

            BlockPos jobPos = assignment.jobSite.pos();
            forceVillagerToReport(villager, jobPos, assignment.endTime - world.getTime());
        }
    }

    private static void startVillagerReport(ServerWorld world, VillagerEntity villager, BlockPos jobPos) {
        long endTime = world.getTime() + JOB_REPORT_DURATION_TICKS;
        REPORTING_VILLAGERS.put(villager.getUuid(), new ReportAssignment(GlobalPos.create(world.getRegistryKey(), jobPos), endTime));
        forceVillagerToReport(villager, jobPos, JOB_REPORT_DURATION_TICKS);
    }

    private static void forceVillagerToReport(VillagerEntity villager, BlockPos jobPos, long remainingTicks) {
        var brain = villager.getBrain();
        brain.forget(MemoryModuleType.HURT_BY);
        brain.forget(MemoryModuleType.HURT_BY_ENTITY);
        brain.forget(MemoryModuleType.NEAREST_HOSTILE);
        brain.forget(MemoryModuleType.AVOID_TARGET);
        brain.forget(MemoryModuleType.ATTACK_TARGET);
        brain.remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(jobPos), remainingTicks);
        brain.remember(MemoryModuleType.WALK_TARGET, new WalkTarget(jobPos, JOB_REPORT_SPEED, JOB_REPORT_COMPLETION_RANGE), remainingTicks);

        Vec3d jobCenter = Vec3d.ofCenter(jobPos);
        double distance = villager.squaredDistanceTo(jobCenter);
        if (distance <= JOB_REPORT_HOLD_DISTANCE_SQUARED) {
            villager.getNavigation().stop();
            villager.getMoveControl().strafeTo(0.0F, 0.0F);
            villager.getLookControl().lookAt(jobCenter.getX(), jobCenter.getY(), jobCenter.getZ());
        } else {
            villager.getNavigation().startMovingTo(jobCenter.getX(), jobCenter.getY(), jobCenter.getZ(), JOB_REPORT_SPEED);
        }
    }

    private static Optional<BlockPos> getGuardReportPosition(ServerWorld world, GuardEntity guard) {
        if (guard.getPairedStandUuid() == null) {
            return Optional.empty();
        }

        Entity standEntity = world.getEntity(guard.getPairedStandUuid());
        if (standEntity instanceof ArmorStandEntity armorStand
                && armorStand.isAlive()
                && armorStand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
            return Optional.of(armorStand.getBlockPos());
        }

        return Optional.empty();
    }

    private static void highlightEmployedVillager(ServerWorld world, VillagerEntity villager) {
        villager.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, (int) JOB_REPORT_DURATION_TICKS, 0, false, false, true));
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getBodyY(0.5D), villager.getZ(), JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.5D, 0.35D, 0.0D);
    }

    private static void highlightJobSite(ServerWorld world, BlockPos jobPos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, jobPos.getX() + 0.5D, jobPos.getY() + 1.0D, jobPos.getZ() + 0.5D, JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.35D, 0.35D, 0.0D);
    }

    private static void highlightGuardStand(ServerWorld world, BlockPos standPos) {
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, standPos.getX() + 0.5D, standPos.getY() + 1.0D, standPos.getZ() + 0.5D, JOB_HIGHLIGHT_PARTICLE_COUNT, 0.35D, 0.35D, 0.35D, 0.0D);
        for (ArmorStandEntity stand : world.getEntitiesByClass(ArmorStandEntity.class, new Box(standPos).expand(0.5D), Entity::isAlive)) {
            if (stand.getBlockPos().equals(standPos) && stand.getCommandTags().contains(VillageGuardStandManager.GUARD_STAND_TAG)) {
                stand.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, (int) JOB_REPORT_DURATION_TICKS, 0, false, false, true));
            }
        }
    }

    private static boolean isEmployedVillager(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT && !villager.isBaby();
    }

    private record ReportAssignment(GlobalPos jobSite, long endTime) {
    }

    private record BellVillageReport(List<String> orderedLines) {
    }

    private static boolean hasPairedBlock(ServerWorld world, BlockPos jobPos, Predicate<BlockState> predicate) {
        int range = (int) Math.ceil(JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE);
        for (BlockPos checkPos : BlockPos.iterate(jobPos.add(-range, -range, -range), jobPos.add(range, range, range))) {
            if (jobPos.isWithinDistance(checkPos, JobBlockPairingHelper.JOB_BLOCK_PAIRING_RANGE) && predicate.test(world.getBlockState(checkPos))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BlockPos> findBellReportChest(ServerWorld world, BlockPos bellPos) {
        Optional<BlockPos> generatedChest = findNearestChestWithin(world, bellPos, GENERATED_BELL_CHEST_RADIUS);
        if (generatedChest.isPresent()) {
            return generatedChest;
        }

        if (GuardVillagersConfig.bellReportChestFallbackRadius <= 0) {
            return Optional.empty();
        }

        return findNearestChestWithin(world, bellPos, GuardVillagersConfig.bellReportChestFallbackRadius);
    }

    private static Optional<BlockPos> findNearestChestWithin(ServerWorld world, BlockPos center, int radius) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos checkPos : BlockPos.iterate(center.add(-radius, -1, -radius), center.add(radius, 1, radius))) {
            if (!center.isWithinDistance(checkPos, radius)) {
                continue;
            }
            if (!world.getBlockState(checkPos).isOf(Blocks.CHEST)) {
                continue;
            }

            double distance = checkPos.getSquaredDistance(center);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = checkPos.toImmutable();
            }
        }

        return Optional.ofNullable(nearest);
    }

    private static Optional<Inventory> getChestInventory(ServerWorld world, BlockPos chestPos) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ChestBlock.getInventory(chestBlock, state, world, chestPos, true));
    }

    private static ItemStack createWrittenBook(String title, List<String> reportLines) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putString("title", title);
        nbt.putString("author", BOOK_AUTHOR);

        NbtList pages = new NbtList();
        for (String page : paginateLines(reportLines)) {
            pages.add(NbtString.of(page));
        }
        nbt.put("pages", pages);
        nbt.putBoolean("resolved", true);
        return stack;
    }

    private static List<String> paginateLines(List<String> lines) {
        List<String> pages = new java.util.ArrayList<>();
        StringBuilder pageBuilder = new StringBuilder();
        for (String line : lines) {
            String pageLine = line + "\n";
            if (pageBuilder.length() + pageLine.length() > BOOK_PAGE_CHAR_LIMIT) {
                if (pages.size() >= BOOK_PAGE_COUNT_LIMIT) {
                    break;
                }
                pages.add(pageBuilder.toString());
                pageBuilder = new StringBuilder();
            }
            pageBuilder.append(pageLine);
        }

        if (pages.size() < BOOK_PAGE_COUNT_LIMIT && pageBuilder.length() > 0) {
            pages.add(pageBuilder.toString());
        }

        if (pages.isEmpty()) {
            pages.add("No report data available.");
        }

        return pages;
    }

    private static void storeBookInChestOrDrop(ServerWorld world, BlockPos chestPos, Inventory inventory, ItemStack book) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                inventory.setStack(slot, book);
                inventory.markDirty();
                return;
            }
        }

        LOGGER.warn("Chest at {} is full; dropping bell report book", chestPos.toShortString());
        BlockPos dropPos = chestPos.up();
        world.spawnEntity(new net.minecraft.entity.ItemEntity(
                world,
                dropPos.getX() + 0.5D,
                dropPos.getY() + 0.25D,
                dropPos.getZ() + 0.5D,
                book.copy()));
    }

    private static void incrementCount(Map<VillagerProfession, Integer> map, VillagerProfession profession) {
        map.merge(profession, 1, Integer::sum);
    }

    private static void logPairings(GuardStandPairingReport pairingReport) {
        if (pairingReport.assignments().isEmpty()) {
            LOGGER.info("No guard to armor stand pairings were created.");
            return;
        }

        LOGGER.info("Guard and armor stand pairings:");
        for (GuardStandAssignment assignment : pairingReport.assignments()) {
            LOGGER.info("Guard: {}", assignment.guardPos().toShortString());
            LOGGER.info("Stand: {}", assignment.standPos().toShortString());
        }

        if (!pairingReport.demotedGuards().isEmpty()) {
            LOGGER.info("Demoted excess guards: {}", pairingReport.demotedGuards().size());
            pairingReport.demotedGuards()
                    .forEach(pos -> LOGGER.info("- {}", pos.toShortString()));
        }
    }
}
