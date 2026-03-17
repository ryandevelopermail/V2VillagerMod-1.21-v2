package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.goal.ArmorerRepairGuardArmorGoal;
import dev.sterner.guardvillagers.common.entity.goal.FollowShieldGuards;
import dev.sterner.guardvillagers.common.entity.goal.GuardEatFoodGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardInteractDoorGoal;
import dev.sterner.guardvillagers.common.entity.goal.GuardLookAtAndStopMovingWhenBeingTheInteractionTarget;
import dev.sterner.guardvillagers.common.entity.goal.GuardRunToEatGoal;
import dev.sterner.guardvillagers.common.entity.goal.KickGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonGuardChestDistributionGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonGuardStonecuttingGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonMiningStairGoal;
import dev.sterner.guardvillagers.common.entity.goal.MasonWallBuilderGoal;
import dev.sterner.guardvillagers.common.entity.goal.RaiseShieldGoal;
import dev.sterner.guardvillagers.common.entity.goal.RunToClericGoal;
import dev.sterner.guardvillagers.common.entity.goal.WalkBackToCheckPointGoal;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.IronGolemWanderAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.WanderAroundPointOfInterestGoal;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MasonGuardEntity extends GuardEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasonGuardEntity.class);

    private BlockPos pairedChestPos;
    private BlockPos pairedJobPos;
    private ItemStack expectedMiningTool = ItemStack.EMPTY;
    private boolean loggedSpawnValidation;
    private long nextMiningStartTick;
    private BlockPos miningOrigin;
    private BlockPos miningStartPos;
    private BlockPos miningLastMinedPos;
    private int miningStepIndex;
    private int miningDirectionId = -1;
    private boolean miningSessionActive;

    // Cluster 4 — Wall builder state
    /** The primary bell this mason is associated with for wall-building decisions. */
    private GlobalPos wallBuilderHomeBell;
    /** Ordered list of remaining wall segment positions to place. Persisted across restarts. */
    private List<BlockPos> wallSegments = new ArrayList<>();
    /** Gate positions reserved per wall face (1 per face, max 4). For lumberjack fence gates. */
    private List<BlockPos> wallGatePositions = new ArrayList<>();

    public MasonGuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
    }

    public void setPairedChestPos(BlockPos chestPos) {
        this.pairedChestPos = chestPos == null ? null : chestPos.toImmutable();
    }

    public void setPairedJobPos(BlockPos jobPos) {
        this.pairedJobPos = jobPos == null ? null : jobPos.toImmutable();
    }

    public void setExpectedMiningTool(ItemStack stack) {
        this.expectedMiningTool = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    public BlockPos getPairedChestPos() {
        return pairedChestPos;
    }

    public BlockPos getPairedJobPos() {
        return pairedJobPos;
    }

    public ItemStack getExpectedMiningTool() {
        return expectedMiningTool;
    }

    public long getNextMiningStartTick() {
        return nextMiningStartTick;
    }

    public void setNextMiningStartTick(long nextMiningStartTick) {
        this.nextMiningStartTick = nextMiningStartTick;
    }

    public BlockPos getMiningOrigin() {
        return miningOrigin;
    }

    public int getMiningStepIndex() {
        return miningStepIndex;
    }

    public int getMiningDirectionId() {
        return miningDirectionId;
    }

    public BlockPos getMiningStartPos() {
        return miningStartPos;
    }

    public BlockPos getMiningLastMinedPos() {
        return miningLastMinedPos;
    }

    public void setMiningProgress(BlockPos origin, int stepIndex, int directionId) {
        this.miningOrigin = origin == null ? null : origin.toImmutable();
        this.miningStepIndex = Math.max(0, stepIndex);
        this.miningDirectionId = directionId;
    }

    public void setMiningPathAnchors(BlockPos startPos, BlockPos lastMinedPos) {
        this.miningStartPos = startPos == null ? null : startPos.toImmutable();
        this.miningLastMinedPos = lastMinedPos == null ? null : lastMinedPos.toImmutable();
    }

    // -------------------------------------------------------------------------
    // Cluster 4 — Wall builder accessors
    // -------------------------------------------------------------------------

    public GlobalPos getWallBuilderHomeBell() {
        return wallBuilderHomeBell;
    }

    public void setWallBuilderHomeBell(GlobalPos bellPos) {
        this.wallBuilderHomeBell = bellPos;
    }

    public List<BlockPos> getWallSegments() {
        return wallSegments;
    }

    public void setWallSegments(List<BlockPos> segments) {
        this.wallSegments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
    }

    public void clearWallSegments() {
        this.wallSegments.clear();
    }

    public List<BlockPos> getWallGatePositions() {
        return wallGatePositions;
    }

    public void setWallGatePositions(List<BlockPos> gates) {
        this.wallGatePositions = gates == null ? new ArrayList<>() : new ArrayList<>(gates);
    }

    public void clearMiningProgress() {
        this.miningOrigin = null;
        this.miningStartPos = null;
        this.miningLastMinedPos = null;
        this.miningStepIndex = 0;
        this.miningDirectionId = -1;
    }

    public boolean isMiningSessionActive() {
        return miningSessionActive;
    }

    public void setMiningSessionActive(boolean miningSessionActive) {
        this.miningSessionActive = miningSessionActive;
    }

    // -------------------------------------------------------------------------
    // NBT helpers
    // -------------------------------------------------------------------------

    private static NbtList writeBlockPosList(List<BlockPos> list) {
        NbtList nbtList = new NbtList();
        for (BlockPos pos : list) {
            NbtCompound entry = new NbtCompound();
            entry.putInt("X", pos.getX());
            entry.putInt("Y", pos.getY());
            entry.putInt("Z", pos.getZ());
            nbtList.add(entry);
        }
        return nbtList;
    }

    private static List<BlockPos> readBlockPosList(NbtCompound nbt, String key) {
        List<BlockPos> result = new ArrayList<>();
        if (!nbt.contains(key, net.minecraft.nbt.NbtElement.LIST_TYPE)) return result;
        NbtList nbtList = nbt.getList(key, net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < nbtList.size(); i++) {
            NbtCompound entry = nbtList.getCompound(i);
            result.add(new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z")));
        }
        return result;
    }

    @Override
    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        return List.of();
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new KickGoal(this));
        this.goalSelector.add(0, new GuardEatFoodGoal(this));
        this.goalSelector.add(0, new RaiseShieldGoal(this));
        this.goalSelector.add(1, new RespondToHornGoal(this, 1.0D));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        this.goalSelector.add(3, new FollowHeroGoal(this));
        if (GuardVillagersConfig.guardEntitysRunFromPolarBears) {
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        }
        this.goalSelector.add(2, new MasonGuardStonecuttingGoal(this));
        this.goalSelector.add(3, new MasonMiningStairGoal(this));
        this.goalSelector.add(3, new MasonGuardChestDistributionGoal(this));
        this.goalSelector.add(4, new MasonWallBuilderGoal(this));
        this.goalSelector.add(3, new WanderAroundPointOfInterestGoal(this, 0.5D, false));
        this.goalSelector.add(3, new IronGolemWanderAroundGoal(this, 0.5D));
        this.goalSelector.add(3, new MoveThroughVillageGoal(this, 0.5D, false, 4, () -> false));
        if (GuardVillagersConfig.guardEntitysOpenDoors) {
            this.goalSelector.add(3, new GuardInteractDoorGoal(this, true));
        }
        if (GuardVillagersConfig.guardEntityFormation) {
            this.goalSelector.add(5, new FollowShieldGuards(this));
        }
        if (GuardVillagersConfig.clericHealing) {
            this.goalSelector.add(6, new RunToClericGoal(this));
        }
        if (GuardVillagersConfig.armorerRepairGuardEntityArmor) {
            this.goalSelector.add(6, new ArmorerRepairGuardArmorGoal(this));
        }
        this.goalSelector.add(4, new WalkBackToCheckPointGoal(this, 0.5D));
        this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.5D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, MerchantEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new GuardLookAtAndStopMovingWhenBeingTheInteractionTarget(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient && !this.loggedSpawnValidation && this.age > 1) {
            this.loggedSpawnValidation = true;
            LOGGER.info("Mason Guard {} spawn validation: selectedChestTool={}, equippedItem={}, pairedChestPos={}, pairedJobPos={}, roleType={}",
                    this.getUuidAsString(),
                    this.expectedMiningTool.isEmpty() ? "empty" : Registries.ITEM.getId(this.expectedMiningTool.getItem()),
                    this.getMainHandStack().isEmpty() ? "empty" : Registries.ITEM.getId(this.getMainHandStack().getItem()),
                    this.pairedChestPos == null ? "none" : this.pairedChestPos.toShortString(),
                    this.pairedJobPos == null ? "none" : this.pairedJobPos.toShortString(),
                    "mason");
        }

        this.setTarget(null);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.loggedSpawnValidation = nbt.getBoolean("MasonLoggedSpawnValidation");
        if (nbt.contains("MasonPairedChestX")) {
            this.pairedChestPos = new BlockPos(nbt.getInt("MasonPairedChestX"), nbt.getInt("MasonPairedChestY"), nbt.getInt("MasonPairedChestZ"));
        } else {
            this.pairedChestPos = null;
        }
        if (nbt.contains("MasonPairedJobX")) {
            this.pairedJobPos = new BlockPos(nbt.getInt("MasonPairedJobX"), nbt.getInt("MasonPairedJobY"), nbt.getInt("MasonPairedJobZ"));
        } else {
            this.pairedJobPos = null;
        }
        if (nbt.contains("MasonExpectedTool")) {
            this.expectedMiningTool = ItemStack.fromNbtOrEmpty(this.getRegistryManager(), nbt.getCompound("MasonExpectedTool"));
        } else {
            this.expectedMiningTool = ItemStack.EMPTY;
        }
        this.nextMiningStartTick = nbt.contains("MasonNextMiningStartTick") ? nbt.getLong("MasonNextMiningStartTick") : 0L;
        this.miningSessionActive = nbt.getBoolean("MasonMiningSessionActive");
        // Cluster 4 — Wall builder state
        if (nbt.contains("MasonWallBellDim") && nbt.contains("MasonWallBellX")) {
            Identifier dimId = Identifier.tryParse(nbt.getString("MasonWallBellDim"));
            if (dimId != null) {
                RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
                BlockPos bellPos = new BlockPos(nbt.getInt("MasonWallBellX"), nbt.getInt("MasonWallBellY"), nbt.getInt("MasonWallBellZ"));
                this.wallBuilderHomeBell = GlobalPos.create(worldKey, bellPos);
            }
        } else {
            this.wallBuilderHomeBell = null;
        }
        this.wallSegments = readBlockPosList(nbt, "MasonWallSegments");
        this.wallGatePositions = readBlockPosList(nbt, "MasonWallGates");

        if (nbt.contains("MasonMiningOriginX")) {
            this.miningOrigin = new BlockPos(nbt.getInt("MasonMiningOriginX"), nbt.getInt("MasonMiningOriginY"), nbt.getInt("MasonMiningOriginZ"));
            this.miningStepIndex = Math.max(0, nbt.getInt("MasonMiningStepIndex"));
            this.miningDirectionId = nbt.contains("MasonMiningDirectionId") ? nbt.getInt("MasonMiningDirectionId") : -1;
            if (nbt.contains("MasonMiningStartX")) {
                this.miningStartPos = new BlockPos(nbt.getInt("MasonMiningStartX"), nbt.getInt("MasonMiningStartY"), nbt.getInt("MasonMiningStartZ"));
            } else {
                this.miningStartPos = this.miningOrigin;
            }
            if (nbt.contains("MasonMiningLastMinedX")) {
                this.miningLastMinedPos = new BlockPos(nbt.getInt("MasonMiningLastMinedX"), nbt.getInt("MasonMiningLastMinedY"), nbt.getInt("MasonMiningLastMinedZ"));
            } else {
                this.miningLastMinedPos = null;
            }
        } else {
            this.clearMiningProgress();
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("MasonLoggedSpawnValidation", this.loggedSpawnValidation);
        if (this.pairedChestPos != null) {
            nbt.putInt("MasonPairedChestX", this.pairedChestPos.getX());
            nbt.putInt("MasonPairedChestY", this.pairedChestPos.getY());
            nbt.putInt("MasonPairedChestZ", this.pairedChestPos.getZ());
        }
        if (this.pairedJobPos != null) {
            nbt.putInt("MasonPairedJobX", this.pairedJobPos.getX());
            nbt.putInt("MasonPairedJobY", this.pairedJobPos.getY());
            nbt.putInt("MasonPairedJobZ", this.pairedJobPos.getZ());
        }
        if (!this.expectedMiningTool.isEmpty()) {
            NbtCompound toolNbt = new NbtCompound();
            nbt.put("MasonExpectedTool", this.expectedMiningTool.encode(this.getRegistryManager(), toolNbt));
        }
        // Cluster 4 — Wall builder state
        if (this.wallBuilderHomeBell != null) {
            nbt.putString("MasonWallBellDim", this.wallBuilderHomeBell.dimension().getValue().toString());
            nbt.putInt("MasonWallBellX", this.wallBuilderHomeBell.pos().getX());
            nbt.putInt("MasonWallBellY", this.wallBuilderHomeBell.pos().getY());
            nbt.putInt("MasonWallBellZ", this.wallBuilderHomeBell.pos().getZ());
        }
        nbt.put("MasonWallSegments", writeBlockPosList(this.wallSegments));
        nbt.put("MasonWallGates", writeBlockPosList(this.wallGatePositions));

        nbt.putLong("MasonNextMiningStartTick", this.nextMiningStartTick);
        nbt.putBoolean("MasonMiningSessionActive", this.miningSessionActive);
        if (this.miningOrigin != null) {
            nbt.putInt("MasonMiningOriginX", this.miningOrigin.getX());
            nbt.putInt("MasonMiningOriginY", this.miningOrigin.getY());
            nbt.putInt("MasonMiningOriginZ", this.miningOrigin.getZ());
            nbt.putInt("MasonMiningStepIndex", this.miningStepIndex);
            nbt.putInt("MasonMiningDirectionId", this.miningDirectionId);
            if (this.miningStartPos != null) {
                nbt.putInt("MasonMiningStartX", this.miningStartPos.getX());
                nbt.putInt("MasonMiningStartY", this.miningStartPos.getY());
                nbt.putInt("MasonMiningStartZ", this.miningStartPos.getZ());
            }
            if (this.miningLastMinedPos != null) {
                nbt.putInt("MasonMiningLastMinedX", this.miningLastMinedPos.getX());
                nbt.putInt("MasonMiningLastMinedY", this.miningLastMinedPos.getY());
                nbt.putInt("MasonMiningLastMinedZ", this.miningLastMinedPos.getZ());
            }
        }
    }
}
