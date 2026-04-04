# Per-Profession Playtest Guide

Each scenario is designed as a standalone, fast-to-verify test.
Run in Creative/operator mode with `/time set day` and `/gamerule doDaylightCycle false` so guards stay active.

Use `/time set 6000` to skip to midday and prevent guards from sleeping.

---

## Setup Shared by All Tests

1. Place a **Bell**.
2. Place a **Chest** adjacent to or within 3 blocks of the Bell — this is the **Bell Chest** (village supply).
3. All profession guards need a **paired chest** within 3 blocks of their job block.
4. Most guards also need a **crafting table** paired within 3 blocks of *both* the job block and chest.

---

## Pre-merge regression gate (Wallbuilder)

- [ ] If a PR changes mason wallbuilder logic, complete the log-based contract checks in `docs/mason-wallbuilder-regression-contract.md` before merge.

---

## A — Lumberjack

### A-1 · Auto-Spawn & Conversion
**Preconditions:** Bell placed. No lumberjack yet.
**Steps:**
1. Wait ~30 seconds (600 ticks). The `VillageLumberjackSpawnManager` scans every 600 ticks.
2. Observe chat / logs for `"Placed crafting table near bell"`.
3. A nearby villager without a job should walk to the crafting table and convert to Lumberjack.

**Pass:** A lumberjack guard entity exists within ~20 blocks of the bell.

---

### A-2 · Chopping & Depositing Logs
**Preconditions:** Lumberjack exists with paired chest.
**Steps:**
1. Ensure trees are within ~64 blocks of the bell.
2. Wait; observe lumberjack walking to trees and chopping.
3. After chop cycle, watch lumberjack return to chest and deposit logs.

**Pass:** Logs accumulate in the lumberjack's paired chest.

---

### A-3 · Charcoal Production (Furnace Modifier)
**Preconditions:** Lumberjack has paired chest + crafting table.
**Steps:**
1. Place a **Furnace** within 3 blocks of *both* the crafting table and the chest.
2. Place a **Guard Stand Modifier block** touching the furnace (1 block away, any face).
3. Add logs to the lumberjack's chest (≥16).
4. Wait ~30 seconds.

**Pass:** Furnace input slot fills with logs; charcoal appears in the output slot; lumberjack visits the furnace.

**Debug tip:** If lumberjack never visits, check logs for `"outside_zone"` — furnace must be within `JOB_BLOCK_PAIRING_RANGE + 2` (≈5 blocks) of *both* the chest and crafting table.

---

### A-4 · Pen Building
**Preconditions:** Lumberjack has paired chest.
**Steps:**
1. Add **≥20 any-wood fences** + **≥1 any-wood fence gate** to the lumberjack's paired chest.
2. Wait up to 30 seconds (600-tick cooldown).

**Pass:** Lumberjack walks to a flat area within ~300 blocks of bell and places a 6×6 fence perimeter with gate on the south face.

**Debug tip:** Logs show `"LumberjackPen: planned pen at … (N fences, gate at …)"`. If no suitable flat 6×6 spot exists nearby, the goal silently re-queues.

---

## B — Mason

### B-1 · Mining Stairs
**Preconditions:** Mason has job block (stonecutter) + paired chest.
**Steps:**
1. Observe mason going underground and mining cobblestone via stair descent.

**Pass:** Cobblestone accumulates in mason's paired chest.

---

### B-2 · Stonecutting
**Preconditions:** Mason has cobblestone in chest.
**Steps:**
1. Watch mason use the stonecutter to convert cobblestone → stone bricks.

**Pass:** Stone bricks appear in mason's chest alongside or replacing raw cobblestone.

---

### B-3 · Wall Building
**Preconditions:** Mason has cobblestone in chest (≥ perimeter segment count).
**Steps:**
1. Set `masonWallPoiMode` in config and note expected footprint behavior:
   - `JOB_SITES_ONLY`: only workstation/job-site POIs define bounds (beds ignored).
   - `JOBS_AND_BEDS` (default): workstation/job-site POIs + bed HOME POIs define bounds.
   - `ALL_POIS`: all POI types define bounds (backward-compat mode).
