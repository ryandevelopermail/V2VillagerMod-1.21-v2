package dev.sterner.guardvillagers.common.professionalstorage;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;

import java.util.Objects;
import java.util.Optional;

/** Stable, serialization-safe identity for a native profession or converted professional role. */
public record ProfessionalRoleId(Identifier value) implements Comparable<ProfessionalRoleId> {
    public static final ProfessionalRoleId BUTCHER_GUARD = guardRole("butcher_guard");
    public static final ProfessionalRoleId FISHERMAN_GUARD = guardRole("fisherman_guard");
    public static final ProfessionalRoleId MASON_GUARD = guardRole("mason_guard");
    public static final ProfessionalRoleId LUMBERJACK = guardRole("lumberjack");
    public static final ProfessionalRoleId QUARTERMASTER = guardRole("quartermaster");

    public ProfessionalRoleId {
        Objects.requireNonNull(value, "value");
    }

    public static ProfessionalRoleId fromVillagerProfession(VillagerProfession profession) {
        return new ProfessionalRoleId(Registries.VILLAGER_PROFESSION.getId(profession));
    }

    public static Optional<ProfessionalRoleId> parse(String raw) {
        Identifier id = Identifier.tryParse(raw);
        return id == null ? Optional.empty() : Optional.of(new ProfessionalRoleId(id));
    }

    private static ProfessionalRoleId guardRole(String path) {
        return new ProfessionalRoleId(Identifier.of(GuardVillagers.MODID, path));
    }

    @Override
    public int compareTo(ProfessionalRoleId other) {
        return value.toString().compareTo(other.value.toString());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
