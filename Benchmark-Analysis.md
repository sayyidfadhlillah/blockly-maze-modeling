# Benchmark Analysis: every MOMoT benchmark so far

This document collects every benchmark of the MOMoT search on the Blockly maze levels, in the order they were run, and ends with the latest one. Each section says what question the benchmark asked, how it was set up, the results, and what they do and do not show. The surrounding argument (hypotheses, plan) is in `Exploration-Proposal.md`; the landscape measurements that are not search runs are in `Landscape-Analysis.md`.

## 0. Reading guide

**Terms used in every section**

- **Level:** one of the ten maze levels. Levels 1–3 are trivial; 4–7 need loops (and from 6 on, conditions); 8 and 10 are the hard ones.
- **Run / seed:** one independent search from an empty program. Run *i* uses random seed *i*, so a configuration is reproducible and two configurations on the same seeds can be compared run by run.
- **Solved:** the run found a program that reaches the goal within the budget. Every benchmark here stops a run at its first goal-reaching candidate ("first goal"), so nothing is measured about the quality of solutions.
- **Budget:** population 150, 100 generations, 15,000 evaluations per run (unless a section says otherwise).
- **Generation of the first goal:** the generation in which the first goal-reaching candidate appeared (1 = in the random initial population).
- **ETT / EET:** expected time / evaluations to target if failed runs are restarted (formulas in `blocky_momot/analysis/FIRST_GOAL_BENCHMARK.md`, section 2). Infinite when no run succeeds.
- **McNemar test (exact):** compares two configurations on the same seeds. It only looks at the seeds where exactly one of them solved, and asks whether the split is more uneven than a fair coin. p < 0.05 is read as a real difference.

**How to read a "success counts" table:** each cell is the number of runs solved out of the runs of that level. Compare columns within a row. A difference of one or two runs on a level is within noise at 20 seeds; the totals over several levels are the reliable numbers.

**Hardware (all runs):** Intel Core i7-11800H (8 cores, 16 threads), 32 GB RAM, Windows 11, JDK 17. Wall-clock times of runs that were executed in parallel on this machine are not comparable with times of runs executed alone; success counts and generations are.

---

## 1. June 2026: minimum solution length (session `paper_simple_boosted_20260627`)

**Question:** how many transformation steps (`solutionLength`) does MOMoT need before at least one of 10 runs reaches the goal, and how large is the program it finds compared with the known optimum?

**Setup:** NSGA-II, population 150, 100 iterations (15,000 evaluations), 10 runs per `solutionLength`, seeds 1–10. The reported length is the smallest one for which at least one run succeeded. Source: `blocky_momot/analysis/BENCHMARK.md`.

**How to read the table:** "Min. solutionLength" is the number of rule applications the search needed. "MOMoT blocks" is how many real blocks the goal-reaching program has. The gap between the two is search overhead (placeholders, deletions, reordering). "Successes at min" is how many of the 10 runs succeeded at that length.

| Level | Optimal blocks | Min. solutionLength | MOMoT blocks | Successes at min |
|---:|---:|---:|---:|---:|
| 1 | 2 | 2 | 2 | 10/10 |
| 2 | 5 | 8 | 8 | 2/10 |
| 3 | 2 | 2 | 2 | 9/10 |
| 4 | 5 | 11 | 8 | 1/10 |
| 5 | 5 | 8 | 8 | 1/10 |
| 6 | 4 | 10 | 9 | 1/10 |
| 7 | 4 | 8 | 6 | 1/10 |
| 8 | 5 | 12 | 11 | 1/10 |
| 9 | 4 | 8 | 5 | 2/10 |
| 10 | 7 | 38 | 10 | 1/10 |

**What it showed:** every level is solvable by the search at some length, but from level 4 on only one or two runs in ten succeed at the minimum. These `solutionLength` values became the "canonical" lengths used by every later benchmark (2, 8, 2, 11, 8, 10, 8, 12, 8, 38). **What it did not show:** how often a run succeeds, only that one of ten can.

## 2. September 2026: first-goal benchmark, levels 1–10 (10 seeds)

**Question:** with the canonical `solutionLength`, how long does a run take to find its first goal-reaching program, and what is the expected time to target when failed runs are restarted? Source: `blocky_momot/analysis/FIRST_GOAL_BENCHMARK.md`.

