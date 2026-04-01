# Scan Range Tuning (TPS vs Logistics Reach)

This mod now exposes **heavy scan ranges** in config so multiplayer servers can tune CPU cost versus logistics coverage.

## New config options

All values are in blocks.

- `quartermasterScanRange` (default `128`, clamp `32..512`)
  - Used by `QuartermasterGoal` for village-wide logistics scans.
- `armorerFallbackScanRange` (default `128`, clamp `32..512`)
  - Used by `ArmorerIronRoutingGoal` when no Quartermaster is active.
- `overflowRecipientScanRange` (default `96`, clamp `16..256`)
  - Used by overflow recipient scans in distribution goals.
  - Also used as the universal distribution recipient search range.
- `overflowFallbackQmSearchRadius` (default `128`, clamp `32..512`)
  - Used to find the nearest Quartermaster chest when overflow has no direct recipient.

## Why lower defaults?

Older values around `300` blocks can be expensive in multiplayer because each scan includes many more entities/chests in loaded chunks.

The new defaults (`96..128`) reduce worst-case scan fanout while still covering normal village footprints.

## Suggested presets

### TPS-first (busy multiplayer)

- `quartermasterScanRange = 96`
- `armorerFallbackScanRange = 96`
- `overflowRecipientScanRange = 64`
- `overflowFallbackQmSearchRadius = 96`

Use this when server tick time is unstable and villages are compact.

### Balanced (default)

- `quartermasterScanRange = 128`
- `armorerFallbackScanRange = 128`
- `overflowRecipientScanRange = 96`
- `overflowFallbackQmSearchRadius = 128`

Good baseline for most SMP worlds.

### Mega-village reach

- `quartermasterScanRange = 256` to `320`
- `armorerFallbackScanRange = 256` to `320`
- `overflowRecipientScanRange = 160` to `224`
- `overflowFallbackQmSearchRadius = 256` to `320`

Use only when villages are very spread out and you accept higher CPU cost.

## Validation and safety

Range settings are clamped when the mod initializes. Pathological values (negative, zero, or extreme) are automatically corrected to safe bounds.
