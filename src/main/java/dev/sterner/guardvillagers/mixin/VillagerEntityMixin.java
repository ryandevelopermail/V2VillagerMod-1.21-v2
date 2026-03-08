package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.util.ArmorerStandManager;
import dev.sterner.guardvillagers.common.util.ArmorerStandMemoryHolder;
import dev.sterner.guardvillagers.common.util.LeatherworkerCraftingMemoryHolder;
import dev.sterner.guardvillagers.common.util.ToolsmithCraftingMemoryHolder;
import dev.sterner.guardvillagers.common.util.WeaponsmithCraftingMemoryHolder;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandManager;
import dev.sterner.guardvillagers.common.util.WeaponsmithStandMemoryHolder;
import dev.sterner.guardvillagers.common.util.ConvertedWorkerJobSiteReservationManager;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(VillagerEntity.class)
public class VillagerEntityMixin implements ArmorerStandMemoryHolder, WeaponsmithStandMemoryHolder, WeaponsmithCraftingMemoryHolder, ToolsmithCraftingMemoryHolder, LeatherworkerCraftingMemoryHolder {
    private static final String WEAPONSMITH_LAST_CRAFTED_KEY = "GuardVillagersLastWeaponsmithCrafted";
    private static final String TOOLSMITH_LAST_CRAFTED_KEY = "GuardVillagersLastToolsmithCrafted";
    private static final String LEATHERWORKER_LAST_CRAFTED_KEY = "GuardVillagersLastLeatherworkerCrafted";
    private static final Logger LOGGER = LoggerFactory.getLogger(VillagerEntityMixin.class);
    private static final long GUARDVILLAGERS_RESERVED_POI_CLEANUP_BASE_COOLDOWN_TICKS = 100L;
    private static final long GUARDVILLAGERS_RESERVED_POI_CLEANUP_MAX_COOLDOWN_TICKS = 1600L;
    private static final int GUARDVILLAGERS_RESERVED_POI_CLEANUP_MAX_BACKOFF_STEPS = 4;
    private static final AtomicLong GUARDVILLAGERS_RESERVED_POI_FALLBACK_ACTIVATIONS = new AtomicLong();
    private static final AtomicLong GUARDVILLAGERS_RESERVED_POI_FALLBACK_COOLDOWN_SKIPS = new AtomicLong();
    private static final AtomicLong GUARDVILLAGERS_RESERVED_POI_FALLBACK_PROFESSION_RESETS = new AtomicLong();
    private final Map<UUID, ArmorerStandManager.StandProgress> guardvillagers$armorerStandMemory = new HashMap<>();
    private final Map<UUID, WeaponsmithStandManager.StandProgress> guardvillagers$weaponsmithStandMemory = new HashMap<>();
    @Nullable
    private Identifier guardvillagers$lastWeaponsmithCrafted;
    @Nullable
    private Identifier guardvillagers$lastToolsmithCrafted;
    @Nullable
    private Identifier guardvillagers$lastLeatherworkerCrafted;
    @Unique
    private final Map<BlockPos, Long> guardvillagers$nextReservedCleanupTickByPos = new HashMap<>();
    @Unique
    private final Map<BlockPos, Integer> guardvillagers$reservedCleanupBackoffByPos = new HashMap<>();

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

    @Override
    public @Nullable Identifier guardvillagers$getLastToolsmithCrafted() {
        return guardvillagers$lastToolsmithCrafted;
    }

    @Override
    public void guardvillagers$setLastToolsmithCrafted(@Nullable Identifier identifier) {
        guardvillagers$lastToolsmithCrafted = identifier;
    }

    @Override
    public @Nullable Identifier guardvillagers$getLastLeatherworkerCrafted() {
        return guardvillagers$lastLeatherworkerCrafted;
    }