**Setup:** NSGA-II, population 150, 15,000 evaluations, 10 seeds per level, rule file `statement_insertions_henshin_text.henshin` for every level (according to that document), early stop at the first goal.

**How to read the table:** success rate is solved runs over 10. Median time is for successful runs. ETT is the expected time to target with restarts; an ETT far above the median time means many restarts are needed.

| Level | Canonical length | Success rate | Median time (s) | ETT (s) |
|---:|---:|---:|---:|---:|
| 1 | 2 | 100% (10/10) | 1.42 | 1.43 |
| 2 | 8 | 20% (2/10) | 2.56 | 12.6 |
| 3 | 2 | 90% (9/10) | 1.63 | 1.81 |
| 4 | 11 | 10% (1/10) | 2.98 | 30.1 |
| 5 | 8 | 10% (1/10) | 2.54 | 25.4 |
| 6 | 10 | 10% (1/10) | 2.63 | 27.5 |
| 7 | 8 | 10% (1/10) | 2.94 | 28.9 |
| 8 | 12 | 10% (1/10) | 5.63 | 43.3 |
| 9 | 8 | 20% (2/10) | 3.98 | 19.6 |
| 10 | 38 | 10% (1/10) | 15.54 | 163.8 |

**What it showed:** the same picture as the June benchmark, now as a rate: about 10% per run from level 4 on, and the document concluded that every level is reachable within a finite expected time (level 10: about 2.7 minutes). **What it did not show:** with 10 seeds and one success per level, the rates are very uncertain (one run is 10 percentage points), and there was no baseline to say whether the search was doing anything beyond chance. The next sections supply that baseline. Note also the later finding that these runs and the ones below disagree on level 2 (2/10 here, 20/20 later); the likely cause is the rule file, because the runner now uses `atomic_only` for levels 1–2.

## 3. 2026-10-01, step 1: is NSGA-II better than random search?

**Question:** if a search with no selection at all (every generation a fresh random population from the same generator) is about as good as NSGA-II, then NSGA-II's successes are chance, not guidance from the fitness.

**Setup:** NSGA-II against `RANDOM_SEARCH` (`RandomSearchNSGAII.java`), levels 1–10, population 150, 15,000 evaluations, 20 seeds (same seeds for both), canonical solution length and twice that. Rule files as selected by the runner (`atomic_only` levels 1–2, `no_conds` 3–5, `no_else` 6–7, `henshin_text` 8–10), early stop at the first goal. Raw data: `blocky_momot/analysis/random_search_baseline/`.

**How to read the table:** each cell is solved runs out of 20. If NSGA-II beats random search, the search objectives are guiding it. If random search wins, the guidance is doing harm.

| Level | NSGA-II | Random search | NSGA-II, 2× length | Random search, 2× length |
|---:|---:|---:|---:|---:|
| 1–3 | 20 each | 20 each | 20 each | 20 each |
| 4 | 0 | 5 | 2 | 8 |
| 5 | 5 | 19 | 9 | 20 |
| 6 | 4 | 17 | 7 | 18 |
| 7 | 2 | 11 | 5 | 17 |
| 8 | 0 | 0 | 0 | 0 |
| 9 | 1 | 9 | 3 | 15 |
| 10 | 0 | 0 | 0 | 0 of 2 (stopped) |
| **Levels 4–9** | **12 / 120** | **61 / 120** | **26 / 120** | **78 / 120** |

**What it showed:**
1. NSGA-II was not just no better but clearly worse than random search on levels 4–9 (12 against 61 of 120).
2. All NSGA-II successes came early (first 31 of 100 generations); random search kept finding solutions up to generation 100, the look of a population that converged.
3. Levels 1–3 are trivial; levels 8 and 10 were solved by neither, so there the failure is about the generator, not the objectives.
4. Doubling the allowed length helps both algorithms.

**Caveat found afterwards:** the NSGA-II columns were measured with a bug. The `closestToGoal` objective returned the constant 100000 for every candidate on levels 2–10, so NSGA-II had nothing pointing towards the goal. The random-search columns are not affected. **Consequence:** random search became "the bar": any later change has to beat it, not just beat NSGA-II.

