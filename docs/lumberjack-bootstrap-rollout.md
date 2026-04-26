# Lumberjack Bootstrap Rollout QA Checklist

## Failure handling matrix (coordinator-owned)

| Failure | Logging key | Retry delay | Max attempts | Terminal fail |
|---|---|---:|---:|---|
| No eligible unemployed found | `no_eligible_unemployed_found` | 600 ticks (30s) | 8 | No |
| No valid tree found | `no_valid_tree_found` | 400 ticks (20s) | 6 | Yes |
| Chop/path timeout | `chop_or_path_timeout` | 300 ticks (15s) | 5 | Yes |
| Insufficient wood for table | `insufficient_wood_for_table` | 500 ticks (25s) | 4 | Yes |
| Table placement blocked | `table_placement_blocked` | 200 ticks (10s) | 10 | Yes |
| Conversion failure | `conversion_failure` | 600 ticks (30s) | 3 | Yes |

Validation log shape:
- `lumberjack-bootstrap failure village=<...> villager=<...> failure_key=<...> result=<RETRY_SCHEDULED|TERMINAL|MISSING> retry_count=<...> max_attempts=<...> terminal_fail=<...> next_retry_tick=<...>`

---

## Scenario checklist

### 1) New village generation with natural villager load
- [ ] Fresh world or unexplored village chunk creates a bootstrap candidate.
- [ ] Coordinator logs candidate selection once per village scope.
- [ ] Villager performs one-tree bootstrap and reaches conversion.
- [ ] Lifecycle reaches `DONE` and candidate entry is removed.

### 2) Village with no nearby trees
- [ ] Selected villager receives `no_valid_tree_found` failure key.
- [ ] Retry delay and retry count advance per matrix.
- [ ] At configured max attempts, lifecycle transitions to terminal fail (`FAILED`).

### 3) Village with blocked table placement spaces
- [ ] Bootstrap chop completes but no valid placement is found.
- [ ] `table_placement_blocked` failure key is logged.
- [ ] Retries happen only after scheduled delay (no per-tick spam).
- [ ] Terminal fail occurs only when max attempts is reached.

### 4) Server restart during each bootstrap stage
- [ ] Restart during `SELECTED` preserves candidate UUID and village scope.
- [ ] Restart during `CHOPPING_ONE_TREE` preserves retry state.
- [ ] Restart during `NEEDS_TABLE` preserves placed-table metadata (if any).
- [ ] Restart during deferred retry preserves `next_retry_tick` and failure key.
- [ ] Restart during `READY_TO_CONVERT` resumes and allows conversion completion.

### 5) Multiplayer simultaneous chunk loads around same village
- [ ] Multiple chunk-load callbacks do not create multiple active candidates.
- [ ] Active lifecycle entry remains single-writer per village key.
- [ ] Retry/failure accounting is consistent even with concurrent player proximity scans.

### 6) Exactly-one promotion guarantee per bootstrap trigger
- [ ] One bootstrap trigger results in exactly one villager promoted.
- [ ] No duplicate lumberjack guards spawn for the same candidate/table.
- [ ] Job-site reservation remains associated with the converted guard.
- [ ] Candidate lifecycle is finalized after conversion (`DONE` + entry removed).
