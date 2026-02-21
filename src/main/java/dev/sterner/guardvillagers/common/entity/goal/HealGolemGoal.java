package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.villager.behavior.ArmorerBehavior;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.EnumSet;
import java.util.List;

public class HealGolemGoal extends Goal {
    public final MobEntity healer;
    public IronGolemEntity golem;
    public boolean hasStartedHealing;
    private long lastHealTick = Long.MIN_VALUE;

    public HealGolemGoal(MobEntity mob) {
        healer = mob;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        VillagerEntity villager = (VillagerEntity) this.healer;
        VillagerProfession profession = villager.getVillagerData().getProfession();

        if ((profession != VillagerProfession.WEAPONSMITH && profession != VillagerProfession.TOOLSMITH
                && profession != VillagerProfession.ARMORER) || this.healer.isSleeping()) {
            return false;
        }

        if (profession == VillagerProfession.ARMORER && !armorerHasIron(villager)) {
            return false;
        }

        List<IronGolemEntity> list = this.healer.getWorld().getNonSpectatingEntities(IronGolemEntity.class, this.healer.getBoundingBox().expand(10.0D));
        if (!list.isEmpty()) {
            for (IronGolemEntity golem : list) {
                if (!golem.isInvisible() && golem.isAlive() && golem.getType() == EntityType.IRON_GOLEM) {
                    if (golem.getHealth() <= 60.0D || this.hasStartedHealing && golem.getHealth() < golem.getMaxHealth()) {
                        healer.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
                        this.golem = golem;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void stop() {
        healer.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        this.hasStartedHealing = false;
        super.stop();
    }

    @Override
    public void start() {
        if (golem == null)
            return;
        healer.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
        this.healGolem();
    }

    @Override
    public void tick() {
        if (golem.getHealth() < golem.getMaxHealth()) {
            this.healGolem();
        }
    }

    public void healGolem() {
        healer.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
        healer.getNavigation().startMovingTo(golem, 0.5);
        if (healer.distanceTo(golem) <= 2.0D) {
            long currentTick = healer.getWorld().getTime();
            if (currentTick == lastHealTick) {
                return;
            }

            VillagerEntity villager = (VillagerEntity) healer;
            if (villager.getVillagerData().getProfession() == VillagerProfession.ARMORER && !consumeArmorerIron(villager)) {
                return;
            }

            this.hasStartedHealing = true;
            this.lastHealTick = currentTick;
            healer.swingHand(Hand.MAIN_HAND);
            golem.heal(15.0F);
            float f1 = 1.0F + (golem.getRandom().nextFloat() - golem.getRandom().nextFloat()) * 0.2F;
            golem.playSound(SoundEvents.ENTITY_IRON_GOLEM_REPAIR, 1.0F, f1);
        }
    }

    private boolean armorerHasIron(VillagerEntity villager) {
        Inventory inventory = getArmorerChestInventory(villager);
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(Items.IRON_INGOT) && stack.getCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeArmorerIron(VillagerEntity villager) {
        Inventory inventory = getArmorerChestInventory(villager);
        if (inventory == null) {
            return false;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(Items.IRON_INGOT) || stack.getCount() <= 0) {
                continue;
            }

            stack.decrement(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            inventory.markDirty();
            return true;
        }

        return false;
    }

    private Inventory getArmorerChestInventory(VillagerEntity villager) {
        if (!(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return null;
        }

        BlockPos chestPos = ArmorerBehavior.getPairedChestPos(villager);
        if (chestPos == null) {
            return null;
        }

        BlockState state = serverWorld.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }

        return ChestBlock.getInventory(chestBlock, state, serverWorld, chestPos, true);
    }
}