## 4. 2026-10-01, step 2: gated objectives

**Question:** does NSGA-II find more solutions if `Edits`, `Actions` and a new `Blocks` objective only count for candidates that reach the goal (hypothesis H2b: "small programs that do nothing are rewarded for being small")? Predictions were written before the run.

**Setup:** levels 4–9, population 150, 15,000 evaluations, seeds 1–20, canonical length, original rule files. Two new NSGA-II configurations, both with the `closestToGoal` fix: current objectives (ungated) and `blocky.objectives=GATED`. Reference columns are step 1. Raw data: `blocky_momot/analysis/gated_objectives/`.

**How to read the table:** solved runs out of 20. The two NSGA-II columns on the right differ only in the gate; the difference between them is the effect of gating alone.

| Level | NSGA-II, step 1 (broken `closestToGoal`) | NSGA-II, fixed, ungated | **NSGA-II, gated** | Random search |
|---:|---:|---:|---:|---:|
| 4 | 0 | 0 | 6 | 5 |
| 5 | 5 | 5 | 20 | 19 |
| 6 | 4 | 4 | 15 | 17 |
| 7 | 2 | 3 | 14 | 11 |
| 8 | 0 | 1 | 1 | 0 |
| 9 | 1 | 3 | 5 | 9 |
| **Levels 4–9** | **12 / 120** | **16 / 120** | **61 / 120** | **61 / 120** |

Paired comparison on the 120 runs (exact McNemar): gated against fixed-ungated, 50 runs only gated solved against 5 only ungated solved (p < 0.001); gated against random search, 18 against 18 (p = 1.0); fixed-ungated against step 1, 14 against 10 (p = 0.54).

**What it showed:**
1. **Gating was the limiting cause.** It roughly quadruples solved runs (16 → 61) with no change to NSGA-II. The early stall of step 1 disappeared: 28 of the 61 gated successes come after generation 31, the same as random search.
2. Fixing `closestToGoal` alone did not help (12 → 16, p = 0.54): whatever it contributed was drowned out by the pull towards small programs.
3. **Gated NSGA-II is only equal to random search** (61 against 61). Before any solution exists, `closestToGoal` (Progress) is the only objective that differs between candidates, so this is a direct test of Progress as a guide: it gives no measurable advantage over no guidance.
4. Where both solved, gated NSGA-II returned programs with fewer blocks in 12 of 43 runs and never more; this understates the gain, because runs stop at the first solution.
5. Levels 8 and 10 stayed out of reach.

## 5. 2026-10-01/02, landscape analysis (not a search run)

This is not a benchmark but explains what follows. All programs up to a block limit were enumerated and each objective was scored, and the question was whether a better objective value means fewer edits to a solution. Details: `Landscape-Analysis.md`.

**What it showed, in one table (rank correlation of Progress with the distance to a solution; above 0.3 informative, below 0.1 uninformative):**

| Levels | Wrap off | Wrap on |
|---|---|---|
| 4, 5 | weak on level 4 (0.12), informative on level 5 (0.31) | little change (0.18, 0.31) |
| 6, 7, 9 | not informative | informative (0.31, 0.31, 0.29) |
| 8, 10 | not informative | **still not informative (0.08)** |

Here "wrap on" means that wrapping a block and its successors into a new loop or if, and the reverse, count as single edits. The landscape became more climbable with wrap on levels 6, 7 and 9, and not on 8 and 10. Whether a search benefits was left open. This is what the benchmark in the next section tests.

## 6. 2026-10-02, step 3: wrap and unwrap moves in the search (latest benchmark)

**Question:** the landscape analysis (section 5) says wrap and unwrap make Progress informative on levels 6, 7 and 9 but not on 8 and 10. Does a gated NSGA-II solve more runs when the rules include wrap and unwrap moves?

**Prediction, written before the run:** with wrap moves, more runs are solved on levels 6, 7 and 9; about the same on levels 8 and 10.

### Setup