2. Ensure the village has POIs that match the chosen mode spread out enough to make a perimeter.
3. The mason computes an AABB around those selected POIs, expands it 10 blocks, and plans a one-thick perimeter (by X/Z) that places at sampled surface Y and vertically fills depressions up to 4 blocks deep.
4. Watch for mason walking the perimeter and placing blocks.
5. Check logs for `MasonWallBuilder ... wall POI scan mode=<MODE>` to confirm the active mode.

**Pass:** A one-thick cobblestone perimeter forms around the village outline, with vertical anti-gap fill in dips (up to 4 blocks deep). Steep terrain (>3 block upward delta from anchor level) is skipped. At least 1 gap is always left open.

---

### B-4 · Cobblestone Distribution to Lumberjack
**Preconditions:** Mason has excess cobblestone; lumberjack exists with chest.
**Steps:**
1. Allow mason to accumulate >32 cobblestone.
2. Wait for `MasonGuardChestDistributionGoal` to fire.

**Pass:** Cobblestone moves from mason chest to lumberjack chest (which the lumberjack uses as furnace fuel seed-stock).

---

## C — Librarian / Quartermaster

### C-1 · Quartermaster Promotion (Double Chest)
**Preconditions:** Librarian has job block (lectern) + single paired chest.
**Steps:**
1. Place a **second chest** adjacent to the first (making a double chest) within 3 blocks of the lectern.
2. Observe the pairing animation / sound.

**Pass:** Librarian becomes a Quartermaster — check logs for `"Librarian promoted to Quartermaster"` or verify `QuartermasterGoal` is now active (visible by the librarian hauling items between chests).

**Debug tip:** The double-chest detection scans face-adjacent world blocks at promotion time. Both chests must be adjacent to each other and within pairing range of the lectern.

---

### C-2 · Quartermaster — Planks to Lumberjack
**Preconditions:** Quartermaster promoted. Lumberjack chest has <16 planks. Bell chest has planks.
**Steps:**
1. Put planks (any wood type) in the Bell Chest.
2. Wait up to 15 seconds (300-tick QM poll).

**Pass:** Planks move from Bell Chest → Lumberjack's paired chest.

---

### C-3 · Quartermaster — Stone to Mason
**Preconditions:** Quartermaster promoted. Mason chest has <32 stone/cobblestone. Bell chest has stone.
**Steps:**
1. Put cobblestone/stone in Bell Chest.
2. Wait up to 15 seconds.

**Pass:** Stone moves from Bell Chest → Mason's paired chest.

---

## D — Cartographer

### D-1 · Map Exploration Workflow
**Preconditions:** Cartographer has job block (cartography table) + paired chest.
**Steps:**
1. Add **≥4 empty maps** (paper × 1 in crafting, or `give @p map 4`) to cartographer's chest.
2. Wait up to 30 seconds.

**Pass:** Cartographer picks up 4 maps, walks to 4 tile positions around the job site (each ~128 blocks apart), returns to chest, deposits filled maps.

**Log to watch:** `"Cartographer … canStart=true: emptyMaps=4 pendingTiles=4"` then `"completed territory 1/4 … 4/4"`.

**Cycle reset:** After all 4 tiles are mapped, the cartographer automatically resets and starts a new cycle. You should see `"all 4 tiles already mapped — resetting for next mapping cycle"` on the next check.

---

### D-2 · Lumberjack Bounds Unlock
**Preconditions:** Cartographer completes all 4 maps (D-1 passed).
**Steps:**
1. Observe `"Cartographer … wrote mapped bounds for bell … → [minX,minZ to maxX,maxZ]"` in logs.
2. The lumberjack will now restrict tree chopping to within those bounds.

**Pass:** Lumberjack no longer chops trees far outside the mapped region (verify by ensuring all trees inside bounds get chopped first).

