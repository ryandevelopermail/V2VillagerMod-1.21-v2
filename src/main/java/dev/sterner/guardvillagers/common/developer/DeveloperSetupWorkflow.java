package dev.sterner.guardvillagers.common.developer;

/**
 * Minecraft-independent setup state machine. The server session supplies observations after it
 * performs the action for the current stage, keeping timing and cleanup behavior unit-testable.
 */
public final class DeveloperSetupWorkflow {
    private final DeveloperSetupType setupType;
    private final boolean placeInfrastructure;
    private final boolean populateInventory;
    private final boolean generateTrees;
    private final int stageTimeoutTicks;
    private DeveloperSetupStage stage = DeveloperSetupStage.PREPARE;
    private int ticksInStage;
    private boolean subjectRestrained;
    private String failureMessage = "";

    public DeveloperSetupWorkflow(
            DeveloperSetupType setupType,
            boolean placeInfrastructure,
            boolean populateInventory,
            boolean generateTrees,
            int stageTimeoutTicks
    ) {
        this.setupType = setupType;
        this.placeInfrastructure = placeInfrastructure;
        this.populateInventory = populateInventory;
        this.generateTrees = generateTrees;
        this.stageTimeoutTicks = Math.max(1, stageTimeoutTicks);
    }

    public DeveloperSetupStage stage() {
        return stage;
    }

    public int ticksInStage() {
        return ticksInStage;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public void tick(Observation observation) {
        if (stage.isTerminal()) {
            return;
        }

        ticksInStage++;
        switch (stage) {
            case PREPARE -> {
                if (observation.prepared()) {
                    transition(setupType == DeveloperSetupType.PLAIN_VILLAGER
                            ? afterBaseSetup()
                            : DeveloperSetupStage.WAIT_FOR_PROFESSION);
                }
            }
            case WAIT_FOR_PROFESSION -> {
                if (observation.professionReady()) {
                    transition(setupType == DeveloperSetupType.V2_PROFESSION
                            ? DeveloperSetupStage.PLACE_V2_BLOCKS
                            : afterBaseSetup());
                }
            }
            case PLACE_V2_BLOCKS -> {
                if (observation.v2BlocksPlaced()) {
                    transition(DeveloperSetupStage.WAIT_FOR_PAIRING);
                }
            }
            case WAIT_FOR_PAIRING -> {
                if (observation.pairingReady()) {
                    transition(afterBaseSetup());
                }
            }
            case PLACE_LUMBERJACK_INFRASTRUCTURE -> {
                if (observation.infrastructureReady()) {
                    transition(afterInfrastructure());
                }
            }
            case POPULATE_LUMBERJACK_INVENTORY -> {
                if (observation.inventoryReady()) {
                    transition(afterInventory());
                }
            }
            case GENERATE_TREES -> {
                if (observation.treesReady()) {
                    transition(DeveloperSetupStage.COMPLETE);
                }
            }
            default -> {
            }
        }

        if (!stage.isTerminal() && ticksInStage >= stageTimeoutTicks) {
            fail("Timed out during " + stage.name().toLowerCase().replace('_', ' ') + ".");
        }
    }

    public void fail(String message) {
        if (stage.isTerminal()) {
            return;
        }
        failureMessage = message == null || message.isBlank() ? "Setup failed." : message;
        transition(DeveloperSetupStage.FAILED);
    }

    public void markSubjectRestrained() {
        subjectRestrained = true;
    }

    public void markSubjectReleased() {
        subjectRestrained = false;
    }

    public boolean subjectRestrained() {
        return subjectRestrained;
    }

    public boolean releaseRequired() {
        return stage.isTerminal() && subjectRestrained;
    }

    private DeveloperSetupStage afterBaseSetup() {
        if (placeInfrastructure) {
            return DeveloperSetupStage.PLACE_LUMBERJACK_INFRASTRUCTURE;
        }
        return afterInfrastructure();
    }

    private DeveloperSetupStage afterInfrastructure() {
        if (populateInventory) {
            return DeveloperSetupStage.POPULATE_LUMBERJACK_INVENTORY;
        }
        return afterInventory();
    }

    private DeveloperSetupStage afterInventory() {
        return generateTrees ? DeveloperSetupStage.GENERATE_TREES : DeveloperSetupStage.COMPLETE;
    }

    private void transition(DeveloperSetupStage nextStage) {
        stage = nextStage;
        ticksInStage = 0;
    }

    public record Observation(
            boolean prepared,
            boolean professionReady,
            boolean v2BlocksPlaced,
            boolean pairingReady,
            boolean infrastructureReady,
            boolean inventoryReady,
            boolean treesReady
    ) {
        public static Observation none() {
            return new Observation(false, false, false, false, false, false, false);
        }
    }
}