- **Same benchmark as step 2:** NSGA-II, gated objectives (`BLOCKY_OBJECTIVES=GATED`, with the `closestToGoal` fix), population 150, 15,000 evaluations, seeds 1–20, canonical solution length, early stop at the first goal. Levels 6–10 (level 10 has no gated baseline from step 2, so it was run here).
- **Wrap off:** the original rule files, as in step 2 (`no_else` for levels 6–7, `henshin_text` for 8–10). Rerun with the current code (session `wrapbench_off`) instead of reusing step 2, so both arms ran on the same code.
- **Wrap on:** `BLOCKY_WRAP=true` selects the `*_wrap.henshin` files (session `wrapbench_on`), built by `tools/henshin-prototype/WrapOpsPatcher.java`.
- **Raw data:** `blocky_momot/analysis/first_goal_benchmark_raw_wrapbench_{off,on}.csv`, with summaries and logs next to them. The two runs executed in parallel on one machine, so times are not comparable; success counts and generations are.
- **Smoke test first:** level 6, 2 seeds, wrap on. The wrap rule file loaded and both runs solved. This was the first execution of the wrap rules by a search; before it they had only been checked on small programs.

**What differs between the two arms (a confound):** wrap on is not "wrap off plus two moves". In `blocky_custom`, a module that contains a unit named `EditAnywhere` makes that unit the only search move. `WrapOpsPatcher` creates `EditAnywhere` from the insert rule (weighted three times) and a Restructure move (wrap or unwrap). So against the original rules, wrap on also (a) drops the separate `DeleteContainerAnywhere` move, so blocks can only be removed by unwrapping, and (b) changes how often each insert is chosen. A result therefore says "the `_wrap` rule set against the original rule set", not "wrap alone". A controlled pair (`*_edit_anywhere` against `*_edit_anywhere_wrap`, which differ only in wrap and unwrap) was prepared in the runner (`-Dblocky.rules.editAnywhere=true`) but **not run**, by decision of the project owner for lack of time.

### Success counts (out of 20 seeds)

**How to read:** each cell is solved runs out of 20; the two columns differ only in the rule set. The last two columns are a paired exact McNemar test on the same seeds: "only wrap on" is the number of seeds that wrap on solved and wrap off did not, "only wrap off" the reverse.

| Level | Wrap off | Wrap on | Only wrap on / only wrap off | p |
|---:|---:|---:|---:|---:|
| 6 | 17 | 19 | 3 / 1 | 0.63 |
| 7 | 14 | 18 | 5 / 1 | 0.22 |
| 8 | 1 | 1 | 1 / 1 | 1.0 |
| 9 | 7 | **18** | 12 / 1 | 0.003 |
| 10 | 0 | 0 | 0 / 0 | 1.0 |
| **Levels 6, 7, 9 (predicted to gain)** | **38 / 60** | **55 / 60** | 20 / 3 | < 0.001 |
| **Levels 8, 10 (predicted not to gain)** | **1 / 40** | **1 / 40** | 1 / 1 | 1.0 |
| **Levels 6–10** | **39 / 100** | **56 / 100** | 21 / 4 | < 0.001 |

### Generation of the first goal (solved runs only)

**How to read:** the generation (1–100) in which the first goal-reaching candidate appeared. A lower median means the search finds a solution sooner.

| Level | Wrap off: median (range), solved runs | Wrap on: median (range), solved runs |
|---:|---|---|
| 6 | 31 (2–98), 17 | 13 (2–97), 19 |
| 7 | 49.5 (3–96), 14 | 18 (1–49), 18 |
| 8 | 97, 1 run | 93, 1 run |
| 9 | 28 (4–94), 7 | 17 (2–52), 18 |
| 10 | none | none |

### Reference points

- **Reproducibility of the baseline:** wrap off (this run) against the gated run of step 2, same seeds, levels 6–9: 39 against 35 solved of 80, but 26 of the 80 seeds differ (15 only here, 11 only there; p = 0.56). The totals agree, individual seeds do not, so the search is not reproducible seed by seed on this code. Per-seed claims should be avoided; the pooled counts are what to rely on.
- **Against random search (step 1), levels 6–9:** wrap on solves 56 of 80 against 37 for random search. Seeds solved only by wrap on: 23; only by random search: 4 (p < 0.001). Gated NSGA-II without wrap was equal to random search (section 4); with wrap it is clearly better on these levels.

### Predictions against results

