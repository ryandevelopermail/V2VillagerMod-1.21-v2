package dev.sterner.guardvillagers.common.professionalstorage;

/** Query result for a pairing whose worker is either loaded and validated or currently unavailable. */
public record ProfessionalStorageResolution(
        ProfessionalStoragePairing pairing,
        WorkerAvailability workerAvailability
) {
    public enum WorkerAvailability {
        LOADED,
        UNLOADED
    }
}