---

## E — Shepherd

### E-1 · Bed Crafting
**Preconditions:** Shepherd has job block (loom) + paired chest + crafting table.
**Steps:**
1. Add **≥3 planks (any)** + **≥3 wool (any single color)** to shepherd's chest.
2. Ensure at least 1 nearby villager has no sleeping position.
3. Wait up to a full in-game day (daily crafting limit applies).

**Pass:** A colored bed appears in shepherd's chest.

---

### E-2 · Bed Placement
**Preconditions:** Bed in shepherd's chest (E-1 passed). At least one existing bed block placed nearby (serves as anchor).
**Steps:**
1. Watch shepherd pick up bed from chest and walk to placement site adjacent to an existing bed block.
2. Shepherd places the bed.

**Pass:** A new bed block appears near the existing beds. Bedless villager count should decrease.

**Debug tip:** If shepherd never places, there may be no existing bed block to use as an anchor. Place one manually as seed.

---

### E-3 · Animal Shearing / Pen Behavior
**Preconditions:** Pen exists (detected by VillagePenRegistry, which rescans every 6000 ticks / 5 min). Shepherd has banner in chest or pen.
**Steps:**
1. Ensure the pen's fence gate is registered (VillagePenRegistry finds pens near bells that have a paired chest).
2. Place sheep in pen.
3. Watch shepherd shear sheep.

**Pass:** Wool items appear in shepherd's chest. Sheep are sheared.

---

## F — Butcher

### F-1 · Conversion Trigger (Axe)
**Preconditions:** Butcher has job block (smoker) + paired chest.
**Steps:**
1. Place an **axe** in butcher's paired chest.
2. Observe conversion trigger.

**Pass:** Logs show `"Butcher converted"` or equivalent. Guard retains the axe as weapon.

---

### F-2 · Meat Cooking
**Preconditions:** Butcher converted, paired chest exists.
**Steps:**
1. Animals exist in or near pens.
2. Butcher navigates to nearest pen (via VillagePenRegistry first, then legacy banner scan).
3. Butcher kills animals, returns, loads smoker, cooks meat.

**Pass:** Cooked meat appears in butcher's chest.

---

### F-3 · Cooked Meat Distribution
**Preconditions:** Cooked meat in butcher's chest.
**Steps:**
1. Observe `ButcherMeatDistributionGoal` or `ButcherToLeatherworkerDistributionGoal` fire.

**Pass:** Meat moves to guards' food supply or leatherworker's chest.

---

## G — Fletcher

### G-1 · Arrow Crafting
**Preconditions:** Fletcher has job block (fletching table) + paired chest.
**Steps:**
1. Add **flint**, **sticks**, and **feathers** to fletcher's chest.
2. Wait for `FletcherFletchingTableGoal` to fire (countdown-based: fires every N ticks when materials present).

**Pass:** Arrows appear in fletcher's chest (4 arrows per recipe: 1 flint + 1 stick + 1 feather).

---

### G-2 · Arrow Distribution to Archers
**Preconditions:** Arrows in fletcher's chest. Archer guards present.
**Steps:**
1. Wait for `FletcherDistributionGoal`.

**Pass:** Arrows move from fletcher's chest to archer guard inventories.

---

## H — Armorer

### H-1 · Golem Healing (Chest-Gated)
**Preconditions:** Armorer has job block (blast furnace) + paired chest with iron ingots. Iron Golem is damaged.
**Steps:**
1. Pre-damage a golem (`/damage @e[type=iron_golem] 50`).
2. Ensure armorer's chest has ≥5 iron ingots.
3. Watch armorer approach golem and heal it.

**Pass:** Golem health increases. Iron ingot count in chest decreases by 1 per heal event. Golem repair sound plays.

**Pass (negative):** Remove iron from chest → armorer stops healing.

---

## I — Cleric

