# Improvement Plan: making the MOMoT exploration solve and repair Blocky programs

This document is a guide that anybody on the project can follow to apply, check and maintain the improvements to the MOMoT search. Each improvement has the same parts: why it is needed (the observation), what exactly to change and where, how to do it step by step, and how to check that it works without running a new benchmark.

Related documents: `Benchmark-Analysis.md` (every benchmark result and its limits), `Exploration-Proposal.md` (hypotheses and experiments), `Landscape-Analysis.md` (the enumeration measurements behind the relaxed gate), `AGENTS.md` (project conventions).

## 1. The ten improvements at a glance

| # | Improvement | Needed for | Evidence | State in the repository | Remaining work |
|---|---|---|---|---|---|
| 1 | Fix the `closestToGoal` objective | Progress towards the goal can guide the search | Bug found in step 1 (constant 100000 on levels 2–10). Alone: 12 → 16 of 120 solved (p = 0.54) | Done in `blocky_custom.java` | Make sure it is committed; verify (section 3.1) |
| 2 | Gated objectives (`Edits`, `Actions`, `Blocks` only count for goal-reaching candidates) | Not rewarding small programs that do nothing | **Measured:** 16 → 61 of 120 on levels 4–9 (p < 0.001), equal to random search | Done in `blocky_custom.java`, switch `blocky.objectives=GATED` | Make GATED the default outside Docker (section 3.2) |
| 3 | Edit and delete rules (`*_edit_anywhere`) | Repairing buggy programs | **Not measured.** Observed by the project owner with the original exploration | Generated `.henshin` files exist and the game uses them | Port the source to `.henshin_text`, test repair (section 3.3) |
| 4 | Wrap and unwrap rules (`*_wrap`) | Adding structure without destroying what a program already does | **Measured:** 39 → 56 of 100 on levels 6–10 (p < 0.001), 56 against 37 of 80 for random search on levels 6–9. Confounded, see below | Generated `.henshin` files exist, switch `blocky.rules.wrap=true` | Decide the rule set that ships (section 3.4) |
| 5 | Relaxed gate at `T = R − 5` | Letting near-solutions compete on size | **Landscape only.** No search has used it | **Not implemented** | Implement behind a switch (section 3.5) |
| 6 | Always show non-goal candidates in the MoMoT solution panel | Seeing how close the search got when it has not (yet) found a solution, and what it is working on | A usability request. Two separate causes hide them today (UI filter, and the Pareto front under gating) | **Implemented, GUI check pending** | Check in the app (section 3.6) |
| 7 | Fixed exploration parameters, hidden from the MoMoT panel | Users should not have to choose seeds, population, iterations, runs, solution length or algorithm | A usability request. Defaults sit between the old panel and the benchmark settings; the solution length is a constant 10 | **Implemented, compiles, GUI check pending** | Check in the app (section 3.7) |
| 8 | Remove the Execution log window of the game | Less clutter in the game UI | A usability request | **Implemented, compiles, GUI check pending** | Check in the app (section 3.8) |
| 9 | MoMoT panel: progress bar, elapsed time, log hidden behind a button | Seeing that a search runs and how far it is, without the log | A usability request. The data (`nfe`) was already delivered to the panel | **Implemented, compiles, GUI check pending** | Check in the app, in particular the run counter (section 3.9) |
| 10 | MoMoT panel: no Refresh, Load or Run buttons; a "Clear path" button; the search starts when a Direct Manipulation marker is placed | The panel refreshes itself, double-click already loads a row, and placing the marker is the only way to define the target | A usability request | **Implemented, compiles, GUI check pending** | Check in the app, in particular restarting by placing a second marker (section 3.10) |

**Two honest remarks before the details**

- **Items 1, 2 and 4 have search data; items 3 and 5 do not; item 6 is a usability change and needs no benchmark.** Item 3 is justified by an observation that no benchmark can show (every benchmark starts from an empty program, where there are no user blocks to edit or delete). Item 5 is justified only by counting programs, not by a search.
- **The benchmarked rule set is not the shipped rule set.** The wrap benchmark compared the original rules against `*_wrap.henshin`, which is built from the original rules. The game always loads `*_edit_anywhere.henshin`, so with wrap on it loads `*_edit_anywhere_wrap.henshin`. That module was only checked on small programs (`run.sh verify-wrap`); a search has never used it. Section 3.4 says what to do about this.

## 2. Starting point and how to work

- **Branch:** `proto/modify-delete-ops`. At the time of writing, several changes are still uncommitted (`blocky_custom.java`, `MomotFirstGoalBenchmarkRunner.java`, `BlockyUI.java`, the `*_wrap.henshin` files, `tools/`). Commit them before anything else, in the pieces of section 6.
- **Build:** `mvn clean compile` from the repository root.
- **Run the game:** `mvn -pl blocky_game javafx:run` from the repository root.
- **Never edit `src-gen/`.** Everything below avoids it on purpose: the overrides are in `blocky_custom.java` (hand-written), and the rule files are separate `.henshin` files. If a change ever needs a different metamodel, change `blocky.ecore` and ask for the code to be regenerated in Eclipse (see `AGENTS.md`).
- **Switches are JVM system properties.** The Docker entrypoint sets them from environment variables (`BLOCKY_OBJECTIVES`, `BLOCKY_WRAP`) through `JAVA_TOOL_OPTIONS`. Outside Docker, set `JAVA_TOOL_OPTIONS="-Dblocky.objectives=GATED -Dblocky.rules.wrap=true"` before starting the game.

| Property | Values | Default in code | Read by |
|---|---|---|---|
| `blocky.objectives` | `CURRENT`, `GATED` | `CURRENT` | `blocky_custom.java` |
| `blocky.rules.wrap` | `true`, `false` | `false` | `MomotFirstGoalBenchmarkRunner.withWrapMoves` (used by the game and the benchmark) |
| `blocky.rules.editAnywhere` | `true`, `false` | `false` | `MomotFirstGoalBenchmarkRunner.withWrapMoves`, benchmark only (the game always uses `_edit_anywhere`) |
| `blocky.algorithm` | `NSGA_II`, `MEMETIC_NSGA_II` (game), `RANDOM_SEARCH`, `IMMIGRANTS_NSGA_II` (benchmarks) | `NSGA_II` | `blocky_custom.java` |

## 3. The improvements

### 3.1 Fix `closestToGoal`

**Observation.** On levels 2–10 the `closestToGoal` objective returned 100000 for every candidate. NSGA-II therefore had nothing pointing it towards the goal.

**Cause.** The generated objective (in `src-gen`) reads the attribute `Cell.distanceToGoal`. That attribute is only filled in for the one input file that was known when the runner class loaded (the static `blocky.input`). For any other level every cell was unannotated, so the objective fell back to its penalty value.

**Change.** `blocky_momot/src/blocky_momot_runner/blocky_custom.java`, the override `_createObjectiveHelper_3`. It no longer reads the attribute. It computes the distance field per evaluation with `BlockySimulator.distanceToGoalOrPenalty(level, 100000)`, which gives the same value (the smallest maze distance to the goal over the cells the robot visits) for every input. Any exception returns 1000000.

```java
@Override
protected double _createObjectiveHelper_3(final TransformationSolution solution, final EGraph graph, final EObject root) {
    try {
        if (root instanceof Game game && !game.getLevels().isEmpty() && game.getLevels().get(0) != null) {
            return (double) BlockySimulator.distanceToGoalOrPenalty(game.getLevels().get(0), 100000);
        }
    } catch (Throwable t) {
        return 1000000.0;
    }
    return 1000000.0;
}
```

