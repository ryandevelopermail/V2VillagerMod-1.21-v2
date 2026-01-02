package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import dev.sterner.guardvillagers.common.util.ArmorerStandMemoryHolder;
import dev.sterner.guardvillagers.common.util.WeaponsmithCraftingMemoryHolder;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandMemoryHolder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mixin(VillagerEntity.class)
public class VillagerEntityMixin implements ArmorerStandMemoryHolder, WeaponsmithStandMemoryHolder, WeaponsmithCraftingMemoryHolder {
    private static final String WEAPONSMITH_LAST_CRAFTED_KEY = "GuardVillagersLastWeaponsmithCrafted";
    private final Map<UUID, ArmorerStandManager.StandProgress> guardvillagers$armorerStandMemory = new HashMap<>();
    private final Map<UUID, WeaponsmithStandManager.StandProgress> guardvillagers$weaponsmithStandMemory = new HashMap<>();
    @Nullable
    private Identifier guardvillagers$lastWeaponsmithCrafted;

    @Override
    public Map<UUID, ArmorerStandManager.StandProgress> guardvillagers$getArmorerStandMemory() {
        return guardvillagers$armorerStandMemory;
    }

    @Override
    public Map<UUID, WeaponsmithStandManager.StandProgress> guardvillagers$getWeaponsmithStandMemory() {
        return guardvillagers$weaponsmithStandMemory;
    }

    @Override
    public @Nullable Identifier guardvillagers$getLastWeaponsmithCrafted() {
        return guardvillagers$lastWeaponsmithCrafted;
    }

    @Override
    public void guardvillagers$setLastWeaponsmithCrafted(@Nullable Identifier identifier) {
        guardvillagers$lastWeaponsmithCrafted = identifier;
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void guardvillagers$readArmorerStandMemory(NbtCompound nbt, CallbackInfo ci) {
        guardvillagers$armorerStandMemory.clear();
        if (!nbt.contains(ArmorerStandManager.ARMOR_STAND_MEMORY_KEY, 9)) {
            return;
        }

        NbtList list = nbt.getList(ArmorerStandManager.ARMOR_STAND_MEMORY_KEY, 10);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            if (!entry.containsUuid("StandId")) {
                continue;
            }
            UUID standId = entry.getUuid("StandId");
            int armorMask = entry.getInt("ArmorMask");
            ArmorerStandManager.StandProgress progress = new ArmorerStandManager.StandProgress();
            progress.setArmorMask(armorMask);
            guardvillagers$armorerStandMemory.put(standId, progress);
        }
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void guardvillagers$readWeaponsmithStandMemory(NbtCompound nbt, CallbackInfo ci) {
        guardvillagers$weaponsmithStandMemory.clear();
        if (!nbt.contains(WeaponsmithStandManager.WEAPON_STAND_MEMORY_KEY, 9)) {
            return;
        }

        NbtList list = nbt.getList(WeaponsmithStandManager.WEAPON_STAND_MEMORY_KEY, 10);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            if (!entry.containsUuid("StandId")) {
                continue;
            }
            UUID standId = entry.getUuid("StandId");
            int handMask = entry.getInt("HandMask");
            WeaponsmithStandManager.StandProgress progress = new WeaponsmithStandManager.StandProgress();
            progress.setHandMask(handMask);
            guardvillagers$weaponsmithStandMemory.put(standId, progress);
        }
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void guardvillagers$readWeaponsmithCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        guardvillagers$lastWeaponsmithCrafted = null;
        if (!nbt.contains(WEAPONSMITH_LAST_CRAFTED_KEY, 8)) {
            return;
        }
        Identifier parsed = Identifier.tryParse(nbt.getString(WEAPONSMITH_LAST_CRAFTED_KEY));
        guardvillagers$lastWeaponsmithCrafted = parsed;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void guardvillagers$writeArmorerStandMemory(NbtCompound nbt, CallbackInfo ci) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, ArmorerStandManager.StandProgress> entry : guardvillagers$armorerStandMemory.entrySet()) {
            NbtCompound standTag = new NbtCompound();
            standTag.putUuid("StandId", entry.getKey());
            standTag.putInt("ArmorMask", entry.getValue().getArmorMask());
            list.add(standTag);
        }
        nbt.put(ArmorerStandManager.ARMOR_STAND_MEMORY_KEY, list);
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void guardvillagers$writeWeaponsmithStandMemory(NbtCompound nbt, CallbackInfo ci) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, WeaponsmithStandManager.StandProgress> entry : guardvillagers$weaponsmithStandMemory.entrySet()) {
            NbtCompound standTag = new NbtCompound();
            standTag.putUuid("StandId", entry.getKey());
            standTag.putInt("HandMask", entry.getValue().getHandMask());
            list.add(standTag);
        }
        nbt.put(WeaponsmithStandManager.WEAPON_STAND_MEMORY_KEY, list);
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void guardvillagers$writeWeaponsmithCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        if (guardvillagers$lastWeaponsmithCrafted != null) {
            nbt.putString(WEAPONSMITH_LAST_CRAFTED_KEY, guardvillagers$lastWeaponsmithCrafted.toString());
        } else {
            nbt.remove(WEAPONSMITH_LAST_CRAFTED_KEY);
        }
    }
}
