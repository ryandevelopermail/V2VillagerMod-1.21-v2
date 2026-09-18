package dev.sterner.guardvillagers.common.developer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Minecraft-independent inventory plans for developer-created Lumberjack chests. */
public enum LumberjackInventoryPreset {
    EMPTY_NATURAL(0, "Empty / Natural"),
    CHARCOAL_TEST(1, "Charcoal Test", entry(Item.OAK_LOG, 16)),
    // One Lumberjack distribution batch; stays below the local 20-fence pen-builder trigger.
    SHEPHERD_SUPPLY_TEST(2, "Shepherd Supply Test", entry(Item.OAK_FENCE, 8), entry(Item.OAK_FENCE_GATE, 1)),
    GENERAL_MIXED_TEST(3, "General / Mixed Test",
            entry(Item.OAK_LOG, 16),
            entry(Item.OAK_PLANKS, 16),
            entry(Item.STICK, 8),
            entry(Item.OAK_FENCE, 8),
            entry(Item.OAK_FENCE_GATE, 1));

    private final int networkId;
    private final String displayName;
    private final Map<Item, Integer> contents;

    @SafeVarargs
    LumberjackInventoryPreset(int networkId, String displayName, Map.Entry<Item, Integer>... contents) {
        this.networkId = networkId;
        this.displayName = displayName;
        EnumMap<Item, Integer> plan = new EnumMap<>(Item.class);
        for (Map.Entry<Item, Integer> entry : contents) {
            plan.put(entry.getKey(), entry.getValue());
        }
        this.contents = Collections.unmodifiableMap(plan);
    }

    public int networkId() {
        return networkId;
    }

    public String displayName() {
        return displayName;
    }

    public Map<Item, Integer> contents() {
        return contents;
    }

    public LumberjackInventoryPreset next() {
        LumberjackInventoryPreset[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static Optional<LumberjackInventoryPreset> fromNetworkId(int id) {
        for (LumberjackInventoryPreset preset : values()) {
            if (preset.networkId == id) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    public static Map<Item, Integer> createPlan(
            LumberjackInventoryPreset preset,
            boolean includeCharcoalMaterials,
            boolean includeShepherdSupplyMaterials
    ) {
        EnumMap<Item, Integer> plan = new EnumMap<>(Item.class);
        if (preset != null) {
            preset.contents.forEach((item, count) -> mergeMaximum(plan, item, count));
        }
        if (includeCharcoalMaterials) {
            mergeMaximum(plan, Item.OAK_LOG, 16);
        }
        if (includeShepherdSupplyMaterials) {
            mergeMaximum(plan, Item.OAK_FENCE, 8);
            mergeMaximum(plan, Item.OAK_FENCE_GATE, 1);
        }
        return Collections.unmodifiableMap(plan);
    }

    private static void mergeMaximum(Map<Item, Integer> plan, Item item, int count) {
        plan.merge(item, count, Math::max);
    }

    private static Map.Entry<Item, Integer> entry(Item item, int count) {
        return Map.entry(item, count);
    }

    public enum Item {
        OAK_LOG,
        OAK_PLANKS,
        STICK,
        OAK_FENCE,
        OAK_FENCE_GATE
    }
}
