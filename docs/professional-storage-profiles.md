# Professional storage profiles

Opening a registered professional chest or barrel builds a server-side snapshot. The panel refreshes when the container is reopened. A valid double chest is counted once, and career counters aggregate the distinct workers paired with the opened storage. Mixed-role storage retains the generic overview. If a paired worker is unloaded, live worker-dependent values are marked partial or unknown; stored item counts and persisted career totals remain available.

## Remaining profiles

| Role | Tabs | Main information |
| --- | --- | --- |
| Shepherd | Overview, Flock, Crafting, Distribution | Loom and table, shears, wheat, wool, banner recipes with inputs, beds, fences, gates, shearing, crafting, placement, and direct deliveries |
| Librarian | Overview, Crafting, Distribution | Lectern and table, recipes with inputs, book materials and shelves, crafted items and direct deliveries |
| Mason | Overview, Stonecutting, Crafting, Distribution | Stonecutter and table, stone and clay, stonecut output, tool materials and final tools crafted, direct deliveries |
| Butcher Guard | Overview, Cooking, Hunting, Earlier career | Smoker state, smokable input and fuel, cooked output collected, animals hunted and loot stored; applicable native Butcher totals are preserved |
| Mason Guard | Overview, Stonecutting, Construction, Distribution | Stonecutter, materials, stonecut output, mined blocks, placed walls, earlier tool crafting, deliveries |
| Lumberjack | Overview, Forestry, Materials, Distribution | Crafting table, logs, saplings, planks, sticks, charcoal, harvested logs, items deposited and delivered |
| Quartermaster | Overview, Storage, Distribution | Lectern, bank contents and slot usage, items delivered |

A recipe count reports ingredient availability from the installed native crafting goal; it does not promise that the table, daily quota, or space for the output is available. Shepherd's count covers banner recipes, while beds and pen materials have separate demand-driven workflows. The Butcher Guard uses the guard's actual smoking and fuel predicates. When multiple loaded workers share storage, the recipe and smoker-state details come from the lowest worker UUID, matching the existing profile convention.

Counters record successful outcomes from the time this feature is installed; they cannot reconstruct earlier work. The native Mason and Butcher counters are transferred to their guards on a successful conversion. Librarian and Quartermaster totals remain separately keyed to their respective roles when the worker is promoted or demoted. Direct-delivery counters exclude generic overflow or universal routes; guard and Quartermaster delivery totals count the amount actually inserted, including partial insertions. A Lumberjack's harvested-log count is the number of log blocks removed, not the number of drops.

Existing saved specialist guards restore their registered pairing when the entity and its storage are loaded. Lumberjacks also register a chest created during recovery. Unpaired or invalid storage remains vanilla, and custom container names remain intact.

## Manual checks

- Open each paired role's storage; confirm its title, tab order, scrolling, and labels. Open a normal unpaired chest and mixed-role shared storage as controls.
- Change the inputs and reopen the panel. Confirm double chests are counted once, shared workers are aggregated, and unloaded workers are shown as partial or unknown.
- Complete and interrupt each role's work: shear sheep, craft and place a bed, craft and deliver goods, cut stone and craft a tool, mine and build a wall, hunt and smoke food, fell and distribute wood, and have a Quartermaster deliver items. Confirm only successful work changes the appropriate counter and that partial transfers count only inserted items.
- Convert a working Butcher and Mason to their guard roles, reload the world, and verify their applicable earlier totals remain. Check an existing saved guard and a Lumberjack that has to create a replacement chest.
- Have a Fisherman Guard deliver fish to a Butcher and confirm its existing Fish delivered counter increases by the amount accepted.
- Confirm career totals survive a save and reload, while the live stock counts update when the container is reopened.
