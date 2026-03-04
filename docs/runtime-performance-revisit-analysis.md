# Runtime performance revisit analysis

## Observed hot paths

From branch inspection, the primary avoidable server-load issue was repeated **world-wide villager scans** during profession conversion hooks:

- `ButcherBehavior.tryConvertButchersWithWeapon`
- `FishermanBehavior.tryConvertFishermenWithRod`

Both methods already consumed `VillagerConversionCandidateIndex.pollCandidates(...)`, but then also performed an additional full-world `getEntitiesByClass` scan across world-border bounds every conversion cycle.

Because conversion hooks are run periodically (`GuardVillagers` end-server-tick path), this created O(all loaded villagers) sweeps on a tight cadence and can contribute to "Can't keep up" spikes under load.

## New POI mismatch error correlation

The log error:

```
POI data mismatch: never registered at BlockPos{...}
```

is commonly triggered when ticket/POI release runs against stale or already-unregistered POI data during entity transition/removal edges.

This branch had several villager-to-guard conversion paths that called `releaseTicketFor(...)` right before `discard()`.
Switching these calls to brain memory forgets avoids forcing POI ticket release from conversion code paths while still removing villager memories during conversion.

## Fixes applied in this revisit

1. Removed world-wide fallback scans from butcher/fisherman conversion hooks.
2. Replaced conversion-time `releaseTicketFor(...)` calls with `getBrain().forget(...)` in all villager conversion flows:
   - player conversion
   - armor stand conversion
   - butcher/fisherman/mason profession conversions

## Additional follow-up recommendations (not changed yet)

- Add lightweight profiling counters around `ProfessionDefinitions.runConversionHooks(world)` to track candidate counts and conversions per tick window.
- Consider rate-limiting conversion hooks further under TPS pressure (e.g., skip hook cycle when mspt exceeds threshold).
- Optionally downgrade some pairing `INFO` logs to `DEBUG` if high-frequency in real servers.


## Follow-up optimization expansion

Additional optimizations were extended into more behavior files:

- `ButcherBehavior` chest listeners now rate-limit conversion scans and only run hooks when a convertible weapon is present in the paired chest.
- `FishermanBehavior` chest listeners now rate-limit conversion scans and only run hooks when a fishing rod trigger exists in barrel/chest storage.
- `MasonBehavior` conversion polling now skips stale/removed/off-world entities before deeper checks.

This reduces conversion-hook churn caused by rapid inventory mutation events while keeping conversion responsiveness.