**How to do it from scratch.**
1. Open `blocky_custom.java` and find the objective helpers (`_createObjectiveHelper_1` is `Edits`, `_2` is `Actions`, `_3` is `closestToGoal`; the objective order is `[GoalReached, Edits, Actions, closestToGoal, Blocks]`).
2. Add the override above. Do not touch the generated class.
3. `mvn clean compile`.

**How to check it works (no benchmark).**
- Run one search on a level above 1 (for example the benchmark runner with one run, `BLOCKY_RUNS=1 BLOCKY_FROM_LEVEL=6 BLOCKY_TO_LEVEL=6`) and open the `objectives.pf` file in the run's output directory. The `closestToGoal` column must contain different values. If every row has 100000, the fix is not active.
- After the fix the value is 0 exactly for programs that reach the goal.

**Limit.** Alone it changes the solve count very little (12 → 16 of 120, p = 0.54), because the ungated size objectives pull towards small programs. It is a precondition for item 2, not an improvement by itself.

### 3.2 Gated objectives

**Observation.** With the original objectives the search keeps small programs that do nothing, because `Edits`, `Actions` and (later) `Blocks` reward being small even when the goal is not reached. All of NSGA-II's successes came in the first 31 of 100 generations, as if the population had collapsed onto small programs.

**Hypothesis (H2b) and result.** If those objectives only count for programs that reach the goal, the search stops preferring useless small programs. Measured: 16 → 61 of 120 solved on levels 4–9 (p < 0.001). The early stall disappeared. The gated search is equal to random search (61 against 61), not better.

**Change.** `blocky_custom.java`:
- `blocky.objectives=GATED` switches it on (`gatedObjectives()`).
- `reachesGoal(root)` runs the simulator and returns true if the status is `WON`.
- `_createObjectiveHelper_1` (`Edits`) and `_createObjectiveHelper_2` (`Actions`) return `GATED_WORST` (100000) for a candidate that does not reach the goal.
- `createFitnessFunction` adds a fifth objective `Blocks` (statement count, through `BlockyProgramMetrics.countStatements`), gated in the same way. It is added last so the positions of the existing objectives stay as the tools expect.
- `GoalReached` and `closestToGoal` are never gated.

**How to do it.** Nothing to implement: it is in the file. What is left is the default.
1. The code default is `CURRENT`. The Docker entrypoint already defaults to `GATED` (`entrypoint.sh`, `docker-compose.yml`). The game started with `mvn javafx:run` therefore runs the old objectives unless the property is set.
2. Change the default in `gatedObjectives()` to `"GATED"` (one word), or set `JAVA_TOOL_OPTIONS` in the way the project documents running the app. Changing the code default is simpler and cannot be forgotten.
3. The comment in `entrypoint.sh` still says `CURRENT (default)`; correct it to match.

**How to check it works.**
- Start a search with `-Dblocky.objectives=GATED`. In the log the objective list must read `[GoalReached, Edits, Actions, closestToGoal, Blocks]`.
- In `objectives.pf`, every candidate that does not reach the goal has 100000 in `Edits`, `Actions` and `Blocks`; only goal-reaching candidates have real values.

**Known risk, untested: repair.** In repair, the search starts from the user's program. Gating gives no reward for staying close to that program until the goal is reached, so the search may move away from it before it succeeds. No repair run has been made. See section 5.

### 3.3 Edit and delete rules (`*_edit_anywhere`)

**Observation.** The original rules insert blocks and have a few fixed delete rules (the four `Delete...` rules that this module replaces), but no move that changes a block in place. A buggy program therefore cannot be repaired by changing the wrong block; the search has to remove it and rebuild around it. The project owner saw this often with the original exploration. No benchmark covers it: all of them synthesize from an empty program.

**What the rules do.** The patcher builds, from each original rule module, a copy `*_edit_anywhere.henshin` that contains all original rules plus:
- `EditAnywhere(k, cnd)`: the single search move. It is an independent unit; Henshin tries its sub-units in random order until one applies, so no step is wasted on a move with nothing to act on (for example on an empty program). Its sub-units:
  - `CreateThenInsertContainerThenPopulate(k, cnd)`: the existing insert move;
  - `DeleteContainerAnywhere`: deletes a block, with rules `Delete<Content>At<Position>` for Content in {Empty, Atomic, EmptyLoop, EmptyIf, EmptyIfElse} and Position in {OnlyInBody, BodyHead, Between, Last}. Loops and ifs can be deleted once their bodies are empty;
  - `ModifyStatementAnywhere(k, cnd)`: `ChangeAtomicKind(k)` (set `AtomicStatement.kind`, only if it differs) and `ChangeIfCondition(cnd)` (set `IfStmt.condition`, only if it differs).
- Delete and modify only match **user-placed blocks** (`generated = false`). Changing or deleting blocks that the search inserted itself would add no reachable programs and only cancel insertions.
- The old delete rules (`DeleteOnlyContainerFromBody`, `DeleteHeadContainerWithNext`, `DeleteBetweenContainerWithNext`, `DeleteLastContainer`) are replaced.

**Where it is wired.**
- `tools/henshin-prototype/ModifyOpsPatcher.java` generates the files; `tools/henshin-prototype/VerifyPatchedRules.java` checks them.
- `blocky_custom.createModuleManager()`: if a module has a unit named `EditAnywhere`, that unit becomes the only move (all other units are removed from the manager) and its parameters `k` and `cnd` get random value providers. This is done in `blocky_custom` and not in `blocky.momot`, so `src-gen` does not need regenerating.
- `EnumParamPreprocessFitnessFunction.java`: also treats `EditAnywhere` as a top-level unit that takes `k` and `cnd`.
- `BlockyUI.java`: the game always selects the `*_edit_anywhere.henshin` file for the current level (`atomic_only`, `no_conds`, `no_else` or `henshin_text`).

**How to regenerate the rule files.**
1. Prerequisites: JDK 17+, the Henshin and EMF jars in `libs/` and the EMF jars in the local Maven repository (`~/.m2`, override with `M2_REPO`). `run.sh` builds the class path itself.
2. From the repository root: `tools/henshin-prototype/run.sh patch` writes `blocky_model/transformations/statement_insertions_{henshin_text,no_else,no_conds,atomic_only}_edit_anywhere.henshin`. The original files are not modified.
3. `tools/henshin-prototype/run.sh verify` applies the generated rules to small programs and checks the expected behaviour. All checks must pass.
4. The patcher serialises the metamodel reference as `http://www.example.org/blocky#//X`, as MOMoT expects, so the NSURI patch of `AGENTS.md` is not needed for these files. If you ever recompile from `.henshin_text` in Eclipse, patch the NSURI by hand as described there.

**How to check it works (no benchmark).**
- `run.sh verify` passes, including the check that on an empty program 15 of 15 `EditAnywhere` steps apply, and that on a user program `EditAnywhere` inserts, deletes and modifies.
- In the game, load a level, place a program with a wrong block (for example a wrong turn), and start the search. Solutions that fix the program by changing that block must appear. This is the failure the project owner observed; a few such hand checks are the available evidence until a repair benchmark exists.

