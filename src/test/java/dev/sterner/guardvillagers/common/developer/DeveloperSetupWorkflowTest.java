package dev.sterner.guardvillagers.common.developer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperSetupWorkflowTest {
    @Test
    void v2TransitionsFromProfessionWaitThroughChestPairing() {
        DeveloperSetupWorkflow workflow = new DeveloperSetupWorkflow(
                DeveloperSetupType.V2_PROFESSION,
                false,
                20
        );

        workflow.tick(observation(true, false, false, false, false));
        assertEquals(DeveloperSetupStage.WAIT_FOR_PROFESSION, workflow.stage());

        workflow.tick(observation(false, true, false, false, false));
        assertEquals(DeveloperSetupStage.PLACE_V2_BLOCKS, workflow.stage());

        workflow.tick(observation(false, false, true, false, false));
        assertEquals(DeveloperSetupStage.WAIT_FOR_PAIRING, workflow.stage());

        workflow.tick(observation(false, false, false, true, false));
        assertEquals(DeveloperSetupStage.COMPLETE, workflow.stage());
    }

    @Test
    void waitingStageTimesOut() {
        DeveloperSetupWorkflow workflow = new DeveloperSetupWorkflow(
                DeveloperSetupType.V1_PROFESSION,
                false,
                2
        );
        workflow.tick(observation(true, false, false, false, false));

        workflow.tick(DeveloperSetupWorkflow.Observation.none());
        workflow.tick(DeveloperSetupWorkflow.Observation.none());

        assertEquals(DeveloperSetupStage.FAILED, workflow.stage());
        assertTrue(workflow.failureMessage().contains("wait for profession"));
    }

    @Test
    void terminalFailureRequiresRestrainedSubjectRelease() {
        DeveloperSetupWorkflow workflow = new DeveloperSetupWorkflow(
                DeveloperSetupType.V1_PROFESSION,
                false,
                20
        );
        workflow.markSubjectRestrained();
        workflow.fail("expected failure");

        assertTrue(workflow.releaseRequired());
        workflow.markSubjectReleased();
        assertFalse(workflow.releaseRequired());
        assertFalse(workflow.subjectRestrained());
    }

    @Test
    void treeGenerationCompletesAfterBaseSetup() {
        DeveloperSetupWorkflow workflow = new DeveloperSetupWorkflow(
                DeveloperSetupType.PLAIN_VILLAGER,
                true,
                20
        );
        workflow.tick(observation(true, false, false, false, false));
        assertEquals(DeveloperSetupStage.GENERATE_TREES, workflow.stage());

        workflow.tick(observation(false, false, false, false, true));
        assertEquals(DeveloperSetupStage.COMPLETE, workflow.stage());
    }

    private static DeveloperSetupWorkflow.Observation observation(
            boolean prepared,
            boolean professionReady,
            boolean v2BlocksPlaced,
            boolean pairingReady,
            boolean treesReady
    ) {
        return new DeveloperSetupWorkflow.Observation(prepared, professionReady, v2BlocksPlaced, pairingReady, treesReady);
    }
}
