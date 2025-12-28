package dev.sterner.guardvillagers.common.entity.goal;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.EnumSet;
import java.util.List;

public class FarmerAnimalFeedingGoal extends Goal {
    private static final double MOVE_SPEED = 0.6D;
    private static final double TARGET_REACH_SQUARED = 4.0D;
    private static final int MIN_FEEDS = 1;
    private static final int MAX_FEEDS = 5;
    private static final double PEN_RANGE = 6.0D;

    private final VillagerEntity villager;
    private BlockPos bannerPos;
    private long lastFeedDay = -1L;
    private int feedsRemaining;
    private Stage stage = Stage.IDLE;

    public FarmerAnimalFeedingGoal(VillagerEntity villager, BlockPos bannerPos) {
        this.villager = villager;
        this.bannerPos = bannerPos.toImmutable();
        setControls(EnumSet.of(Control.MOVE));
    }

    public void setBannerPos(BlockPos bannerPos) {
        this.bannerPos = bannerPos.toImmutable();
        this.stage = Stage.IDLE;
    }

    @Override
    public boolean canStart() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        long day = world.getTimeOfDay() / 24000L;
        if (day == lastFeedDay) {
            return false;
        }
        if (bannerPos == null) {
            return false;
        }
        if (findFeedItem(villager.getInventory()) == null) {
            return false;
        }
        if (getAnimalsInPen(world).isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean shouldContinue() {
        return stage != Stage.DONE && villager.isAlive();
    }

    @Override
    public void start() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }
        long day = world.getTimeOfDay() / 24000L;
        lastFeedDay = day;
        feedsRemaining = MIN_FEEDS + villager.getRandom().nextInt(MAX_FEEDS);
        stage = Stage.GO_TO_PEN;
        moveToBanner();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        stage = Stage.DONE;
    }

    @Override
    public void tick() {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            stage = Stage.DONE;
            return;
        }

        switch (stage) {
            case GO_TO_PEN -> {
                if (isNear(bannerPos)) {
                    stage = Stage.FEED;
                } else {
                    moveToBanner();
                }
            }
            case FEED -> {
                feedAnimals(world);
                stage = Stage.DONE;
            }
            case IDLE, DONE -> {
            }
        }
    }

    private void feedAnimals(ServerWorld world) {
        Inventory inventory = villager.getInventory();
        List<AnimalEntity> animals = getAnimalsInPen(world);
        for (AnimalEntity animal : animals) {
            if (feedsRemaining <= 0) {
                return;
            }
            Item feedItem = findFeedItemForAnimal(inventory, animal);
            if (feedItem == null) {
                return;
            }
            if (!consumeItem(inventory, feedItem)) {
                return;
            }
            animal.lovePlayer(null);
            feedsRemaining--;
        }
    }

    private List<AnimalEntity> getAnimalsInPen(ServerWorld world) {
        Box box = new Box(bannerPos).expand(PEN_RANGE);
        return world.getEntitiesByClass(AnimalEntity.class, box, animal -> animal.isAlive() && !animal.isBaby());
    }

    private Item findFeedItem(Inventory inventory) {
        Item[] candidates = new Item[] {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT};
        for (Item candidate : candidates) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty() && stack.getItem() == candidate) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Item findFeedItemForAnimal(Inventory inventory, AnimalEntity animal) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (animal.isBreedingItem(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private boolean consumeItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
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

    private boolean isNear(BlockPos pos) {
        return villager.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= TARGET_REACH_SQUARED;
    }

    private void moveToBanner() {
        villager.getNavigation().startMovingTo(bannerPos.getX() + 0.5D, bannerPos.getY() + 0.5D, bannerPos.getZ() + 0.5D, MOVE_SPEED);
    }

    private enum Stage {
        IDLE,
        GO_TO_PEN,
        FEED,
        DONE
    }
}