**Gap to close.** The patcher edits the compiled `.henshin` modules; the textual source (`statement_insertions.henshin_text`) is **not** updated. `AGENTS.md` says rules are written in `.henshin_text` and compiled. Until the textual source gets the same rules, recompiling the text file in Eclipse silently loses the edit and wrap moves. Either port the rules to `.henshin_text` and compile, or state in `AGENTS.md` that these modules are generated by `tools/henshin-prototype` and must not be recompiled from the text.

### 3.4 Wrap and unwrap rules (`*_wrap`)

**Observation.** The existing rules insert and delete one block at a time. Turning `F F L` into `repeat { F F L }` means deleting the blocks and rebuilding them inside a loop. On levels that need a loop, most programs without one are far from every solution in block edits. The landscape analysis showed that when wrap and unwrap count as single edits, Progress becomes informative on levels 6, 7 and 9 (rank correlation about 0.3) and stays uninformative on levels 8 and 10 (0.08).

**What the rules do** (`tools/henshin-prototype/WrapOpsPatcher.java`):
- `WrapTailIn<Wrapper>At<Head|After>(cnd)`: a block and everything after it in the same body moves into a new loop, if or if-else that takes the block's place. At the first block this wraps the whole program.
- `Unwrap<Wrapper>At<Head|After>`: the reverse, for a loop, if or if-else that is the last block of its body. The else branch must be empty.
- `WrapAnywhere(cnd)`, `UnwrapAnywhere`, `Restructure(cnd)`: independent units over those rules.
- `EditAnywhere` is extended (or created, if the module has none) with `Restructure`, plus two alias units of the insert move, so insertion is chosen three times as often as restructuring. This weight is `INSERT_WEIGHT = 3` in the patcher.
- Wrappers are limited to the module's own vocabulary: it only wraps in what it can also insert. There is no wrap file for `atomic_only` (no loop or if to wrap in).

**Result.** Gated NSGA-II, levels 6–10, 20 seeds, original rules against `*_wrap.henshin`: 39 → 56 of 100 solved (p < 0.001). Levels 6, 7 and 9 together: 38 → 55 of 60. Levels 8 and 10 unchanged (1 of 40 in both). Median generation of the first goal falls from 31 to 13 on level 6, from 49.5 to 18 on level 7 and from 28 to 17 on level 9. Against random search on levels 6–9: 56 against 37 of 80.

**Two caveats that decide what to do next.**
1. **Confound.** `*_wrap.henshin` is built from the original module, which has no `EditAnywhere`, so the patcher creates one from insert (weighted ×3) and Restructure only. In `blocky_custom` a module with `EditAnywhere` uses only that unit, so the separate `DeleteContainerAnywhere` move is gone and inserts are re-weighted. The measured gain is "the `_wrap` rule set against the original", not "wrap alone".
2. **Rule set mismatch.** The game loads `*_edit_anywhere.henshin`; with wrap on it loads `*_edit_anywhere_wrap.henshin`, where delete and modify are present. That module differs from the benchmarked one. Both contain the same wrap and unwrap rules, so a similar effect is plausible, but it has not been measured.

**How to regenerate.** `tools/henshin-prototype/run.sh wrap` writes `*_wrap.henshin` for the three originals and for the three `*_edit_anywhere` modules. Run `run.sh patch` first, because the wrap step reads the `_edit_anywhere` files. `tools/henshin-prototype/run.sh verify-wrap` applies the rules to small programs and checks the outcome (for example that `EditAnywhere` on `[F, Loop[L]]` produces inserts, wraps and unwraps). All checks must pass.

**How to switch it on.** `-Dblocky.rules.wrap=true` (Docker: `BLOCKY_WRAP=true`, the default there). `MomotFirstGoalBenchmarkRunner.withWrapMoves` appends `_wrap` to the file name that the game or the benchmark chose. Switching it off, or deleting the `_wrap` files, restores the previous behaviour.

**What to do about the mismatch** (pick one and write the choice into this section):
- **Ship `_edit_anywhere_wrap`** (the current game behaviour) and say in the release notes that the measured gain is for a related rule set. This is the cheapest option and matches the aim (repair needs delete and modify).
- **Check `_edit_anywhere_wrap` against `_edit_anywhere` once** with the existing benchmark script: set `-Dblocky.rules.editAnywhere=true` and run it with `BLOCKY_WRAP=false` and `true` on levels 6–10 (the switch exists in `MomotFirstGoalBenchmarkRunner`, but the run script does not pass it on yet; add it next to `-Dblocky.rules.wrap`). The project owner decided not to run another benchmark for now, so this stays optional.

**Limit.** The wrap rules are modelled as string edits in `LandscapeAnalysis` and have been exercised by Henshin only on small programs (`verify-wrap`) and in the benchmark runs above. A fault in an unusual program shape is possible.

### 3.5 Relaxed gate at `T = R − 5` (new, not yet implemented)

**Observation.** The strict gate gives `Edits`, `Actions` and `Blocks` a value only for programs that reach the goal. Before any solution exists, only Progress differs between candidates. A relaxed gate would let candidates close to the goal already compete on size, so the search is also pushed towards small programs among near-solutions.

**Definition.** Let `R` be the maze distance from the start cell to the goal (the length of the shortest route; R is 16 on levels 6 and 8, 12 on level 7, 10 on levels 9 and 10). Progress is the smallest maze distance to the goal over the visited cells, so it runs from `R` (the robot never leaves the start) down to 0 (the goal is reached). A gate at `T` admits a program if its Progress is at most `T`; solutions have Progress 0 and always pass. The strict gate is `T = 0`. The relaxed gate is `T = max(0, R − 5)`.

**Evidence (landscape only, `Landscape-Analysis.md` section 8).**
- `T = 3` is the strict gate in practice: at most 37 non-solutions out of millions reach Progress ≤ 3.
- The number of admitted programs does not grow smoothly with `T`; it jumps by a factor of 35 to 1,700 in one step, and purity (the share of solutions among all that pass) drops by a factor of 30–120. The jump is at `T = R − 4` on levels 6 and 7, `R − 2` on levels 8 and 10, and `R − 1` on level 9.
- `T = R − 5` is below the jump on all five levels. It is the largest value of the form `R − c` with that property that uses the same `c` everywhere. It is an empirical rule fitted to five levels, not a derived one.
- What it admits depends on the level:

| Level | R | Non-solutions admitted at `R − 5` | Reading |
|---:|---:|---:|---|
| 6 | 16 | 83 | With wrap they are closer to a solution than the failing programs (2.55 against 4.25 edits); without wrap they are not |
| 7 | 12 | 2,010 | As many as there are solutions; 96% are within 2 edits of a solution. The one level where it admits a useful set |
| 8 | 16 | 283 | Closer than the failing ones (4.9 against 9.3 edits); about 2% are smaller than the smallest solution |
| 9 | 10 | 16 | Half are within 2 edits |
| 10 | 10 | 0 | Nothing passes; at `T = 7`, 320 pass but are not close to a solution |

**Expected effect, stated honestly.** On four of the five levels the relaxed gate admits between 0 and 283 programs out of 1–5 million, and the search draws 150 candidates per generation, so it will rarely meet them. A visible effect is plausible on level 7 only, and level 10 is untouched. The risk it adds is on levels 8 and 10, where about 2% of the admitted programs are smaller than the smallest solution (the tiny-program problem that gating removed). Do not expect it to change the solve counts much; do not claim that it does until a search has used it.