| Prediction | Result |
|---|---|
| More runs solved with wrap on levels 6, 7, 9 | **Held on the pooled count** (38 → 55 of 60, p < 0.001). Per level only level 9 is significant (7 → 18, p = 0.003); levels 6 and 7 move in the predicted direction (+2, +4) but are within noise on 20 seeds. |
| About the same on levels 8 and 10 | **Held.** 1 of 40 in both arms. |

### Findings

1. **Wrap moves help the search where the landscape analysis said the landscape became climbable.** The landscape prediction (levels 6, 7, 9 gain; 8, 10 do not) was confirmed by a search, not only by an enumeration. The gain is absent where the landscape did not improve (8 and 10). It is largest on level 9, although the landscape measure there (0.29) was no larger than on levels 6 and 7 (0.31), so the size of the gain is not predicted by the landscape measure.
2. **The gain is mostly in finding solutions earlier and more reliably.** The median generation of the first goal drops from 31 to 13 (level 6), 49.5 to 18 (level 7) and 28 to 17 (level 9).
3. **Gated NSGA-II with wrap beats random search on levels 6–9 (56 against 37 of 80).** This is the first configuration in this series that does. Gating alone only reached equality (section 4).
4. **Levels 8 and 10 are still out of reach**, and wrap did not change that. This agrees with the landscape result (Progress stays uninformative there) and with the proposal's view that these two levels are a generator problem (H4: 4 solutions in about a million programs of up to 5 blocks).

### Limits of this measurement

- **Not wrap alone** (see the confound above): the loss of the separate delete move and the changed insert weighting are mixed into the effect. The size of the effect on levels 7 and 9 makes it unlikely that deleting less explains all of it, but this was not tested.
- **Levels 6, 7 and 9 were grouped because they were predicted to gain**, before the run. The subgroup "7 and 9" that stands out in the data was not planned and is not used as evidence here.
- **20 seeds per level:** only level 9 is significant on its own. The baseline differs from the step 2 run on 26 of 80 seeds, so differences of a few runs on one level are noise.
- **First goal only:** the run stops at the first solution. Nothing is measured about block count, `Edits` or `Actions` of the solutions, and nothing about repair of a buggy program.
- **Synthesis from an empty program only.** Wrap preserves what a program already does, which suggests it helps repair too, but no repair benchmark exists.
- The wrap rules are applied by Henshin in a search for the first time here; their behaviour on arbitrary programs is otherwise only checked by `run.sh verify-wrap` on small programs.

## 7. Where this leaves the exploration (decision of 2026-10-02)

The project owner decided, from the results above and from experience with the original exploration, that the working configuration, compared with the original `main` branch, is:

| Change | Evidence |
|---|---|
| **Fix of `closestToGoal`** (it was the constant 100000 on levels 2–10) | Bug, shown in step 1. Alone it changed little (12 → 16 of 120, p = 0.54), but without it Progress cannot guide anything. |
| **Gated objectives** (`Edits`, `Actions`, `Blocks` only count for goal-reaching candidates) | **Measured:** 16 → 61 of 120 on levels 4–9 (p < 0.001), section 4. Only brings NSGA-II to the level of random search. |
| **Wrap and unwrap rules** | **Measured:** 39 → 56 of 100 on levels 6–10 (p < 0.001), and 37 → 56 against random search on levels 6–9, section 6. Confounded with the loss of the separate delete move and the insert weighting. |
| **Edit and delete (and modify) rules** (`*_edit_anywhere`) | **Not measured in any benchmark.** Needed for repairing buggy programs, which the project owner saw frequently with the original exploration. All benchmarks here start from an empty program, where there are no user blocks to edit or delete, so they cannot show it. |

**What is and is not supported by data today**

- **Supported:** gating, the `closestToGoal` fix, and the wrap rules, for synthesis on levels 4–9.
- **Not supported by a benchmark:** the edit and delete rules, and every claim about repair, including whether gating lets the search drift away from a user's program before the goal is reached (flagged as untested in section 4).
- **Not solved by any of these:** levels 8 and 10.

**Open, not planned:** the controlled pair `*_edit_anywhere` against `*_edit_anywhere_wrap` (isolates wrap), a repair benchmark (measures the edit and delete rules and the gating question in repair), runs without the early stop (solution quality), and the generator for levels 8 and 10.
