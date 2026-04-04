package dev.sterner.guardvillagers.common.util;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.villager.behavior.CartographerBehavior;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class DistributionRecipientHelper {
    private static final Comparator<RecipientRecord> RECIPIENT_ORDER =
            Comparator.comparingDouble(RecipientRecord::sourceSquaredDistance)
                    .thenComparing(recipient -> recipient.recipient().getUuid(), java.util.UUID::compareTo);

    private DistributionRecipientHelper() {
    }

    public static List<RecipientRecord> findEligibleMasonRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.MASON, Blocks.STONECUTTER);
    }

    public static List<RecipientRecord> findEligibleFarmerRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.FARMER, Blocks.COMPOSTER);
    }

    public static List<RecipientRecord> findEligibleCartographerRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE);
    }

    public static List<RecipientRecord> findEligibleV2CartographerRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleRecipients(
                world,
                source,
                range,
                VillagerProfession.CARTOGRAPHER,
                Blocks.CARTOGRAPHY_TABLE,
                recipient -> isEligibleV2CartographerRecipient(world, recipient));
    }

    public static List<RecipientRecord> findEligibleLibrarianRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.LIBRARIAN, Blocks.LECTERN);
    }

    public static List<RecipientRecord> findEligibleLibrarianRecipientsForClerics(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.LIBRARIAN, Blocks.LECTERN);
    }

    public static List<RecipientRecord> findEligibleShepherdRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.SHEPHERD, Blocks.LOOM);
    }

    public static List<RecipientRecord> findEligibleFishermanRecipients(ServerWorld world, VillagerEntity source, double range) {
        if (range <= 0.0D || !source.isAlive()) {
            return List.of();
        }

        double shortRange = resolveShortRange(range);
        List<RecipientRecord> recipients = collectEligibleFishermanRecipients(world, source, shortRange);
        if (recipients.isEmpty()) {
            double wideRange = resolveWideRange(shortRange);
            if (wideRange > shortRange) {
                recipients = collectEligibleFishermanRecipients(world, source, wideRange);
            }
        }
        return recipients;
    }

    public static List<RecipientRecord> findEligibleButcherRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.BUTCHER, Blocks.SMOKER);
    }

    public static List<RecipientRecord> findEligibleLeatherworkerRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.LEATHERWORKER, Blocks.CAULDRON);
    }

    public static List<RecipientRecord> findEligibleRecipients(
            ServerWorld world,
            VillagerEntity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock
    ) {
        return findEligibleRecipients(world, source, range, profession, expectedJobBlock, recipient -> true);
    }

    public static List<RecipientRecord> findEligibleRecipients(
            ServerWorld world,
            Entity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock
    ) {
        return findEligibleRecipients(world, source, range, profession, expectedJobBlock, recipient -> true);
    }

    public static List<RecipientRecord> findEligibleRecipients(
            ServerWorld world,
            VillagerEntity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock,
            Predicate<RecipientRecord> filter
    ) {
        List<RecipientRecord> recipients = findEligibleVillagerRecipients(world, source, range, profession, expectedJobBlock);
        if (recipients.isEmpty()) {
            return recipients;
        }
        return recipients.stream().filter(filter).sorted(RECIPIENT_ORDER).toList();
    }

    public static List<RecipientRecord> findEligibleRecipients(
            ServerWorld world,
            Entity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock,
            Predicate<RecipientRecord> filter
    ) {
        List<RecipientRecord> recipients = findEligibleVillagerRecipients(world, source, range, profession, expectedJobBlock);
        if (recipients.isEmpty()) {
            return recipients;
        }
        return recipients.stream().filter(filter).sorted(RECIPIENT_ORDER).toList();
    }

    private static List<RecipientRecord> findEligibleVillagerRecipients(
            ServerWorld world,
            VillagerEntity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock
    ) {
        if (range <= 0.0D || !source.isAlive()) {
            return List.of();
        }

        double shortRange = resolveShortRange(range);
        List<RecipientRecord> recipients = collectEligibleVillagerRecipients(world, source, shortRange, profession, expectedJobBlock);
        if (recipients.isEmpty()) {
            double wideRange = resolveWideRange(shortRange);
            if (wideRange > shortRange) {
                recipients = collectEligibleVillagerRecipients(world, source, wideRange, profession, expectedJobBlock);
            }
        }
        return recipients;
    }

    private static List<RecipientRecord> findEligibleVillagerRecipients(
            ServerWorld world,
            Entity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock
    ) {
        if (range <= 0.0D || !source.isAlive()) {
            return List.of();
        }

        double shortRange = resolveShortRange(range);
        List<RecipientRecord> recipients = collectEligibleVillagerRecipients(world, source, shortRange, profession, expectedJobBlock);
        if (recipients.isEmpty()) {
            double wideRange = resolveWideRange(shortRange);
            if (wideRange > shortRange) {
                recipients = collectEligibleVillagerRecipients(world, source, wideRange, profession, expectedJobBlock);
            }
        }
        return recipients;
    }

    private static List<RecipientRecord> collectEligibleVillagerRecipients(
            ServerWorld world,
            Entity source,
            double range,
            VillagerProfession profession,
            Block expectedJobBlock
    ) {
        List<RecipientRecord> recipients = new ArrayList<>();
        Box scanBox = new Box(source.getBlockPos()).expand(range);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox, candidate -> candidate != source && isEmployed(candidate) && candidate.getVillagerData().getProfession() == profession)) {
            Optional<RecipientRecord> recipient = validateRecipient(world, source, villager, expectedJobBlock);
            recipient.ifPresent(recipients::add);
        }
        recipients.sort(RECIPIENT_ORDER);
        return recipients;
    }

    private static List<RecipientRecord> collectEligibleFishermanRecipients(ServerWorld world, VillagerEntity source, double range) {
        List<RecipientRecord> recipients = new ArrayList<>();
        Box scanBox = new Box(source.getBlockPos()).expand(range);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox, candidate -> candidate != source && isEmployed(candidate) && candidate.getVillagerData().getProfession() == VillagerProfession.FISHERMAN)) {
            if (!isSameVillageOrUnknown(source, villager)) {
                continue;
            }
            Optional<GlobalPos> jobSiteMemory = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
            if (jobSiteMemory.isEmpty()) {
                continue;
            }

            GlobalPos globalPos = jobSiteMemory.get();
            if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
                continue;
            }

            BlockPos jobPos = globalPos.pos();
            if (!world.getBlockState(jobPos).isOf(Blocks.BARREL)) {
                continue;
            }

            double squaredDistance = source.squaredDistanceTo(villager);
            recipients.add(new RecipientRecord(villager, jobPos.toImmutable(), jobPos.toImmutable(), squaredDistance));
            // Exclude jobPos so the fisherman's barrel job block does not self-match as its own chest.
            JobBlockPairingHelper.findNearbyChest(world, jobPos, jobPos)
                    .ifPresent(chestPos -> recipients.add(new RecipientRecord(villager, jobPos.toImmutable(), chestPos.toImmutable(), squaredDistance)));
        }

        recipients.sort(RECIPIENT_ORDER);
        return recipients;
    }

    private static Optional<RecipientRecord> validateRecipient(ServerWorld world, VillagerEntity source, VillagerEntity recipient, Block expectedJobBlock) {
        return validateRecipient(world, (Entity) source, recipient, expectedJobBlock);
    }

    private static Optional<RecipientRecord> validateRecipient(ServerWorld world, Entity source, VillagerEntity recipient, Block expectedJobBlock) {
        if (source instanceof VillagerEntity sourceVillager && !isSameVillageOrUnknown(sourceVillager, recipient)) {
            return Optional.empty();
        }
        Optional<GlobalPos> jobSiteMemory = recipient.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (jobSiteMemory.isEmpty()) {
            return Optional.empty();
        }

        GlobalPos globalPos = jobSiteMemory.get();
        if (!Objects.equals(globalPos.dimension(), world.getRegistryKey())) {
            return Optional.empty();
        }

        BlockPos jobPos = globalPos.pos();
        BlockState jobState = world.getBlockState(jobPos);
        if (!jobState.isOf(expectedJobBlock)) {
            return Optional.empty();
        }

        Optional<BlockPos> nearbyChest = JobBlockPairingHelper.findNearbyChest(world, jobPos);
        if (nearbyChest.isEmpty()) {
            return Optional.empty();
        }

        double squaredDistance = source.squaredDistanceTo(recipient);
        return Optional.of(new RecipientRecord(recipient, jobPos.toImmutable(), nearbyChest.get().toImmutable(), squaredDistance));
    }

    private static boolean isEmployed(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return villager.isAlive()
                && !villager.isBaby()
                && profession != VillagerProfession.NONE
                && profession != VillagerProfession.NITWIT;
    }

    private static boolean isEligibleV2CartographerRecipient(ServerWorld world, RecipientRecord recipient) {
        if (!JobBlockPairingHelper.findNearbyChest(world, recipient.jobPos())
                .map(chest -> chest.equals(recipient.chestPos()))
                .orElse(false)) {
            return false;
        }

        boolean hasActivePairing = CartographerBehavior.getNearbyPairings(world, recipient.jobPos(), 0).stream()
                .anyMatch(pairing -> pairing.jobPos().equals(recipient.jobPos()) && pairing.chestPos().equals(recipient.chestPos()));
        if (!hasActivePairing) {
            return false;
        }

        Optional<BlockPos> craftingTable = JobBlockPairingHelper.findNearbyCraftingTable(world, recipient.jobPos());
        if (craftingTable.isEmpty()) {
            return false;
        }
        return JobBlockPairingHelper.findNearbyChest(world, craftingTable.get(), recipient.jobPos())
                .map(chest -> chest.equals(recipient.chestPos()))
                .orElse(false);
    }

    private static double resolveShortRange(double requestedRange) {
        return Math.max(requestedRange, GuardVillagersConfig.professionalRecipientScanRange);
    }

    private static double resolveWideRange(double shortRange) {
        return Math.max(shortRange, GuardVillagersConfig.professionalRecipientWideScanRange);
    }

    private static boolean isSameVillageOrUnknown(VillagerEntity source, VillagerEntity recipient) {
        GlobalPos sourceBell = VillageMembershipTracker.getHomeBell(source);
        GlobalPos recipientBell = VillageMembershipTracker.getHomeBell(recipient);
        if (sourceBell == null || recipientBell == null) {
            return true;
        }
        return sourceBell.equals(recipientBell);
    }

    public record RecipientRecord(VillagerEntity recipient, BlockPos jobPos, BlockPos chestPos, double sourceSquaredDistance) {
    }
}