    @Override
    public void guardvillagers$setLastLeatherworkerCrafted(@Nullable Identifier identifier) {
        guardvillagers$lastLeatherworkerCrafted = identifier;
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

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void guardvillagers$readToolsmithCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        guardvillagers$lastToolsmithCrafted = null;
        if (!nbt.contains(TOOLSMITH_LAST_CRAFTED_KEY, 8)) {
            return;
        }
        Identifier parsed = Identifier.tryParse(nbt.getString(TOOLSMITH_LAST_CRAFTED_KEY));
        guardvillagers$lastToolsmithCrafted = parsed;
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void guardvillagers$readLeatherworkerCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        guardvillagers$lastLeatherworkerCrafted = null;
        if (!nbt.contains(LEATHERWORKER_LAST_CRAFTED_KEY, 8)) {
            return;
        }
        Identifier parsed = Identifier.tryParse(nbt.getString(LEATHERWORKER_LAST_CRAFTED_KEY));
        guardvillagers$lastLeatherworkerCrafted = parsed;
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

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void guardvillagers$writeToolsmithCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        if (guardvillagers$lastToolsmithCrafted != null) {
            nbt.putString(TOOLSMITH_LAST_CRAFTED_KEY, guardvillagers$lastToolsmithCrafted.toString());
        } else {
            nbt.remove(TOOLSMITH_LAST_CRAFTED_KEY);
        }
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void guardvillagers$writeLeatherworkerCraftingMemory(NbtCompound nbt, CallbackInfo ci) {
        if (guardvillagers$lastLeatherworkerCrafted != null) {
            nbt.putString(LEATHERWORKER_LAST_CRAFTED_KEY, guardvillagers$lastLeatherworkerCrafted.toString());
        } else {
            nbt.remove(LEATHERWORKER_LAST_CRAFTED_KEY);
        }
    }

    @Inject(method = "mobTick", at = @At("TAIL"))
    private void guardvillagers$releaseReservedConvertedWorkerJobSites(CallbackInfo ci) {
        VillagerEntity villager = (VillagerEntity) (Object) this;
        if (villager.getWorld().isClient || !(villager.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
                .filter(globalPos -> globalPos.dimension() == serverWorld.getRegistryKey())
                .map(GlobalPos::pos)
                .filter(jobPos -> ConvertedWorkerJobSiteReservationManager.isReservedForAnyConvertedWorker(serverWorld, jobPos))
                .ifPresent(jobPos -> {
                    BlockPos immutableJobPos = jobPos.toImmutable();
                    long gameTime = serverWorld.getTime();
                    long nextAllowedTick = guardvillagers$nextReservedCleanupTickByPos.getOrDefault(immutableJobPos, Long.MIN_VALUE);
                    if (gameTime < nextAllowedTick) {
                        long skipped = GUARDVILLAGERS_RESERVED_POI_FALLBACK_COOLDOWN_SKIPS.incrementAndGet();
                        if (skipped % 25L == 0L) {
                            LOGGER.debug("fallback cleanup cooldown skip metrics: activations={} cooldownSkips={} professionResets={}",
                                    GUARDVILLAGERS_RESERVED_POI_FALLBACK_ACTIVATIONS.get(),
                                    skipped,
                                    GUARDVILLAGERS_RESERVED_POI_FALLBACK_PROFESSION_RESETS.get());
                        }
                        return;
                    }

                    int backoffStep = guardvillagers$reservedCleanupBackoffByPos.getOrDefault(immutableJobPos, 0);
                    long cooldownTicks = Math.min(
                            GUARDVILLAGERS_RESERVED_POI_CLEANUP_BASE_COOLDOWN_TICKS << Math.min(backoffStep, GUARDVILLAGERS_RESERVED_POI_CLEANUP_MAX_BACKOFF_STEPS),
                            GUARDVILLAGERS_RESERVED_POI_CLEANUP_MAX_COOLDOWN_TICKS);
                    guardvillagers$nextReservedCleanupTickByPos.put(immutableJobPos, gameTime + cooldownTicks);
                    guardvillagers$reservedCleanupBackoffByPos.put(immutableJobPos,
                            Math.min(backoffStep + 1, GUARDVILLAGERS_RESERVED_POI_CLEANUP_MAX_BACKOFF_STEPS));

                    long activations = GUARDVILLAGERS_RESERVED_POI_FALLBACK_ACTIVATIONS.incrementAndGet();
                    String reservedGuard = ConvertedWorkerJobSiteReservationManager.getReservedGuard(serverWorld, immutableJobPos)
                            .map(UUID::toString)
                            .orElse("unknown");
                    VillagerProfession reservedProfession = ConvertedWorkerJobSiteReservationManager.getReservedProfession(serverWorld, immutableJobPos)
                            .orElse(VillagerProfession.NONE);

                    LOGGER.debug("fallback cleanup triggered: villager={} jobSite={} guard={} reservedProfession={} activation={} cooldown={} backoffStep={}",
                            villager.getUuidAsString(),
                            immutableJobPos.toShortString(),
                            reservedGuard,
                            reservedProfession,
                            activations,
                            cooldownTicks,
                            backoffStep);
                    villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
                    villager.getBrain().forget(MemoryModuleType.JOB_SITE);
                    villager.getBrain().forget(MemoryModuleType.POTENTIAL_JOB_SITE);

                    VillagerProfession profession = villager.getVillagerData().getProfession();
                    if (profession != VillagerProfession.NONE
                            && profession != VillagerProfession.NITWIT
                            && profession == reservedProfession) {
                        villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.NONE));
                        long professionResets = GUARDVILLAGERS_RESERVED_POI_FALLBACK_PROFESSION_RESETS.incrementAndGet();
                        LOGGER.debug("profession reset due reserved POI: villager={} from={} reservedProfession={} jobSite={} resets={} activations={} cooldownSkips={}",
                                villager.getUuidAsString(),
                                profession,
                                reservedProfession,
                                immutableJobPos.toShortString(),
                                professionResets,
                                activations,
                                GUARDVILLAGERS_RESERVED_POI_FALLBACK_COOLDOWN_SKIPS.get());
                    } else {
                        LOGGER.debug("profession reset skipped for reserved POI cleanup: villager={} currentProfession={} reservedProfession={} jobSite={} activations={} cooldownSkips={}",
                                villager.getUuidAsString(),
                                profession,
                                reservedProfession,
                                immutableJobPos.toShortString(),
                                activations,
                                GUARDVILLAGERS_RESERVED_POI_FALLBACK_COOLDOWN_SKIPS.get());
                    }
                });
    }

}