**Design (not in the repository yet).** Keep the strict gate as the default so nothing else changes.

1. **Helper for `R`.** Add to `blocky_momot/src/blocky_momot/BlockySimulator.java`:

```java
/** Maze distance from the start cell to the win cell; -1 if there is none. */
public static int routeLength(Level level) {
    // same start-cell and win-cell lookup as distanceToGoalOrPenalty, then:
    //   Map<Cell,Integer> field = computeDistanceField(map, determineWinCellType(level));
    //   Integer r = field.get(startCell);  return r == null ? -1 : r;
}
```

2. **Switch.** A new property `blocky.gate.slack` (an integer; unset means the strict gate). With `slack = 5` the threshold is `T = max(0, R − 5)`.
3. **Gate in `blocky_custom.java`.** Replace the three uses of `!reachesGoal(root)` (in `_createObjectiveHelper_1`, `_createObjectiveHelper_2` and the `Blocks` objective) by `!passesGate(root)`:

```java
private static final Integer GATE_SLACK = Integer.getInteger("blocky.gate.slack");   // null = strict

private static boolean passesGate(final EObject root) {
    if (reachesGoal(root)) return true;
    if (GATE_SLACK == null) return false;                       // strict gate, as today
    Level level = ((Game) root).getLevels().get(0);
    int r = BlockySimulator.routeLength(level);
    if (r < 0) return false;
    int progress = BlockySimulator.distanceToGoalOrPenalty(level, 100000);
    return progress <= Math.max(0, r - GATE_SLACK);
}
```

   The penalty value 100000 is far above any threshold, so invalid programs never pass.
4. **Cost.** The gate runs one more simulation per objective per evaluation. If runs get noticeably slower, compute `passesGate` once per candidate and share it between the three objectives.
5. **Compile.** `mvn clean compile`, nothing else needs regenerating.

**How to check it works (no benchmark).**
- With `blocky.gate.slack` unset, results must be identical to today's strict gate (same `objectives.pf` on one seed).
- With `slack = 5` on level 7 (R = 12, so `T = 7`): a candidate that never leaves the start must still get 100000, while a candidate that gets within 7 cells of the goal must get real `Edits`, `Actions` and `Blocks`. A short unit check on the helpers (`routeLength` returns 16, 12, 16, 10, 10 on levels 6–10, the R values of the landscape analysis) is cheap and catches wiring mistakes.
- Optionally, count how many candidates of one run pass the relaxed gate against the strict one; the landscape tables predict very few on levels 6, 8, 9 and 10.

**What would settle it.** A benchmark of the relaxed gate against the strict gate on levels 6–10, same seeds and budget (the strict gate result is in `Benchmark-Analysis.md`). It has not been run. Until then ship it off, or on for level 7 only if the project owner wants to try it.

### 3.6 Always show non-goal candidates in the MoMoT solution panel

**Observation.** The solution panel shows only candidates that reach the goal. Candidates that do not reach it are hidden, so when a run has not (yet) found a solution the panel is empty, and the user cannot see how close the search got or what it is working on.

**There are two separate causes, and both have to be fixed.** Fixing only the first leaves the panel nearly empty as soon as the gated objectives (item 2) are on.

**Cause A: a filter in the panel (`BlockyUI.java`, injected JavaScript).**
- A checkbox `Show non-goal solutions` (`__momotShowNonGoal`) is created with `cbNonGoal.checked = false`, so non-goal candidates are hidden by default (around lines 753–761).
- `renderSolutions` reads it into `showNonGoal` (around line 862) and, when it is off, keeps only candidates whose first objective is `GoalReached ≤ −0.5` (`displayed = processed.filter(p => p.isGoal)`, around line 921).
- The status line and an empty-state row mention "non-goal hidden" (around lines 946–956 and 986–990). The refresh key includes `showNonGoal` (line 865).

**Cause B: the data the panel gets does not contain them under gating.**
- The panel lists what is in the run's output directory (`MomotResultsService.loadFromOutputDir`: the files in `models/`, joined to `objectives.pf`, `times.pf`, `generations.pf` and `solutions.txt`). While a search runs it is also fed with the Pareto front (`ParetoFrontPublisherListener`, `globalParetoFront`, a `NondominatedPopulation`).
- A Pareto front only keeps non-dominated candidates. With the original objectives, a non-goal candidate can be non-dominated because it is small (few `Edits`, few `Actions`). With the gated objectives (item 2) every non-goal candidate has 100000 in `Edits`, `Actions` and `Blocks` and a `closestToGoal` above 0, so **every goal-reaching candidate dominates every non-goal candidate**. As soon as one solution exists, the non-goal candidates leave the front, and before that only the one with the best `closestToGoal` is on it. Turning the checkbox on does not bring them back.
- The listener also drops candidates with the same objective values as one already present (`haveSameObjectives`), so under gating at most one non-goal candidate per distinct `closestToGoal` value can appear.
- Also note that `algorithm.getResult()`, which the listener reads, is the algorithm's non-dominated result and not the whole population.

**Change A: show them always (UI only, `BlockyUI.java`).**
1. Remove the checkbox: delete the `cbLabel` / `cbNonGoal` / `cbSpan` lines and append only the load button to `actions`.
2. In `renderSolutions`, set `var showNonGoal = true;` (or delete the variable and the filter branch). `displayed` is then always `processed`. Remove the `showNonGoal` part of `rawJson` and the empty-state row ("No goal-reaching solutions yet ...").
3. Replace the status text with one line, for example `totalCount + ' candidate(s), ' + goalCount + ' reaching the goal' + goalInfo`.
4. Make the table readable with mixed rows: sort by the `Goal Reached` column by default (set the initial `__momotSortCol` to 0, ascending, so goal-reaching candidates come first, since `GoalReached` is printed negated), show `-` instead of 100000 in the `Edits` and `Number of Actions` columns (the gate penalty is not a real value), and dim the rows that do not reach the goal. The `Number of blocks` column already counts the blocks from the model itself (`blockCountOf`), so it is correct for non-goal rows.

**Change B: keep non-goal candidates in the output (needed under gating). Implemented.** A small archive of non-goal candidates is kept next to the Pareto front and written out with it.

