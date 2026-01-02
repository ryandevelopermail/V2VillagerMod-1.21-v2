package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import dev.sterner.guardvillagers.common.util.ArmorerStandMemoryHolder;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandMemoryHolder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mixin(VillagerEntity.class)
public class VillagerEntityMixin implements ArmorerStandMemoryHolder, WeaponsmithStandMemoryHolder {
    private final Map<UUID, ArmorerStandManager.StandProgress> guardvillagers$armorerStandMemory = new HashMap<>();
    private final Map<UUID, WeaponsmithStandManager.StandProgress> guardvillagers$weaponsmithStandMemory = new HashMap<>();

    @Override
    public Map<UUID, ArmorerStandManager.StandProgress> guardvillagers$getArmorerStandMemory() {
        return guardvillagers$armorerStandMemory;
    }

    @Override
    public Map<UUID, WeaponsmithStandManager.StandProgress> guardvillagers$getWeaponsmithStandMemory() {
        return guardvillagers$weaponsmithStandMemory;
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
}