### I-1 · Potion Brewing
**Preconditions:** Cleric has brewing stand + paired chest.
**Steps:**
1. Add blaze powder, nether wart, water bottles to chest.
2. Watch cleric load the brewing stand.

**Pass:** Potions appear in chest over time.

---

## J — Farmer

### J-1 · Harvest & Animal Feeding
**Preconditions:** Farmer has composter job block + paired chest. Pen exists (VillagePenRegistry).
**Steps:**
1. Ensure wheat/crops are nearby.
2. Farmer harvests crops, returns produce to chest.
3. Farmer opens pen gate, feeds animals inside pen.

**Pass:** Crops harvested; animals in pen breed (hearts appear); produce in farmer's chest.

---

## K — Full Village Scenario

This test validates the economy working end-to-end as a system.

### Setup
1. Place bell.
2. Place bell chest.
3. Spawn or allow to auto-spawn: **1 Lumberjack**, **1 Mason**, **1 Librarian** (promote to Quartermaster via double-chest), **1 Cartographer**, **1 Shepherd**, **1 Butcher**, **1 Fletcher**, **1 Farmer**.
4. Each needs paired chest + job block. Give each chest a small seed stock:
   - Bell chest: 16 planks, 16 cobblestone.
   - Lumberjack chest: empty (let lumberjack fill it with logs).
   - Mason chest: empty (let mason fill with cobblestone from mining).
   - Shepherd chest: 6 wool, 6 planks.
   - Fletcher chest: 16 flint, 16 sticks, 16 feathers.
5. Place a furnace within 3 blocks of lumberjack's crafting table + chest; add a Guard Stand Modifier block touching it.
6. Build a small fence pen (or wait for lumberjack to build one); put 2 sheep inside.
7. Place 1 existing bed block as anchor for shepherd.

### Expected Timeline (Minecraft time units)

| Time | Expected Event |
|---|---|
| t=0 | Start observing. Lumberjack starts chopping. |
| t=300–600 | Quartermaster polls; routes planks to shepherd if bell chest has them. |
| t=600 | Lumberjack pen builder checks for fences (if ≥20 fences in chest). |
| t=600 | Cartographer checks for 4 maps. |
| t=1 in-game day | Shepherd crafts first bed; lumberjack deposits logs. |
| t=2 days | Mason has ≥32 cobble; starts perimeter wall. Charcoal in furnace output. |
| t=3 days | Cartographer has walked all 4 tiles; lumberjack bounds unlocked. |
| t=4 days | Butcher has cooked meat in chest; cleric has potions; fletcher has arrows. |

### Pass Criteria
- [ ] Logs in lumberjack chest
- [ ] Charcoal produced in furnace
- [ ] Planks routed by Quartermaster
- [ ] Cobblestone wall partial or complete
- [ ] Beds crafted and placed by shepherd
- [ ] Arrows in fletcher chest
- [ ] Cooked meat in butcher chest
- [ ] 4 filled maps in cartographer chest
- [ ] Lumberjack bounds written (check logs)
- [ ] No phantom chest sounds during play
- [ ] No TPS spike when ≥5 guards active

---

## Logging Quick Reference

Enable debug logging to see canStart decisions:

```
# In server's log4j2.xml or via /log command if available:
# Package: dev.sterner.guardvillagers.common.entity.goal
# Level: INFO (most canStart decisions log at INFO already)
```

Key log markers to grep for:

| Prefix | Meaning |
|---|---|
| `Cartographer … canStart=` | Cartographer map workflow check |
| `LumberjackPen …: planned pen at` | Pen builder found valid site |
| `LumberjackPen …: ran out of fences` | Pen builder ran dry mid-build |
| `Stop lumberjack furnace service: … reason=` | Furnace goal stopped, see reason |
| `Librarian promoted to Quartermaster` | QM promotion succeeded |
| `Cartographer … wrote mapped bounds` | Lumberjack bounds unlocked |
| `Cartographer … all 4 tiles already mapped — resetting` | Starting next cycle |
