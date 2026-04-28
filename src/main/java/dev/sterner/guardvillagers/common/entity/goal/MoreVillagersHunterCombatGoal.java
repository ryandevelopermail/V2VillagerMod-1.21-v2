package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

public class MoreVillagersHunterCombatGoal extends Goal {
    private static final Identifier HUNTER_PROFESSION_ID = Identifier.of("morevillagers", "hunter");
    private static final double TARGET_SCAN_RANGE = 32.0D;
    private static final double LOOT_SCAN_RANGE = 8.0D;
    private static final double CHEST_REACH_SQUARED = 4.0D;
    private static final double ATTACK_REACH_SQUARED = 5.0D;
    private static final double MOVE_SPEED = 0.7D;
    private static final int TARGET_SCAN_INTERVAL_TICKS = 40;
    private static final int LOOT_SCAN_INTERVAL_TICKS = 20;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    private static final Set<net.minecraft.item.Item> HUNTER_DROPS = Set.of(
            Items.STRING,
            Items.BONE,
            Items.GUNPOWDER,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.ARROW,
            Items.ENDER_PEARL,
            Items.SLIME_BALL,
            Items.PHANTOM_MEMBRANE,
            Items.BLAZE_ROD,
            Items.BLAZE_POWDER,
            Items.MAGMA_CREAM
    );

    private final VillagerEntity villager;
    private BlockPos jobPos;
    private BlockPos chestPos;
    private LivingEntity target;
    private long nextTargetScanTick;
    private int attackCooldown;

    public MoreVillagersHunterCombatGoal(VillagerEntity villager, BlockPos jobPos, BlockPos chestPos) {
        this.villager = villager;
        setTargets(jobPos, chestPos);
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public void setTargets(BlockPos jobPos, BlockPos chestPos) {
        this.jobPos = jobPos.toImmutable();
        this.chestPos = chestPos.toImmutable();
        this.target = null;
    }

    public void requestImmediateCheck() {
        nextTargetScanTick = 0L;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world) || !villager.isAlive() || !isHunter(villager)) {
            return false;
        }
        wakeHunter();
        if (tryEquipWeaponFromChest(world)) {
            return true;
        }
        if (hasHunterDrop(villager.getInventory())) {
            return true;
        }
        if (world.getTime() < nextTargetScanTick || !hasWeapon(villager.getMainHandStack())) {
            return false;
        }
        target = findNearestHostile(world);
        nextTargetScanTick = world.getTime() + TARGET_SCAN_INTERVAL_TICKS;
        return target != null;
    }

    @Override
    public boolean shouldContinue() {
        return villager.isAlive()
                && isHunter(villager)
                && (hasHunterDrop(villager.getInventory()) || isValidTarget(target) || canEquipWeaponFromChest());
    }

    @Override
    public void start() {
        wakeHunter();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.setTarget(null);
        target = null;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return;
        }

        wakeHunter();
        if (tryEquipWeaponFromChest(world)) {
            return;
        }

        if (villager.age % LOOT_SCAN_INTERVAL_TICKS == 0) {
            collectNearbyDrops(world);
        }

        if (hasHunterDrop(villager.getInventory())) {
            depositDrops(world);
            return;
        }

        if (!hasWeapon(villager.getMainHandStack())) {
            target = null;
            return;
        }

        if (!isValidTarget(target)) {
            target = findNearestHostile(world);
            if (target == null) {
                return;
            }
        }

        villager.setTarget(target);
        villager.getLookControl().lookAt(target, 30.0F, 30.0F);
        if (villager.squaredDistanceTo(target) > ATTACK_REACH_SQUARED) {
            villager.getNavigation().startMovingTo(target, MOVE_SPEED);
            return;
        }

        villager.getNavigation().stop();
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }

        villager.tryAttack(target);
        attackCooldown = ATTACK_COOLDOWN_TICKS;
        if (!target.isAlive()) {
            collectDropsNear(world, target.getBoundingBox().expand(2.0D));
            target = null;
        }
    }

    private void depositDrops(ServerWorld world) {
        Inventory chestInventory = getChestInventory(world);
        if (chestInventory == null) {
            return;
        }

        if (villager.squaredDistanceTo(chestPos.getX() + 0.5D, chestPos.getY() + 0.5D, chestPos.getZ() + 0.5D) > CHEST_REACH_SQUARED) {
            villager.getNavigation().startMovingTo(chestPos.getX() + 0.5D, chestPos.getY() + 0.5D, chestPos.getZ() + 0.5D, MOVE_SPEED);
            return;
        }

        Inventory inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isHunterDrop(stack)) {
                continue;
            }
            ItemStack remaining = insertStack(chestInventory, stack);
            inventory.setStack(slot, remaining);
        }
        inventory.markDirty();
        chestInventory.markDirty();
    }

    private void collectNearbyDrops(ServerWorld world) {
        collectDropsNear(world, villager.getBoundingBox().expand(LOOT_SCAN_RANGE));
    }

    private void collectDropsNear(ServerWorld world, Box box) {
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, box, entity -> entity.isAlive() && isHunterDrop(entity.getStack()))) {
            ItemStack remaining = insertStack(villager.getInventory(), itemEntity.getStack());
            villager.getInventory().markDirty();
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
    }

    private LivingEntity findNearestHostile(ServerWorld world) {
        Box searchBox = new Box(villager.getBlockPos()).expand(TARGET_SCAN_RANGE);
        return world.getEntitiesByClass(HostileEntity.class, searchBox, Entity::isAlive).stream()
                .min(Comparator.comparingDouble(villager::squaredDistanceTo))
                .orElse(null);
    }

    private boolean tryEquipWeaponFromChest(ServerWorld world) {
        if (hasWeapon(villager.getMainHandStack())) {
            return false;
        }
        Inventory inventory = getChestInventory(world);
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!hasWeapon(stack)) {
                continue;
            }
            ItemStack weapon = stack.split(1);
            inventory.setStack(slot, stack);
            inventory.markDirty();
            villager.equipStack(EquipmentSlot.MAINHAND, weapon);
            return true;
        }
        return false;
    }

    private boolean canEquipWeaponFromChest() {
        if (!(villager.getWorld() instanceof ServerWorld world) || hasWeapon(villager.getMainHandStack())) {
            return false;
        }
        Inventory inventory = getChestInventory(world);
        return inventory != null && hasWeapon(inventory);
    }

    private Inventory getChestInventory(ServerWorld world) {
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, chestPos, false);
    }

    private static ItemStack insertStack(Inventory inventory, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isEmpty()) {
                if (!inventory.isValid(slot, remaining)) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
                ItemStack inserted = remaining.copy();
                inserted.setCount(moved);
                inventory.setStack(slot, inserted);
                remaining.decrement(moved);
                continue;
            }
            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining) || !inventory.isValid(slot, remaining)) {
                continue;
            }
            int moved = Math.min(existing.getMaxCount() - existing.getCount(), remaining.getCount());
            if (moved > 0) {
                existing.increment(moved);
                remaining.decrement(moved);
            }
        }
        return remaining;
    }

    private static boolean hasHunterDrop(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isHunterDrop(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWeapon(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (hasWeapon(inventory.getStack(slot))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    public static boolean isHunterDrop(ItemStack stack) {
        return !stack.isEmpty() && HUNTER_DROPS.contains(stack.getItem());
    }

    private static boolean isValidTarget(LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved();
    }

    private void wakeHunter() {
        if (villager.isSleeping()) {
            villager.wakeUp();
        }
    }

    private static boolean isHunter(VillagerEntity villager) {
        return HUNTER_PROFESSION_ID.equals(Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()));
    }
}