- **Archive** (`ParetoFrontPublisherListener.updateNonGoalArchive`): on every progress update, the non-goal candidates of the algorithm's whole population (read through `NSGAII.getPopulation()`) that came closest to the goal are considered, closest first. At most `K = blocky.nonGoalArchive` are kept, ranked by `closestToGoal` and then by block count. It only records candidates; it never feeds back into selection or the objectives.
- **Display values instead of the gate penalty.** A non-goal candidate has 100000 in `Edits`, `Actions` and `Blocks`, so near-misses with the same `closestToGoal` would have the same objective vector, and therefore the same model file name (`blocky_custom_<objective values>.xmi`, no index) and the same line in the panel's join. The archive therefore stores a **copy** (`TransformationSolution.copy()`) of each candidate whose `Edits` (`BlockyProgramDistance.distanceToBaseline`), `Actions` (`BlockySimulator.simulationSteps`) and `Blocks` (`BlockyProgramMetrics.countStatements`) are the real values, computed once after the fact from the executed model. The originals in the population are never changed. Candidates with identical display vectors are still skipped, so two near-misses collide only if all of `Edits`, `Actions`, `Blocks` and `closestToGoal` agree.
- **Output** (`blocky_custom.java`, `withNonGoalArchive`): the live save (`saveLiveResults`) and the final save (`handleResults`) write the front plus the archive into `models/`, `objectives.pf`, `times.pf` and `generations.pf`. The archive entries come last, so the position-based join with `solutions.txt` stays aligned for the front. The `solutions.txt` / `solutions/` files are not changed, so archived rows have no text summary.
- **Switch:** `blocky.nonGoalArchive` (integer `K`, 0 = off). The default in code is 0, so the benchmark runners (own `main`) and all other tools are unchanged. `Main.java` (the game's entry point) sets it to 10 when it is not already set; `-Dblocky.nonGoalArchive=0` turns it off in the game.
- **Panel sort** (`BlockyUI.java`): ties on `Goal Reached` are broken by `Closest to Goal` and then by block count, so the closest near-miss is listed first.

**How it was checked.** One 15-generation run on level 10 (`blocky.objectives=GATED`, wrap on, not solved), started through `MomotFirstGoalBenchmarkRunner` with `-Dblocky.nonGoalArchive=10`, then read with `MomotResultsService.loadFromOutputDir`, the function the panel uses:
- 11 entries: the 1 near-miss of the Pareto front (with the 100000 penalties) and 10 archived candidates with real values, for example `-0.0 25.0 5.0 8.0 25.0`. The 11 lines of `objectives.pf` match the 11 model files one to one, all names distinct, each entry with its own generation and time.
- With `-Dblocky.nonGoalArchive=0`: 1 line and 1 model, as before the change.
- Wall time for the same run: 13.09 s with the archive off, 13.63 s on (one run each, so only an indication of a small cost).

**Not checked yet.** (1) The GUI itself: the panel list, loading an archived row (double-click), and a run that finds a solution (the archived rows must stay listed after it). (2) That an archived program, when loaded, reaches the same end cell as the candidate it was copied from. `copy()` keeps the transformation sequence, and the model files were written without errors, but the loaded program was not compared.

**Known limits.**
- The front's own near-miss (with the 100000 values) is still listed, and the archive may hold the same program with real values, so one program can appear twice.
- The archive holds one program per display vector, not every program that reaches a given distance.
- Archived rows have no text summary (`solutions.txt` is written from the front only).

**How to check it works in the app.**
- Start a search on a level it cannot solve (level 10): the panel must list near-miss candidates ordered by `Closest to Goal`, with real `Edits`, `Number of Actions` and block counts.
- Start a search that finds a solution under `blocky.objectives=GATED`: the solution is listed first and the near-misses stay listed after it.
- Double-click a near-miss row: the program must load into the game, and running it must show where the robot ends up.
- Run one level of the benchmark script: `objectives.pf` and the success counts must be unchanged (the archive is off there).

**Risks.**
- **Clutter and confusion.** A list of failed programs next to solutions can be mistaken for results. The sorting, the dimming and the `-` values above are there for that reason; keep the status line explicit about how many reach the goal.
- **Cost.** Ranking the population and writing up to `K` extra model files on every live update. Keep `K` small and write non-goal models only when the archive changed.
- **Repair.** In repair, a non-goal candidate near the user's program may be the most useful thing to show. This is a reason to keep the archive ranking simple and visible, not a reason to hide them.

### 3.7 Fixed exploration parameters, hidden from the MoMoT panel

**Observation.** The MoMoT panel showed editable fields for Seed, Pop, Iter, Runs, SolLen and an Alg dropdown. A user has to know what they mean, and a wrong value silently makes a level unsolvable: the panel's SolLen of 10 is below the minimum solution length of levels 4 (11), 8 (12) and 10 (38), and level 6 (10) has no slack. In addition, `showMomotPanelOnly` overwrote SolLen with 2 × the length of the user's current program (`BlockyProgramMetrics.inferSolutionLength`), so the shown default was not a fixed number either.

**Decision (project owner).** Give every parameter a default and remove the fields from the panel. The algorithm is fixed to NSGA-II and its dropdown is removed too.

| Parameter | Before (panel default) | Now (fixed) | Note |
|---|---|---|---|
| Seed | 0 | 0 (auto) | A fixed seed would repeat the same search on every click |
| Pop | 50 | 100 | The benchmark uses 150 |
| Iter | 40 | 100 | Evaluations = Pop × Iter = 10,000 per run (was 2,000). The benchmark also uses 100 |
| Runs | 10 | 8 | Total cost per click is about 80,000 evaluations, about 4 × the old 20,000 |
| SolLen | 10 (then overwritten with 2 × program length) | 10 (constant, project owner's decision) | A per-level table (`2, 8, 2, 11, 8, 10, 8, 12, 8, 38`, as in `MomotFirstGoalBenchmarkRunner.CANONICAL_MIN_SOLUTION_LENGTHS`) was implemented first and then replaced by the constant. **Known limit:** 10 is below the minimum for levels 4 (11), 8 (12) and 10 (38), so those cannot be solved from the panel; level 6 (10) has no slack |
| Alg | NSGA-II (dropdown also offered Memetic) | NSGA-II | Memetic is still reachable with `-Dblocky.algorithm=MEMETIC_NSGA_II` |

**What to change (`blocky_game/src/blocky_game/BlockyUI.java`).**
1. In the injected panel script, delete the creation of `labSeed`/`inpSeed`, `labPop`/`inpPop`, `labIter`/`inpIter`, `labRuns`/`inpRuns`, `labSolLen`/`inpSolLen`, `labAlg`/`selAlg` and the matching `settings.appendChild(...)` lines. Only the Run/Stop button container stays in `__momotSettings`.
2. In the Run button handler, replace the reads of those inputs with constants: `s = 0`, `p = 100`, `it = 100`, `e = p * it`, `r = 8`, `sl = 10`, `alg = 'NSGA_II'`. The rest of the handler (`setMomotAlgorithm`, `runMomotWithParams(s, p, e, r, sl)`) is unchanged.
3. In `showMomotPanelOnly`, delete the SolLen inference (`defSolLen`, `inferSolutionLength`) and the script that set `__momotInpSolLen`. Keep `window.__momotShowAndRefresh()` and set the status text to `MoMoT panel ready. Place a Direct Manipulation marker to start.` (section 3.10 changed the text from `Click Run.`)

**To change a default later:** edit the constants in the Run handler. The benchmarks were run with `CANONICAL_MIN_SOLUTION_LENGTHS`, not with a constant, so their results do not transfer to levels where 10 is below that minimum.

**Trade-offs.**
- The new Pop, Iter and Runs make a click about 4 × slower than before. If that is too slow, lower `p`, `it` or `r` first.
- Removing the 2 × program length rule drops a dynamic default. It may have helped when the user starts from their own, partly correct program (direct manipulation); a constant 10 is also fixed and was not measured there.
- Pop 100, Iter 100 and Runs 8 are not benchmarked as a set; they sit between the old panel values and the benchmark values.
- Level 10 stays unsolved by the search whatever the parameters (see `Benchmark-Analysis.md`); this change does not address that.

**How it was checked.** `mvn -q -pl blocky_game compile` passes. The app was **not** run.

**How to check it works in the app.**
- Open the MoMoT panel: no Seed, Pop, Iter, Runs, SolLen or Alg fields; only Stop in the button row (Run is hidden by section 3.10; section 3.10 also removed Refresh and Load).
- Press Run on any level: the status line reads `Starting MoMoT (alg=NSGA_II, seed=0, pop=100, iter=100 (eval=10000), runs=8, solLen=10)...`.
- The console shows `[JSBridge] runMomotWithParams seed=0 pop=100 eval=10000 runs=8 solLen=10`.
- The panel still lists solutions and loading a row (double-click, section 3.10) still works (nothing else reads the removed fields; a search of the source for `__momotInp` and `__momotSelAlg` finds nothing).

### 3.8 Remove the execution log window

**Request (project owner).** Remove the "Execution log" window from the UI.

**What it was.** A draggable, resizable panel (`__execLogPanel`, title `Execution log`, a Clear button) built inside the Blockly host by the injected script in `BlockyUI.java`. It showed the step-by-step trace of a program run and the immediate-feedback notes.

**What to change (`BlockyUI.java`).** Replace the whole function `__execLogEnsure` (panel creation, drag and resize handling, and the definitions of `window.__execLogClear` / `window.__execLogAppend`, about 180 lines) with a stub that only defines the two functions as no-ops and returns `true`. Do **not** delete the callers: about 20 places (`__execLogAppend` / `__execLogClear` in the run, step and feedback code, and the two `executeScript` calls in the Java methods that clear and append logs) are guarded and now do nothing. Do not put a `//` comment inside the injected JavaScript string: the string parts are joined without newlines, so it would comment out the rest of the script. Use a Java comment.

**Not changed.** The Java side still computes the log lines; only their display is gone. Console output (`System.out`) is unchanged.

**How it was checked.** `mvn -q -pl blocky_game compile` passes, and a search finds no `__execLogPanel` or `__execLogBody` left. The app was **not** run.

**How to check it works in the app.** The Execution log window is no longer shown. Run a program and step through it: the robot still moves, and the MoMoT panel still opens. No JavaScript errors appear in the console.

### 3.9 MoMoT panel: progress bar, elapsed time and a hidden log

**Original idea.** The MoMoT panel (section 3.7) was being simplified: the exploration parameters were fixed and hidden, and the Execution log window of the game was removed (section 3.8). The panel also has its own log (`__momotLog`, a text box under the table with the console output of the run). The project owner was tempted to remove that log too, because it is technical noise for a user who only wants to see solutions. The log is, however, the only place that shows that a search is running and how far it is: the table can stay empty for a while at the start, and a run takes minutes (8 runs of 10,000 evaluations with the defaults of section 3.7).

**Observation.** The panel needs a progress indication before the log can go. The data is already there: the live subscriber in `startMomotWithParams` is called by `ParetoFrontPublisherListener` about every 400 ms with the evaluation count `nfe` of the current run (`event.getCurrentNFE()`), and already refreshes the table at most every 250 ms. Nothing used `nfe`.

**Decision (project owner).**
1. Do **not** remove the log: hide it by default and toggle it with a button, so errors and run output stay reachable.
2. Add a progress bar with the run number, the generation, the percentage and the elapsed time.
3. Do **not** change the solution table (columns, sorting, rendering): it is considered good as it is.

**How to achieve it (`blocky_game/src/blocky_game/BlockyUI.java`).** All JavaScript below is inside Java string literals of the panel script, joined without newlines. **Never put a `//` comment inside those strings**: it would comment out the rest of the script. Use single quotes in the JavaScript, and no non-ASCII characters (the progress text uses ` | ` as a separator for that reason).

*Step 1: the toggle button.* Where the header buttons are built (`right.appendChild(...)`), create a button with the existing `mkBtn` helper and add it to the header (it was first added before the Refresh button; section 3.10 removed that button):

```java
+ "        var logToggleBtn = mkBtn('__momotLogToggleBtn', 'Show log', 'Show or hide the MoMoT log'); "
+ "        right.appendChild(logToggleBtn); "
```

*Step 2: hide the log and wire the button.* Where `log` is created (`log.textContent = ''`), add:

```java
+ "        log.style.display = 'none'; "
+ "        logToggleBtn.addEventListener('click', function() { "
+ "          var show = (log.style.display === 'none'); "
+ "          log.style.display = show ? 'block' : 'none'; "
+ "          logToggleBtn.textContent = show ? 'Hide log' : 'Show log'; "
+ "          if (show) { log.scrollTop = log.scrollHeight + 1000; } "
+ "        }); "
```

*Step 3: the progress element.* Create a block `prog` (hidden until a run starts) with a bar (`progBar` containing `progFill`) and a text line (`progText`), and append it between the status line and the log: `body.appendChild(list); body.appendChild(status); body.appendChild(prog); body.appendChild(log);` (the `actions` row that held the Load button was removed in section 3.10). `progFill` has `width: 0%`, a green background and `transition: width 0.3s`. The table (`list`) and its code are not touched.

*Step 4: the three JavaScript hooks.* Next to `window.__momotSetStatus = setStatus;` define:
- `window.__momotProgressStart(runs, gens)`: stores the start time, resets the state to run 1, generation 0, 0 %, shows `prog`, and starts `setInterval(renderProgress, 1000)` so the elapsed time ticks.
- `window.__momotSetProgress(run, runs, gen, gens, pct)`: stores the values and renders.
- `window.__momotProgressDone()`: stops the timer and appends ` | ended` to the text.
- `renderProgress()` sets the fill width to `pct` and the text to `Run r/R | generation g/G | P% | mm:ss`.

*Step 5: start the progress when Run is pressed.* In `startMomotWithParams`, in the first `executeScript` (next to `__momotLogClear`), add:

```java
"  if (window.__momotProgressStart) window.__momotProgressStart(" + nrRuns + ", "
        + Math.max(1, maxEvaluations / Math.max(1, populationSize)) + ");" +
```

*Step 6: feed it from the live subscriber.* Before the subscriber, keep two counters. In the subscriber, update them on every call (before the 250 ms throttle) and send the progress together with the table refresh:

```java
final AtomicInteger currentRun = new AtomicInteger(1);
final AtomicInteger lastNfe = new AtomicInteger(-1);
final int evalsPerRun = Math.max(1, maxEvaluations);
final int generationsPerRun = Math.max(1, maxEvaluations / Math.max(1, populationSize));
// in the subscriber:
int n = nfe == null ? 0 : Math.max(0, nfe);
int prev = lastNfe.getAndSet(n);
if (prev >= 0 && n < prev && currentRun.get() < nrRuns) currentRun.incrementAndGet();
int gen = Math.min(generationsPerRun, (n + populationSize - 1) / populationSize);
double pct = Math.min(100.0, 100.0 * ((run - 1) * (double) evalsPerRun + Math.min(n, evalsPerRun))
        / ((double) nrRuns * evalsPerRun));
// inside the existing Platform.runLater, before __momotShowAndRefresh:
// window.__momotSetProgress(run, nrRuns, gen, generationsPerRun, pct)   (pct formatted with Locale.ROOT, one decimal)
```

*Step 7: finish.* In the finish callback of `MomotRunService.runAsync` (the `Runnable` that already calls `__momotShowAndRefresh`), call `window.__momotProgressDone()` first.

*Step 8: build and check.* `mvn -q -pl blocky_game compile`, then the in-app checks below.

**Assumption to verify.** `nfe` is counted per run, so the run number is detected by `nfe` dropping below its previous value. If a run's first reported `nfe` happened to be higher than the previous run's last one, the run counter would lag by one until the next drop. Check in the app that the counter reaches `8/8`. If it does not, the listener should publish the run index itself (`ParetoFrontPublisherListener` knows when a seed finishes: `isSeedFinished`), instead of inferring it.

**Known limits.**
- Updates arrive at most about every 400 ms (listener throttle) and are only sent when the Pareto front or the archive is non-empty, so the bar can look idle at the very start of a run.
- Pressing Stop ends the run early: the bar freezes and shows `ended`, without reaching 100 %.
- The table, its columns and its sorting are unchanged.

**How it was checked.** `mvn -q -pl blocky_game compile` passes. The app was **not** run.

**How to check it works in the app.** Press Run: the bar and `Run k/8 | generation g/100 | % | mm:ss` appear and the elapsed time ticks every second. The log is hidden; `Show log` reveals it with the run output and `Hide log` hides it again. The table updates as before. At the end the text shows `ended`.

### 3.10 MoMoT panel: no Refresh, Load or Run buttons, a Clear path button, and the search starts when the marker is placed

**Observation.** After sections 3.7 and 3.9 the panel header still had buttons that did nothing a user needs to do by hand:
- **Refresh** reloaded the table, but the table already refreshes itself: at most every 250 ms during a run (the live subscriber in `startMomotWithParams`), when a run ends (the finish callback of `MomotRunService.runAsync` runs in a `finally`, so Stop is included), at run start, on page load, and after a Direct Manipulation click.
- **Load** loaded the selected row. Double-clicking a row already calls the same bridge method, `loadMomotSolution(modelPath)`.
- **Run** needed a second click after the Direct Manipulation click. The marker (the cell the user clicks) defines the target of the search, so a search without a new marker has no reason to start.

A manual Refresh also cleared two overlays on the maze (the comparison path and the `dmgMarker` element); the automatic refresh does not.

**Decision (project owner).**
1. Remove Refresh and Load; keep double-click as the way to load a row.
2. Give the two overlays their own button, **Clear path**.
3. Start the search automatically when a Direct Manipulation click is accepted. A click on a wall or other invalid cell still does nothing. Hide the Run button; Stop stays. Placing a second marker while a search runs stops the old search and starts a new one.
4. Do **not** shorten the search: it still runs its whole budget (section 3.7). The grace period after the first goal that was considered for this was rejected, because the full run is what improves `Edits`, `Actions` and `Blocks`.

**How to achieve it (`blocky_game/src/blocky_game/BlockyUI.java`).** The same rules as section 3.9 apply to the JavaScript strings: no `//` comments inside them, single quotes, no non-ASCII characters.

*Step 1: remove Refresh.* Delete these three lines of the panel script: the creation `var refreshBtn = mkBtn('__momotRefreshBtn', 'Refresh', ...)`, `right.appendChild(refreshBtn);` and `refreshBtn.addEventListener('click', function(){ refresh(); });`. Keep the function `refresh(isSilent)` and `window.__momotShowAndRefresh`: the automatic refresh calls them.

*Step 2: add Clear path.* Next to `logToggleBtn`, create and append the button, and after the `window.__momotShowAndRefresh = ...` line add its listener (this is the body of the non-silent branch of `refresh`, which the old Refresh button used to run):

```java
+ "        var clearOverlayBtn = mkBtn('__momotClearOverlayBtn', 'Clear path', 'Clear the comparison path and the marker from the maze'); "
+ "        right.appendChild(logToggleBtn); "
+ "        right.appendChild(clearOverlayBtn); "
// after window.__momotShowAndRefresh = ...
+ "        clearOverlayBtn.addEventListener('click', function(){ "
+ "          try { if (window.__dbgDrawComparisonPath) window.__dbgDrawComparisonPath([]); } catch(eC) {} "
+ "          try { "
+ "            var oldMarker = document.getElementById('dmgMarker'); "
+ "            if (oldMarker && oldMarker.parentNode) oldMarker.parentNode.removeChild(oldMarker); "
+ "          } catch(eM) {} "
+ "        }); "
```

*Step 3: remove Load.* Delete the `actions` row and what is in it: the four lines that create `actions` (a `div` with id `__momotActions`, its style line), `loadBtn` (`mkBtn('__momotLoadBtn', 'Load', ...)`) and `actions.appendChild(loadBtn);`; change `body.appendChild(list); body.appendChild(actions); body.appendChild(status);` to `body.appendChild(list); body.appendChild(status);`; and delete the whole `loadBtn.addEventListener('click', ...)` block. In the row `click` handler, make the status say how to load: `setStatus('Selected: ' + p.modelName + ' (double-click to load)');`. The `dblclick` handler is not touched.

*Step 4: one function that starts the search.* In the panel script, the Run button handler `mRunBtn.addEventListener('click', function(){ ... });` becomes a named function that the button and the Direct Manipulation click both call. Change only its first and last line:

```java
// first line, was: mRunBtn.addEventListener('click', function(){
+ "        window.__momotStartRun = function(){ "
// ... the body is unchanged (restore the pegman, reset the first-goal state, s=0, p=100, it=100, e=p*it, r=8, sl=10, alg='NSGA_II', runMomotWithParams) ...
// last line, was: }); 
+ "        }; "
+ "        mRunBtn.addEventListener('click', window.__momotStartRun); "
```

Hide the button right after it is created: `mRunBtn.style.display = 'none';`.

In the Direct Manipulation click handler (the code that calls `bridge.teleportPegman(col, row, t)`), call the function after the panel refresh and before `__dmStop()`. It sits after the checks that reject cells whose value is not 1 or 3, so only an accepted cell starts a search:

```java
+ "                try { if (window.__momotShowAndRefresh) window.__momotShowAndRefresh(); } catch(e4b) {} "
+ "                try { if (window.__momotStartRun) window.__momotStartRun(); } catch(e4c) {} "
+ "                __dmStop(); "
```

*Step 5: ignore the late callbacks of a replaced run.* `MomotRunService.runAsync` already interrupts the running search before it starts a new one, and `runInternal` takes a lock, so two searches never overlap. What is missing is the old run's callbacks, which fire after the old thread ends, when the new run is already going: the finish callback would call `__momotProgressDone()` and the new run's bar would say `ended`. Give every run an id and let the callbacks of an older run do nothing:

```java
// field of BlockyUI
private final java.util.concurrent.atomic.AtomicInteger momotRunId = new java.util.concurrent.atomic.AtomicInteger(0);
// first line of startMomotWithParams
final int myRunId = momotRunId.incrementAndGet();
// first line of the live subscriber (the BiConsumer)
if (momotRunId.get() != myRunId) return;
// first line of the finish callback (the Runnable passed to runAsync)
if (momotRunId.get() != myRunId) return;
// the output folder callback
if (momotRunId.get() == myRunId && finalOutDir != null && !finalOutDir.trim().isEmpty()) { ...
```

Stop alone does not change the id, so the finish callback of a stopped run still runs and the table and bar are finished as before.

*Step 6: the status texts.* Two places said `Click Run`: the first status text of the panel (`status.textContent = ...`) and the script in `showMomotPanelOnly`. Use `Place a Direct Manipulation marker to start the search.` and `MoMoT panel ready. Place a Direct Manipulation marker to start.`

*Step 7: build.* `mvn -q -pl blocky_game compile`. With Docker, `run-game.bat` mounts the repository, so restarting it is enough; if an old build is mounted, use `docker compose up -d --build`.

**What stays the same.** The search itself: population 100, 100 iterations, 8 runs, solution length 10 and no early stop (section 3.7). The pegman is still moved back to its position from before the marker (`__preDmQ`, saved when Direct Manipulation starts) when the search starts, and the marker stays on the maze.

**Known limits.**
- Every accepted marker starts a full search (8 runs of 10,000 evaluations). A marker placed by mistake is handled with Stop, or by placing the right marker, which restarts the search.
- Loading a row is only possible with a double-click; a single click selects it and draws its comparison path. The status line says so.
- The Run button still exists (hidden) because the shared function is registered on it; removing the element means removing the line `mRunBtn.addEventListener(...)` too.

**How it was checked.** `mvn -q -pl blocky_game compile` passes. A search of the source finds no `refreshBtn`, `loadBtn`, `__momotLoadBtn` or `__momotActions` left. The app was **not** run.

**How to check it works in the app.**
- The header shows `Show log` and `Clear path`; there is no Refresh. The row under the table (Load) is gone, and Stop is the only button in the button row.
- Click `Direct Manipulation`, then a path cell: the search starts without any other click, the progress bar appears, and the table fills.
- Click `Direct Manipulation`, then a wall: nothing starts.
- During a run, place a second marker elsewhere: the bar restarts at run 1 and does not show `ended`; the table shows the new search.
- Press Stop during a run: the bar shows `ended` and the table keeps its rows.
- Select a row: the status says `(double-click to load)` and the comparison path is drawn. Double-click it: the program loads into the game.
- Press `Clear path`: the comparison path and the marker disappear from the maze.

## 4. Recommended defaults

| Setting | Recommended | Why |
|---|---|---|
| `blocky.objectives` | `GATED` | Measured gain; the game outside Docker still defaults to `CURRENT` |
| `blocky.rules.wrap` | `true` | Measured gain on levels 6, 7, 9; harmless elsewhere |
| Rule files | `*_edit_anywhere` (game) | Needed for repair |
| `closestToGoal` | the fixed version, always on | A bug fix with no switch |
| `blocky.gate.slack` | unset (strict) | Not implemented; no search evidence |
| `blocky.nonGoalArchive` | 0 in code, set to 10 by `Main.java` for the game | Non-goal candidates stay visible without changing benchmark outputs |
| Panel parameters (seed, pop, iter, runs, solLen, alg) | 0, 100, 100, 8, 10, NSGA-II; fixed, no fields (section 3.7) | The user does not choose them |

## 5. Known gaps and risks

1. **Repair is untested everywhere.** Edit and delete are justified by an observation. Whether gating drifts away from a user's program, and whether wrap helps repair, is unknown. A repair benchmark would be: take a solution, apply one defect (delete, swap or change one block), run the search from it, and count how often the goal is reached, with and without `_edit_anywhere`. Not built.
2. **Rule set mismatch** between what was benchmarked and what ships (section 3.4).
3. **`.henshin_text` is out of date** with respect to the generated modules (section 3.3).
4. **Levels 8 and 10 are not solved by any of this.** Level 8 is solved once in 20 runs, level 10 never (0 of 60 runs in an earlier study). The landscape analysis and the proposal point to the generator of starting material (hypothesis H4: four solutions in about a million programs of up to 5 blocks), which none of the six items touch.
5. **Gated results stop at the first solution.** Nothing is measured about program quality (blocks, `Edits`, `Actions`) among solutions, which is where gating is supposed to pay off.
6. **Reproducibility.** The baseline differs from an earlier identical-seed run on 26 of 80 seeds, although the totals agree (39 against 35). Do not make claims about single seeds; use pooled counts.
7. **No automated tests.** The only automated checks are `run.sh verify` and `run.sh verify-wrap` for the rules. Everything else is checked by running the app, as `AGENTS.md` says.

## 6. Suggested order of work and commits

1. **Commit what exists,** in separate commits so each can be reviewed:
   - `closestToGoal` fix and gated objectives (`blocky_custom.java`, `BlockyUI.java` block-count column if kept separate);
   - tools and generated rules (`tools/henshin-prototype/`, `*_edit_anywhere*.henshin`, `*_wrap.henshin`, `EnumParamPreprocessFitnessFunction.java`);
   - the `blocky.rules.wrap` and `blocky.rules.editAnywhere` switches (`MomotFirstGoalBenchmarkRunner.java`, `docker-compose.yml`, `entrypoint.sh`);
   - the benchmark algorithms and analysis tools (`RandomSearchNSGAII.java`, `RandomImmigrantsNSGAII.java`, `LandscapeAnalysis.java`, `run_first_goal_benchmark.sh`);
   - the documents (`Benchmark-Analysis.md`, `Landscape-Analysis.md`, `Exploration-Proposal.md`, this file) and the benchmark CSVs.
2. **Set the defaults** of section 4 and fix the stale comment in `entrypoint.sh`.
3. **Close the `.henshin_text` gap** (section 3.3).
4. **Implement the relaxed gate** behind `blocky.gate.slack` (section 3.5), off by default.
5. **Show non-goal candidates** (section 3.6). Do change A (the panel) first: it is small, safe and gives the user the behaviour under the original objectives. Do change B (the archive) after the defaults of step 2 are set, because it is only needed once gating is on.
6. **Fixed panel parameters** (section 3.7), **panel progress and log** (section 3.9) and **panel buttons and automatic start** (section 3.10): changes to `BlockyUI.java` only, independent of the steps above. Commit them separately, in this order, because 3.9 and 3.10 refer to the code of the step before.
7. **Optional, when time allows:** the `_edit_anywhere` against `_edit_anywhere_wrap` check (section 3.4), the repair benchmark, a benchmark of the relaxed gate.

## 7. Checklist before calling the work done

- [ ] `mvn clean compile` passes from the repository root.
- [ ] `tools/henshin-prototype/run.sh verify` and `run.sh verify-wrap` pass.
- [ ] A one-run search on level 6 shows varying `closestToGoal` values in `objectives.pf` (not all 100000).
- [ ] With `GATED`, non-goal candidates show 100000 in `Edits`, `Actions` and `Blocks`.
- [ ] The game started with `mvn -pl blocky_game javafx:run` runs with `GATED` and wrap on, without extra flags.
- [ ] In the game, a program with one wrong block can be repaired by the search.
- [ ] The solution panel lists candidates that do not reach the goal, with and without a solution present, and no checkbox hides them.
- [ ] With `GATED`, non-goal candidates remain listed after a solution is found (archive of section 3.6, change B).
- [ ] Benchmark output is unchanged with `blocky.nonGoalArchive=0`.
- [ ] The MoMoT panel shows a progress bar with elapsed time during a run, the log is hidden by default and toggles with `Show log` / `Hide log`, and the table is unchanged (section 3.9).
- [ ] The MoMoT panel has no Refresh, Load or Run button, has `Clear path`, a Direct Manipulation click on a valid cell starts the search, and a second marker restarts it with a correct progress bar (section 3.10).
- [ ] The Execution log window is gone and running or stepping a program still works (section 3.8).
- [ ] The MoMoT panel has no parameter fields, and the status line on Run shows `solLen=10` (section 3.7).
- [ ] No file under `src-gen/` was modified.
- [ ] `AGENTS.md` mentions the new switches and the generated rule modules.
