package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.common.developer.DeveloperProfession;
import dev.sterner.guardvillagers.common.developer.DeveloperProfessionSelection;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupRequest;
import dev.sterner.guardvillagers.common.developer.DeveloperSetupType;
import dev.sterner.guardvillagers.common.developer.LumberjackInventoryPreset;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure representation of the developer setup packet, kept independent of Minecraft bootstrap state. */
public record DeveloperSetupRequestWireData(
        int setupType,
        List<ProfessionEntry> professions,
        boolean createPairedChest,
        boolean createCraftingTable,
        boolean createFurnaceSetup,
        boolean createShepherdSupply,
        int inventoryPreset,
        boolean generateMatureTrees,
        int treeCount
) {
    public DeveloperSetupRequestWireData {
        if (professions != null) {
            professions = List.copyOf(professions);
        }
    }

    public static DeveloperSetupRequestWireData fromRequest(DeveloperSetupRequest request) {
        return new DeveloperSetupRequestWireData(
                request.setupType().networkId(),
                request.professionSelections().stream()
                        .map(selection -> new ProfessionEntry(selection.profession().networkId(), selection.quantity()))
                        .toList(),
                request.createPairedChest(),
                request.createCraftingTable(),
                request.createFurnaceSetup(),
                request.createShepherdSupply(),
                request.inventoryPreset().networkId(),
                request.generateMatureTrees(),
                request.treeCount());
    }

    public Optional<DeveloperSetupRequest> decodeRequest() {
        Optional<DeveloperSetupType> decodedType = DeveloperSetupType.fromNetworkId(setupType);
        Optional<LumberjackInventoryPreset> decodedPreset = LumberjackInventoryPreset.fromNetworkId(inventoryPreset);
        if (decodedType.isEmpty() || decodedPreset.isEmpty() || professions == null) {
            return Optional.empty();
        }
        List<DeveloperProfessionSelection> decodedProfessions = new ArrayList<>();
        for (ProfessionEntry entry : professions) {
            Optional<DeveloperProfession> profession = DeveloperProfession.fromNetworkId(entry.professionId());
            if (profession.isEmpty()) {
                return Optional.empty();
            }
            decodedProfessions.add(new DeveloperProfessionSelection(profession.get(), entry.quantity()));
        }
        return Optional.of(new DeveloperSetupRequest(
                decodedType.get(),
                decodedProfessions,
                createPairedChest,
                createCraftingTable,
                createFurnaceSetup,
                createShepherdSupply,
                decodedPreset.get(),
                generateMatureTrees,
                treeCount));
    }

    public record ProfessionEntry(int professionId, int quantity) {
    }
}
