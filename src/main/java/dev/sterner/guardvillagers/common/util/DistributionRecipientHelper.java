package dev.sterner.guardvillagers.common.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

    public static List<RecipientRecord> findEligibleLibrarianRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.LIBRARIAN, Blocks.LECTERN);
    }

    public static List<RecipientRecord> findEligibleLibrarianRecipientsForClerics(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.LIBRARIAN, Blocks.LECTERN);
    }

    public static List<RecipientRecord> findEligibleShepherdRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.SHEPHERD, Blocks.LOOM);
    }

    public static List<RecipientRecord> findEligibleButcherRecipients(ServerWorld world, VillagerEntity source, double range) {
        return findEligibleVillagerRecipients(world, source, range, VillagerProfession.BUTCHER, Blocks.SMOKER);
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

        List<RecipientRecord> recipients = new ArrayList<>();
        Box scanBox = new Box(source.getBlockPos()).expand(range);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, scanBox, candidate -> candidate != source && isEmployed(candidate) && candidate.getVillagerData().getProfession() == profession)) {
            Optional<RecipientRecord> recipient = validateRecipient(world, source, villager, expectedJobBlock);
            recipient.ifPresent(recipients::add);
        }

        recipients.sort(RECIPIENT_ORDER);
        return recipients;
    }

    private static Optional<RecipientRecord> validateRecipient(ServerWorld world, VillagerEntity source, VillagerEntity recipient, Block expectedJobBlock) {
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

    public record RecipientRecord(VillagerEntity recipient, BlockPos jobPos, BlockPos chestPos, double sourceSquaredDistance) {
    }
}
