# Profession behavior QA harness entries

This note sits next to profession behavior classes so scenario IDs stay close to implementation.

## Target classes

- `ShepherdBehavior`
- `ShepherdSpecialGoal`
- `ButcherBehavior`

## Harness entry IDs

- `GV-QA-SHEPHERD-BOOTSTRAP-BANNER`
  - Valid pen/gate + shepherd + paired chest, no banner blocks present.
  - Add banner to chest.
  - Expect banner placement flow to start and complete.

- `GV-QA-SHEPHERD-WHEAT-GATHER`
  - After banner exists, add wheat to chest.
  - Expect gather session to start and show multi-tick progression.

- `GV-QA-BUTCHER-CONVERT-AXE`
  - Chest contains axe.
  - Expect conversion trigger + spawned weapon retained after initialization.

- `GV-QA-BUTCHER-CONVERT-SWORD`
  - Chest contains sword.
  - Expect conversion trigger + spawned weapon retained after initialization.

- `GV-QA-CHEST-VARIANTS`
  - Re-run all entries above with:
    1. single chest
    2. double chest
  - Expect equivalent trigger responsiveness in both layouts.

## Suggested automation hook

If automated tests are introduced later (GameTest/integration), keep these IDs as test names or tags so results are traceable to the behavior classes listed above.
