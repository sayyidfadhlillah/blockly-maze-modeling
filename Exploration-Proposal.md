# Exploration Proposal: Improving the MOMoT Search

Status: draft for discussion, 2026-10-01. The random-search baseline (step 1) is measured; see section 6. Gated objectives (step 2) are implemented and measured; see section 7: NSGA-II now solves as many runs as random search (61 of 120 on levels 4–9), up from 16. E1 (landscape analysis) is run; see section 8: on levels 6–10 neither Progress nor any behaviour-based alternative follows the distance to a solution. E2–E4 are not run. The plan from here is in section 9: change the rules (wrap moves) and give the search longer programs, not further objectives.

The document follows one line of argument: what is observed (section 2), what could explain it (section 3), which experiment separates the explanations (section 4), and which improvement follows from which confirmed explanation (section 5).

## 1. Problem

The search does not reliably synthesize a solution for most maze levels, and never for level 10.

**Existing benchmark** (`blocky_momot/analysis/FIRST_GOAL_BENCHMARK.md`: original rules, NSGA-II, pop 150, 15,000 evaluations, 10 seeds):

| Levels | Success rate | Optimal blocks |
|---|---|---|
| 1, 3 | 10/10 and 9/10 | 2 |
| 2, 9 | 2/10 | 5 and 4 |
| 4, 5, 6, 7, 8, 10 | 1/10 | 4–7 |

These rates were measured at the smallest `solutionLength` where at least one run succeeded, so a low rate is partly built into the benchmark setup. Rates at longer lengths have not been checked.

**Level 10 sweep** (2026-09-30: pop 100, 10,000 evaluations, 5 seeds, SolLen 6/10/20/38):

| Rules / algorithm | Runs solved | Best `closestToGoal` |
|---|---|---|
| New rules (`_edit_anywhere`), NSGA-II | 0/20 | 6–7 |
| New rules, memetic NSGA-II | 0/20 | 5–7 |
| Original rules, NSGA-II | 0/20 | 5–7 |

The harness and logs of this sweep were in a temporary folder and are not in the repository.

## 2. Observations

Only what has been measured. The objectives referred to are `GoalReached`, `closestToGoal`, `Edits` and `Actions` (defined in `blocky_momot/blocky.momot`; `Actions` is overridden in `blocky_custom.java`).

**O1. NSGA-II solves fewer runs than random search on levels 4–9.** 12 against 61 of 120 runs at the canonical solution length, 26 against 78 at twice that length (section 6).

**O2. NSGA-II only succeeds early.** All its successes fall in the first 31 of 100 generations. Random search keeps succeeding up to generation 100 (section 6).

**O3. Levels 8 and 10 are solved by neither algorithm.** Random search made 300,000 draws per level at the canonical length without a hit, so the chance per draw of generating a solution is below about 1 in 100,000 (95% bound). For level 10 this compares with about 1 in 8,000 for uniform sampling of programs up to 5 blocks (O4). The solution density of level 8 is not known.

**O4. On level 10, `closestToGoal` does not separate near-solutions from random programs.** A brute force over all 31,722 programs of up to 5 blocks found 4 that solve the level, for example:

```
repeat until goal
  if path ahead
    move forward
    turn left
  else
    turn right
```

| Program | `closestToGoal` |
|---|---|
| The solution above | 0 |
| Solution with one block removed or changed | 8–10 |
| Typical random program | 8–10 |
| Best non-solutions, e.g. `loop{F F L}`, `F F L F F` | 6 |

The best-scoring non-solutions walk partway down the corridor and are structurally unrelated to the solution. This was checked on a few programs by hand, not over the whole space, and the brute-force code is not in the repository.

**O5. Levels 1–3 are solved by both algorithms in 20/20 runs**, almost always in the first generation. They say nothing about the search and are left out of the experiments below.

**O6. In the step 1 benchmark, `closestToGoal` was a constant for every candidate on levels 2–10** (found 2026-10-01). Every value recorded in the NSGA-II outputs of step 1 is the penalty 100000, also for programs that reach the goal; only level 1 has real values.

- **Cause:** the objective reads `Cell.distanceToGoal`, which `BlockySimulator.initialize` writes into the input file. The generated runner calls it once, for the file named by the static `blocky.input` when the class is loaded. The benchmark loads the class once and then runs all levels, so only the first file is annotated. On an unannotated map the objective returns its penalty.
- **Consequence:** in step 1, NSGA-II on levels 2–10 had no objective pointing towards the goal. `GoalReached` is 0 until a solution exists, and `closestToGoal` was constant, so selection acted on `Edits` and `Actions` alone, which both prefer the smallest program. O1 and O2 therefore describe NSGA-II with a broken objective, not NSGA-II with the intended one.
- **Not affected:** random search (it does not select), and the level 10 sweep of 2026-09-30, which reported real `closestToGoal` values (5–7) and O4.
- **Fix:** `blocky_custom.java` now computes the distance field per evaluation (`BlockySimulator.distanceToGoalOrPenalty`), which gives the same value without depending on the annotation.

## 3. Hypotheses

NSGA-II differs from random search in two ways at once: it selects candidates on the objectives, and it breeds new candidates from old ones instead of generating them fresh. O1 and O2 show that this combination hurts, but not which part.

| | Hypothesis | Would explain | Improvement that rests on it |
|---|---|---|---|
| H1 | The population loses diversity, and the search stalls once it has converged. | O1, O2 | Novelty objective or archive (step 3) |
| H2a | `closestToGoal` is not informative: a better value does not mean a program closer to a solution. | O1, O2, O4 | Replacing or demoting `closestToGoal` (step 3) |
| H2b | `Edits` and `Actions`, as equal objectives, fill the Pareto front with tiny programs that cannot reach the goal. | O1, O2 | `Edits` and `Actions` as tie-breakers (step 2) |
| H3 | The variation operators (crossover and mutation on rule sequences) produce worse candidates than fresh generation. | O1, O2 | A change to the operators (not yet designed) |
| H4 | The rule-based generator almost never produces a program with the shape of a solution on levels 8 and 10. | O3 | Seeded program shape, exact enumeration, or a different generator |

The hypotheses are not exclusive; several can hold at once. H1 can also be a consequence of H2 or H3 instead of an independent cause: any selective algorithm converges, and convergence only hurts if it happens in the wrong place.

**What is not yet justified.** Steps 2 and 3 were proposed on the strength of O4 and the reasoning behind H2b. O4 is one level and a handful of programs, and level 10 is not among the levels where NSGA-II and random search differ (both score 0). H2b has no measurement behind it at all. H3 has not been considered so far and would make steps 2 and 3 ineffective if it is the main cause.

## 4. Experiments

Each experiment isolates one hypothesis and states its prediction before it is run, so that the result can refute the hypothesis. Unless stated otherwise: levels 4–9, pop 150, 15,000 evaluations, the same 20 seeds as section 6, original rule files as selected by the runner, canonical solution length. Algorithms on the same seeds are compared with an exact McNemar test on the paired runs.

Thresholds given below are proposals and should be agreed before running.

### E1: Landscape analysis (tests H2a and H2b)

**Question:** does a better objective value mean a program that is closer to a solution? This is measured without running any search, so the result does not depend on the algorithm or on the generator.

**Setup**

- **Program space:** for each of levels 4–9 and level 10, enumerate every program up to a block limit, using the block vocabulary of the rule set that level uses (`no_conds` for 4–5, `no_else` for 6–7, full for 8–10). The limit is the size of the smallest solution plus one where that stays enumerable. Level 10 up to 5 blocks is 31,722 programs.
- **Evaluation:** compute the four objectives for each program with `BlockySimulator` and the existing metrics. Every program that reaches the goal is a solution.
- **Distance to a solution:** block-level tree edit distance from `BlockyProgramDistance`, taken to the nearest solution. A neighbour of a program is a program at distance 1.

**Why all of levels 4–9 and not one control level.** O1 is a result about levels 4–9, so H2a must be tested there. A single control level cannot serve: every non-trivial level where random search succeeds is also a level where NSGA-II loses (level 5: 5 against 19 of 20). The test is therefore whether the objectives are uninformative on the levels where NSGA-II loses, and whether the degree matches the size of the loss.

**Tests and predictions**

| Test | Measure | Prediction if the hypothesis holds |
|---|---|---|
| Fitness-distance relation (H2a) | Spearman correlation between `closestToGoal` and distance to the nearest solution, plus mean `closestToGoal` per distance class | Correlation below 0.1 on the levels where NSGA-II loses. Across levels, a lower correlation goes with a lower NSGA-II/random success ratio. |
| One-edit neighbour test (H2a) | For non-solution programs: how often a neighbour that is closer to a solution has a better `closestToGoal`, against how often a neighbour that is farther has one | The two frequencies do not differ. |
| Local optima (H2a) | Share of non-solution programs with no neighbour of strictly better `closestToGoal`, and their distance to the nearest solution | A large share, at distances no smaller than the average program. |
| Plateau share (H2a) | Share of programs in the most common `closestToGoal` value | Reported for context. No prediction: `GoalReached` is flat by construction and is not tested. |
| Front composition (H2b) | Draw random populations of 150 from the enumerated space, sort by all four objectives, and record block count and distance to a solution of the first front. Repeat with `GoalReached` and `closestToGoal` only. | With four objectives the first front is dominated by programs smaller than the smallest solution; with two objectives it is not. |

**What the result decides**

- H2a predictions hold on levels 4–9: `closestToGoal` is measured to be uninformative, and step 3 is justified.
- H2a predictions fail (correlation above 0.3 on levels where NSGA-II still loses): the fitness is not the cause there. O4 is then a property of level 10 only, and step 3 is not justified for levels 4–9.
- H2b prediction holds: step 2 is justified, independently of H2a.

**Threats to validity**

- `BlockyProgramDistance` has no wrap or unwrap operation: putting two existing blocks inside a new loop costs 5, and replacing a block by one of another kind is charged as delete plus insert. Distance 1 therefore means inserting or deleting one childless block, or relabelling one. The search does not move in this neighbourhood; it recombines rule sequences. E1 measures whether the objectives are informative about program structure, not whether they are informative for these particular operators. That second question is E2.
- Enumeration weights every program equally, and the largest size class dominates the count. The search sees a different distribution. Results are reported per block count as well as overall.
- Neighbours of programs at the block limit lie partly outside the enumerated space.
- With tens of thousands of programs every correlation is statistically significant; the thresholds above are on effect size.
- Levels with longer solutions may not stay enumerable beyond 5–6 blocks.

### E2: NSGA-II without fitness (tests H3)

**Question:** do the variation operators alone, with no selection on the objectives, already do worse than fresh generation?

**Setup:** a variant of NSGA-II in which parents are chosen uniformly at random and the next population is a uniform random subset of parents and offspring. Crossover and mutation are unchanged. This differs from random search only in how new candidates are produced.

| Outcome | Reading |
|---|---|
| Solves clearly fewer runs than random search | H3 holds: the operators are a cause on their own, and changing the fitness alone will not close the gap. |
| About as many as random search, more than NSGA-II | H3 is refuted: the loss in O1 comes from selection, which leaves H1 and H2. |
| Between the two | Both contribute; the share of each is read from the three success counts. |

**Threat to validity:** without selection the population drifts, and the result depends on the mutation and crossover rates. They are kept at the NSGA-II settings so that only selection changes.

### E3: Diversity over generations (tests H1)

**Question:** does the NSGA-II population converge before the point where successes stop (O2)?

**Setup:** in the existing NSGA-II runs, log per generation the number of distinct programs and the number of distinct final robot states (cell and heading) in the population. No change to the search.

**Prediction if H1 holds:** both counts fall to a small fraction of the population size before generation 30, and runs that keep more diversity succeed more often.

**Not pursued: random immigrants.** A variant that replaces part of each generation by fresh candidates exists (`RandomImmigrantsNSGAII`, `blocky.algorithm=IMMIGRANTS_NSGA_II`) but is not measured. It helps under H1, H2 and H3 alike, because fresh candidates bypass both selection and variation, so its result would not separate the hypotheses. It is also a change to the algorithm, which is out of scope for now (see below).

### E4: Generator coverage (tests H4)

**Question:** how likely is the generator to produce a program with the shape of a known solution on levels 8 and 10?

**Setup:** sample the generator at the canonical solution length and record block count, nesting depth, and the presence of a loop, an if and an if/else. Compare with the shapes of the solutions found by enumeration in E1.

**Prediction if H4 holds:** the solution shapes are absent or far rarer than under uniform sampling of small programs.

**Open point:** the canonical length for level 10 is 38 rule applications, which does not match a 5-block solution. This should be understood first, because it determines what the generator can produce.

### Order and scope

**Objectives first, algorithm later.** The work is done in two phases, so that a change in the result can be attributed to one thing.

1. **Objectives (now).** NSGA-II, its operators and its settings stay as they are. Gated objectives (step 2) were tried first, directly in the benchmark, and confirmed H2b (section 7). E1 is next: it is the direct test of H2a and it does not involve the algorithm at all.
2. **Algorithm (later).** E2, E3 and step 4 are deferred until the objectives have been improved.

**E1 is also the instrument for judging a new objective.** The measures in E1 (fitness-distance relation, neighbour test, local optima, front composition) can be computed for any candidate objective over the same enumerated programs. A candidate objective is therefore accepted or rejected on these measures before any search is run. This matters because of H3: if the operators are a cause of their own, a better objective may show little gain inside NSGA-II. Judging the objective on the landscape keeps the two questions apart: "is the objective better?" is answered by E1, and "does NSGA-II make use of it?" by the benchmark.

**When E2 is needed.** If an objective is measurably better on the E1 measures but NSGA-II still does not reach random search, the remaining cause is in the algorithm, and E2 and E3 are run then.

E4 reuses the solutions enumerated in E1 and can follow it directly.

## 5. Improvements, conditional on the experiments

No improvement is started before the experiment it rests on. A new or changed objective is first judged on the E1 measures over the enumerated programs. Each improvement is then evaluated against random search on levels 4–9 under the same budget and seeds, reporting success rate and evaluations to first goal. Random search is the bar, not NSGA-II (section 6).

### Step 2: Gated quality objectives (trial agreed 2026-10-01, run directly as a test of H2b)

**Design.** Derived from what the tool must deliver: a program that reaches the goal, and among such programs few edits, few blocks and few actions, with equal priority. The block limit is not a constraint.

| Objective | Direction | Counts for | Change from today |
|---|---|---|---|
| Progress (`closestToGoal`) | minimise | all candidates | unchanged (with the fix of O6) |
| `Edits` (net block edit distance to the input program) | minimise | candidates that reach the goal | gated |
| `Blocks` (number of blocks) | minimise | candidates that reach the goal | new, gated |
| `Actions` (executed steps) | minimise | candidates that reach the goal | gated |

- **Gated** means: a candidate that does not reach the goal gets one fixed worst value (100000). A small or early-crashing program then gains nothing from being small.
- `Edits` and `Blocks` are separate objectives: with modify and delete operations on a non-empty program the number of edits can be high while the number of blocks stays the same. They coincide only for synthesis from an empty program.
- `GoalReached` stays in the vector because the tools read it as the first value. It does not change which candidate dominates which: it is implied by Progress = 0.
- Until a candidate reaches the goal, Progress is the only objective that differs between candidates.
- **Implementation:** `blocky.objectives=GATED` (`BLOCKY_OBJECTIVES` in the run script), in `blocky_custom.java`. The default `CURRENT` keeps the objectives ungated. NSGA-II is unchanged.

**Test.** Levels 4–9, pop 150, 15,000 evaluations, the 20 seeds of step 1, canonical solution length. Two configurations, both with the O6 fix: current objectives and gated objectives. Random search (61 of 120) is the bar.

**Predictions, written before the run**

| Comparison | Prediction | If it fails |
|---|---|---|
| Current objectives with the O6 fix against step 1 (12 of 120) | Clearly more runs solved, because the search now has an objective pointing towards the goal | Progress carries little information on these levels (H2a), or the operators are the cause (H3) |
| Gated against current, both with the fix | More runs solved, if H2b holds | H2b is not the limiting cause |
| Gated against random search | Open. Reaching 61 of 120 would mean the objectives are no longer the problem | The next thing to improve is what Progress measures, as agreed; E1 can show whether Progress or the operators (H3) are responsible |

**Result:** see section 7.

### Step 3: Reward new behaviour instead of smaller distance (requires H2a from E1, or H1 from E3)

**Status after E1 (section 8):** H2a is confirmed for levels 6–10, but two behaviour-based candidates for a second guiding objective (suffix coverage and route following) were measured and are as uninformative as Progress there. A further behaviour-based objective is not expected to help; the plan in section 9 changes the rules instead. The text below is kept as originally proposed.

- Add a novelty objective: a candidate scores well if the robot's behaviour differs from what the population and an archive have already seen.
- Candidate behaviour descriptors, to be compared:
  - final cell and heading;
  - set of visited cells;
  - final cell plus program shape (has loop, has if, has if/else).
- Keep `closestToGoal` as a secondary signal or drop it, depending on its measured informativeness per level in E1.
- An alternative with the same idea is a MAP-Elites archive (one best candidate per behaviour cell) instead of NSGA-II.
- Changes needed: `BlockySimulator` must expose the trajectory; a new fitness dimension and an archive in `blocky_momot/src`. Objectives in `src-gen` are generated from `blocky.momot`, so either the `.momot` is edited and regenerated in Eclipse, or the dimension is overridden in `blocky_custom.java` as is done for `Actions`.
- **Prediction:** success on levels 4–9 reaches or exceeds random search, and successes are no longer confined to the first 30 generations.

### Operators (requires H3, from E2)

Not designed. If E2 confirms H3, the next question is which operator does the damage (crossover on rule sequences, mutation, or repair of invalid sequences), measured by comparing offspring with their parents.

### Generator for levels 8 and 10 (requires H4, from E4)

Options are a seeded program shape, exact enumeration (below), or a larger budget. The choice depends on how many solutions exist at which size (E1) and how far the generator is from producing them (E4).

### Step 4: Revisit the algorithm

Only after the steps above. Swapping NSGA-II for another algorithm on an unchanged fitness and unchanged operators is not expected to help under H2 or H3, because the new algorithm would use the same signal and the same operators.

### Optional: exact search for small cases

Works outside MOMoT, so it depends on whether staying inside MOMoT is a requirement.

- **Synthesis from empty:** enumerate programs by increasing block count. Exact, under a second at 5 blocks, returns the shortest solution. E1 builds this enumeration anyway, and it gives the ground truth for the benchmark (number of solutions per level and size).
- **Repair:** enumerate all 1-edit variants of the user's program, then 2-edit variants. Small and exact, and it fits the modify/delete rules.

## 6. Results of step 1: random-search baseline (2026-10-01)

**Question asked:** is NSGA-II on the current objectives better than search without any selection? If random search is about as good, the successes of NSGA-II are chance and not guidance from the fitness.

### Setup

- **Random search:** `blocky_momot/src/blocky_momot/RandomSearchNSGAII.java`, selected with `blocky.algorithm=RANDOM_SEARCH`. Every generation it draws a full population of new candidates from the same generator that builds NSGA-II's initial population. Nothing is bred from earlier candidates.
- **Runner:** `MomotFirstGoalBenchmarkRunner` with early stop at the first goal-reaching candidate. The run script takes two new settings, `BLOCKY_ALGORITHM` and `BLOCKY_SOL_LEN_FACTOR`.
- **Budget:** pop 150, 15,000 evaluations, 20 seeds per level, same seeds for both algorithms.
- **Rules:** the original rule files as selected by the runner (`atomic_only` for levels 1–2, `no_conds` for 3–5, `no_else` for 6–7, `henshin_text` for 8–10). The `_edit_anywhere` rules were not tested.
- **Solution length:** the canonical length per level (2, 8, 2, 11, 8, 10, 8, 12, 8, 38) and twice that.
- **Raw data:** `blocky_momot/analysis/random_search_baseline/raw_*.csv`.

### Success counts (out of 20 seeds)

| Level | NSGA-II | Random search | NSGA-II, 2× length | Random search, 2× length |
|---:|---:|---:|---:|---:|
| 1 | 20 | 20 | 20 | 20 |
| 2 | 20 | 20 | 20 | 20 |
| 3 | 20 | 20 | 20 | 20 |
| 4 | 0 | 5 | 2 | 8 |
| 5 | 5 | 19 | 9 | 20 |
| 6 | 4 | 17 | 7 | 18 |
| 7 | 2 | 11 | 5 | 17 |
| 8 | 0 | 0 | 0 | 0 |
| 9 | 1 | 9 | 3 | 15 |
| 10 | 0 | 0 | 0 | 0 of 2 (stopped) |
| **Levels 4–9** | **12 / 120** | **61 / 120** | **26 / 120** | **78 / 120** |

The level 10 run of random search at 2× length (SolLen 76) took about 17 minutes per seed and was stopped after 2 seeds.

### Generation of the first goal (levels 4–9, solved runs only)

| Configuration | Range | Typical (median per level) |
|---|---|---|
| NSGA-II | 1–18 | 2–5 |
| NSGA-II, 2× length | 2–31 | 11–23 |
| Random search | 1–100 | 7–57 |
| Random search, 2× length | 1–96 | 10–32 |

### Findings

1. **NSGA-II is worse than random search on levels 4–9, not merely no better.** Random search solves about five times as many runs at the canonical length (61 vs 12) and three times as many at 2× length (78 vs 26). The combination of selection on the current objectives and breeding makes the search worse than fresh generation with no selection. The measurement does not show which of the two is responsible (H1–H3 in section 3).
2. **NSGA-II only succeeds early.** All of its hits come in the first 31 of 100 generations (the first 18 at canonical length). Random search keeps finding solutions up to generation 100. This is what convergence of the population would look like, but diversity was not measured (E3).
3. **Levels 1–3 are trivial.** Both algorithms solve them in 20/20 runs, almost always in the first generation.
4. **A longer solution length helps both algorithms.** It roughly doubles NSGA-II's success count and adds about a quarter to random search's.
5. **Levels 8 and 10 are out of reach for both.** Neither was solved in any run. Since random search has no selection, its failure there is about the generator and not about the objectives (H4).

### Limits of this measurement

- **The NSGA-II columns were measured with a broken `closestToGoal` (O6).** On levels 2–10 that objective was constant, so NSGA-II had nothing pointing towards the goal. The random-search columns are not affected. Findings 1 and 2 hold as measured, but they are findings about the benchmark setup at that time, not about the intended objectives. The corrected comparison is in section 7.
- It only counts whether a goal-reaching program was found. It does not compare program quality (number of blocks, `Edits`, `Actions`), where NSGA-II may do better once a solution exists.
- It covers synthesis from an empty program only, not repair.
- The four configurations ran in parallel on one machine, so the recorded times are not comparable with the earlier benchmark; only success counts and generations are.
- Level 2 is 20/20 here against 2/10 in `FIRST_GOAL_BENCHMARK.md`. That document lists `henshin_text` as the rule set for every level, while the runner now uses `atomic_only` for levels 1–2, which is the likely cause. This was not investigated further.

### Consequences

- **Random search is the bar.** Any later change must beat it on levels 4–9 under the same budget, not just beat NSGA-II.
- **The cause is open.** The result is compatible with H1, H2a, H2b and H3. Steps 2 and 3 are not yet justified by it; experiments E1–E3 decide.
- **Levels 8 and 10 are a separate problem** (H4, experiment E4).

## 7. Results of step 2: gated objectives (2026-10-01)

**Question asked:** does NSGA-II find more solutions when `Edits`, `Blocks` and `Actions` only count for candidates that reach the goal (H2b)? The design and the predictions are in section 5, step 2; the predictions were written before the run.

### Setup

- **Same benchmark as step 1:** levels 4–9, pop 150, 15,000 evaluations, seeds 1–20, canonical solution length, original rule files, early stop at the first goal-reaching candidate.
- **Two new configurations,** both NSGA-II and both with the `closestToGoal` fix of O6:
  - *Current, fixed:* the four objectives as before, ungated.
  - *Gated:* `blocky.objectives=GATED`.
- **Reference columns from step 1:** NSGA-II as measured then (constant `closestToGoal`), and random search. They were not rerun.
- **Raw data:** `blocky_momot/analysis/gated_objectives/raw_*.csv`.

### Success counts (out of 20 seeds)

| Level | NSGA-II, step 1 (constant `closestToGoal`) | NSGA-II, current objectives, fixed | NSGA-II, gated | Random search |
|---:|---:|---:|---:|---:|
| 4 | 0 | 0 | 6 | 5 |
| 5 | 5 | 5 | 20 | 19 |
| 6 | 4 | 4 | 15 | 17 |
| 7 | 2 | 3 | 14 | 11 |
| 8 | 0 | 1 | 1 | 0 |
| 9 | 1 | 3 | 5 | 9 |
| **Levels 4–9** | **12 / 120** | **16 / 120** | **61 / 120** | **61 / 120** |

### Paired comparison on the 120 runs (exact McNemar test)

| Comparison | Solved only by the first | Solved only by the second | p |
|---|---:|---:|---:|
| Gated against current, fixed | 50 | 5 | < 0.001 |
| Gated against random search | 18 | 18 | 1.0 |
| Current, fixed against step 1 | 14 | 10 | 0.54 |
| Current, fixed against random search | 4 | 49 | < 0.001 |

### Generation of the first goal (solved runs only)

| Configuration | Range | Median per level | Solved after generation 31 |
|---|---|---|---|
| NSGA-II, current objectives, fixed | 2–61 | 6–10 | 1 of 16 |
| NSGA-II, gated | 2–100 | 7–62 | 28 of 61 |
| Random search | 1–100 | 7–57 | 28 of 61 |

### Predictions against results

| Prediction (section 5) | Result |
|---|---|
| The O6 fix alone clearly raises NSGA-II above 12 of 120 | **Failed.** 16 of 120, not distinguishable from 12 (p = 0.54). |
| Gating raises the count further, if H2b holds | **Held.** 61 against 16 (p < 0.001). |
| Gated against random search: open | **Equal.** 61 against 61, and each solves 18 runs the other does not. |

### Findings

1. **H2b is confirmed, and it was the limiting cause.** Ungated `Edits` and `Actions` kept the search on small programs that cannot reach the goal. Gating them roughly quadruples the number of solved runs with no change to NSGA-II.
2. **The early stall of O2 is gone.** With ungated objectives 15 of 16 successes come by generation 31. With gating, 28 of 61 come later, the same as random search. The stall was caused by the objectives, not by NSGA-II as such. H1 (loss of diversity) is therefore not needed as an independent cause.
3. **A working `closestToGoal` did not help while the quality objectives were ungated.** Fixing O6 changed 12 to 16. Whatever Progress contributes was drowned out by the pull towards small programs.
4. **Gated NSGA-II is no better than random search at finding a first solution.** This says nothing yet about how good the solutions are; see finding 6. Before a solution exists, Progress is the only objective that differs between candidates, so this comparison is a direct test of Progress as a guide. Selecting on it gives the same number of solved runs as not selecting at all, with the same distribution over generations. On these levels Progress gives the search no measurable advantage.
5. **Levels 8 and 10 are unchanged.** Level 8 is solved once in 20 runs at best; level 10 was not rerun. This remains the generator question (H4).
6. **The first solutions of gated NSGA-II are already somewhat better than those of random search.** Read from the output of the same runs, at the moment the run stopped:

   | Level | Blocks, gated (median, range) | Blocks, random search | Actions, gated (median) | Actions, random search (median) |
   |---:|---|---|---:|---:|
   | 4 | 11 (9–11) | 11 (always) | 18.5 | 18 |
   | 5 | 8 (7–8) | 8 (always) | 10.5 | 11 |
   | 6 | 10 (8–10) | 10 (always) | 37 | 53 |
   | 7 | 8 (7–8) | 8 (always) | 34 | 40 |
   | 9 | 8 (5–8) | 7 (always) | 25 | 35 |

   - Random search always returns a program of one fixed size per level; it has no means to find a smaller one.
   - In the 43 runs solved by both, gated NSGA-II has fewer blocks in 12, the same number in 31, and more in none.
   - This understates the difference: both stop at the first solution, so NSGA-II never gets to minimise `Edits`, `Blocks` and `Actions` among solutions. A comparison of solution quality needs runs without the early stop.

### Limits of this measurement

- Finding 4 is compatible with two explanations that this benchmark cannot separate: Progress is uninformative on these levels (H2a), or Progress is informative and the variation operators lose what it gains (H3). E1 tests the first without running a search; E2 tests the second.
- 20 seeds per level: differences of a few runs on one level (for example level 9, 5 against 9) are within noise. The totals are the reliable numbers.
- The run stops at the first solution, so nothing is measured about `Edits`, `Blocks` and `Actions` among solutions, which is where gating gives them their role.
- Synthesis from an empty program only. In repair, gating lets the search move away from the user's program before the goal is reached; this is untested.
- The step 1 columns were not rerun. Random search does not use the objectives, so its numbers carry over.

### Consequences

- **Gated objectives become the working fitness function.** They remove the measured disadvantage against random search at no cost.
- **The next step is Progress**, as agreed: it is now the only thing guiding the search towards the goal, and it performs like no guidance. A replacement or a second guiding objective has to beat 61 of 120 under the same budget.
- **E1 is the instrument for that step.** Any candidate for Progress can be checked on the enumerated programs (does a better value mean fewer edits to a solution?) before a search is run, and E1 on the current Progress shows whether H2a or H3 explains finding 4.

## 8. Results of E1: landscape analysis (2026-10-01)

**Question asked:** does a better objective value mean a program that is fewer block edits away from a solution? Asked for today's Progress (H2a), for finer definitions of Progress, and for candidates for a second guiding objective. No search is run, and there is no randomness: every program is enumerated and scored once, so there are no seeds.

### Setup

- **Tool:** `blocky_momot/src/blocky_momot/LandscapeAnalysis.java`. Raw output in `blocky_momot/analysis/landscape/` (per level: summary per objective, means per distance, list of solutions).
- **Program space:** every program up to a block limit, in the vocabulary of the level's rule set. Bodies may be empty, because the rules create them; an if without else and an if with an empty else are the same program.

  | Levels | Rule set | Up to | Programs |
  |---|---|---|---:|
  | 4, 5 | `no_conds` | 8 blocks | 2,270,689 |
  | 6, 7 | `no_else` | 6 blocks | 4,612,469 |
  | 8, 9, 10 | full | 5 blocks | 1,014,161 |

- **Values:** `GoalReached`, `closestToGoal` and `Actions` come from the same `BlockySimulator` calls the search uses. The other measures come from a second simulation that records what the robot visits.
- **Distance to a solution:** number of single edits (insert or delete one childless block, change one kind or condition) to the nearest solution, by breadth-first search from all solutions.
- **Checks, passed on every level:** the number of programs equals the closed-form count; the second simulation agrees with `BlockySimulator` on goal reached and step count for every program; the distance equals `BlockyProgramDistance` to the nearest solution on a random sample of 300 programs.

### Measures

- **Rank correlation** (Spearman) between objective value and distance, over all non-solutions. Positive means a better value goes with fewer edits. Thresholds set beforehand: below 0.1 uninformative, above 0.3 informative.
- **Standing of programs one edit from a solution:** their average rank among all non-solutions. 0.5 means they score like a random program, 1.0 that they score best.
- **Neighbour test:** for a single edit that brings a program closer to a solution, how often the value gets better and how often worse.
- **Most common value:** the share of programs that have it. A high share means most edits change nothing.

### Solutions per level

| Level | Solutions in the space | Smallest solution |
|---:|---:|---:|
| 4 | 428 | 5 blocks |
| 5 | 985 | 5 blocks |
| 6 | 1,995 | 4 blocks |
| 7 | 1,948 | 4 blocks |
| 8 | 4 | 5 blocks |
| 9 | 138 | 4 blocks |
| 10 | 4 | 5 blocks |

Most solutions on levels 4–7 and 9 are a small solution plus blocks that do no harm (for example after the goal is reached). On level 9 there are 3 solutions with 4 blocks and 135 with 5. Levels 8 and 10 have 4 solutions in about a million programs, 1 in 250,000. The figure of 4 in 31,722 in O4 came from a different program space and could not be reproduced; the count of 4 solutions is the same.

### Today's Progress (H2a)

| Level | Rank correlation | Standing one edit from a solution | Closer neighbour better / worse | Most common value | Gated NSGA-II against random search (section 7) |
|---:|---:|---:|---|---:|---|
| 4 | 0.12 | 0.78 | 8.0% / 3.5% | 73% | 6 against 5 |
| 5 | 0.31 | 0.81 | 14.5% / 2.6% | 73% | 20 against 19 |
| 6 | 0.01 | 0.71 | 4.6% / 4.7% | 83% | 15 against 17 |
| 7 | 0.01 | 0.71 | 4.6% / 4.7% | 83% | 14 against 11 |
| 8 | −0.02 | 0.55 | 2.0% / 5.7% | 84% | 1 against 0 |
| 9 | −0.04 | 0.66 | 3.0% / 5.2% | 84% | 5 against 9 |
| 10 | −0.03 | 0.56 | 2.1% / 5.7% | 84% | not run |

### Finer definitions of Progress (rank correlation)

| Definition | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Today's Progress | 0.12 | 0.31 | 0.01 | 0.01 | −0.02 | −0.04 | −0.03 |
| Closest cell ever visited | 0.13 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |
| Cell where the robot ends | 0.09 | 0.30 | 0.02 | 0.02 | −0.02 | −0.03 | −0.02 |
| Closest cell, then "did not crash" | 0.13 | 0.31 | −0.42 | −0.42 | −0.44 | −0.52 | −0.44 |
| Closest cell, then more cells visited | 0.15 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |
| Cells visited alone | 0.16 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |

### Candidates for a second guiding objective

For A and B the robot is started at every cell of the shortest start-to-goal route, with the heading it has on arrival there.

- **A, suffix coverage:** number of route cells from which the program reaches the goal.
- **B, route following:** from each route cell, the number of cells the robot then follows along the route, summed.
- **C, program shape (reference only):** has a loop, has an if inside a loop.

Rank correlation:

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---:|---:|---:|---:|---:|---:|---:|
| A, suffix coverage | 0.11 | 0.33 | 0.01 | 0.01 | −0.01 | −0.01 | −0.01 |
| B, route following | 0.20 | 0.38 | 0.01 | −0.03 | −0.02 | −0.05 | −0.03 |
| C, program shape | 0.12 | −0.03 | 0.88 | 0.88 | 0.56 | 0.83 | 0.56 |

Closer neighbour better / worse, in percent:

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---|---|---|---|---|---|---|
| A, suffix coverage | 3.1 / 2.2 | 16.1 / 3.0 | 4.1 / 3.9 | 4.2 / 4.0 | 1.7 / 6.1 | 3.6 / 5.7 | 0.3 / 1.1 |
| B, route following | 10.2 / 6.8 | 18.8 / 3.8 | 5.2 / 5.7 | 6.5 / 7.6 | 2.1 / 7.7 | 4.8 / 8.9 | 2.2 / 8.4 |
| C, program shape | 0.2 / 0.0 | 0.1 / 0.1 | 3.6 / 0.9 | 3.6 / 0.9 | 3.0 / 7.0 | 4.3 / 2.7 | 3.0 / 7.0 |

### Predictions against results

| Prediction | Result |
|---|---|
| Progress is uninformative (correlation below 0.1) on the levels where NSGA-II does not beat random search (section 4, E1) | **Held for levels 6–10. Failed for levels 4 and 5,** where Progress is informative (0.12 and 0.31) and gated NSGA-II still only matches random search. |
| A finer definition of Progress follows the distance better (idea of 2026-10-01) | **Failed.** All position-based variants are within 0.02 of today's Progress. "Did not crash" is strongly misleading (−0.42 to −0.52). |
| Suffix coverage gives partial credit to near-solutions on the loop levels (section 5, step 3 discussion) | **Failed.** On level 10, programs one edit from a solution reach the goal from 0.2 route cells on average, the solutions from 8.75. |

### Findings

1. **H2a is confirmed for levels 6–10.** Where the robot gets to says nothing about how close the program is to a solution. A single edit towards a solution improves Progress as often as it worsens it (levels 6, 7) or less often (levels 8–10).
2. **On levels 4 and 5 Progress is informative, and NSGA-II still does not beat random search.** H2a does not explain these two levels. What remains is H3 (the operators), or the signal being too weak: 73% of programs share one value. Level 5 is also at the ceiling for both algorithms.
3. **No behaviour-based measure works on the loop levels.** From one start or from every route cell, a program that is one block short of a solution behaves like a random program. A program behaves well only once the whole structure is in place: the loop, the condition inside it, and the right actions.
4. **"Did not crash" points the wrong way.** On the loop levels an almost-right loop ends in a crash (step limit or wall), and the programs that end cleanly are short straight-line ones.
5. **Program shape follows the distance, but the number is partly circular** (see limits), and for single edits on levels 8 and 10 it also points the wrong way.
6. **The dip between straight-line programs and loop programs is in the rules, not only in the measurement.** The rules insert and delete one block at a time. Turning `F F L` into a loop around `F F L` means deleting the blocks and rebuilding them inside the loop, and every behaviour measure gets worse on the way.
7. **Enumeration solves every level.** All solutions up to 5 blocks, including levels 8 and 10, are found in 20–30 seconds including the analysis. No search run has solved level 10.

### Limits of this measurement

- **The edit distance has no wrap or unwrap step.** A program without a loop is therefore far from every loop solution by construction. On levels 8 and 10, 74% of programs sit at the maximum distance of 10. This affects the rank correlation and the neighbour test alike, and it is why the correlation of program shape is partly circular. Finding 6 says the same restriction holds for the rules.
- **Programs are weighted equally and limited to 5–8 blocks.** The search runs at 8–12 rule applications and samples programs differently.
- **Neighbours beyond the block limit are not counted.**
- **`closestToGoal` as implemented is not exactly "closest cell visited".** For a top-level loop it only considers where the loop ends. It differs from the closest cell visited in 0.2–6% of programs; the effect on the results above is negligible.
- **Front composition (H2b) was not measured here.** It was tested directly in the benchmark instead (section 7).

## 9. Plan from here (2026-10-01)

**Where the argument stands.** Gating fixed the interference between the objectives (H2b, section 7). E1 shows that on the loop levels no objective based on the robot's behaviour can guide a search that moves one block at a time (section 8). The remaining cause is in the moves and in the starting material, not in the objectives. NSGA-II stays unchanged in all steps below.

### P1: Wrap moves (changes the rules)

- **Hypothesis:** with a move that wraps existing blocks in a loop or an if, and one that unwraps them, a program can gain structure without losing what it already does. Progress then becomes informative on levels 6–10.
- **Decision (2026-10-01):** the rules are written first and tried directly in the tool and the benchmark; the landscape test is done afterwards only if the benchmark result needs explaining.
- **Rules (implemented, not yet benchmarked):** built by `tools/henshin-prototype/WrapOpsPatcher.java` (`run.sh wrap`) into new `*_wrap.henshin` files; the existing rule files are untouched.
  - *Wrap:* a block and everything after it in the same body moves into a new loop, if or if-else, which takes the block's place. At the first block this wraps the whole program.
  - *Unwrap:* the reverse, for a loop, if or if-else that is the last block of its body; an else branch must be empty.
  - *Search move:* one move that inserts a block three times as often as it restructures (wrap or unwrap).
  - *Switch:* `-Dblocky.rules.wrap=true` (`BLOCKY_WRAP=true` for the run script and Docker). Off by default.
  - *Checks:* `run.sh verify-wrap` applies the rules to small programs; all checks pass.
- **Benchmark:** levels 8–10 only, gated objectives, with and without wrap moves, same seeds and budget. Level 10 has no gated baseline yet, so the baseline is run too.
- **Prediction:** with wrap moves, gated NSGA-II solves more runs on levels 8–10 than without.
- **Landscape test, if needed:** add wrap and unwrap as edits in `LandscapeAnalysis` (both to the neighbourhood and to the distance) and repeat the measures of section 8. Expected if the moves help: on levels 6–10 the rank correlation of Progress rises above 0.1.
- **If the benchmark does not improve:** the benchmark alone cannot say whether wrap moves fail to make the landscape climbable or whether the search cannot use them; the landscape test separates the two. P3 and P4 remain either way.

### P2: Longer programs, then minimise (benchmark runs only)

- **Reason:** solutions are more common among larger programs (level 9: 3 solutions with 4 blocks, 135 with 5). In step 1, twice the solution length raised random search from 61 to 78 of 120 and NSGA-II from 12 to 26.
- **Runs:** gated NSGA-II and random search on levels 4–9 at twice the canonical length, with the early stop and without it.
- **Predictions:** (a) with the early stop, gated NSGA-II at double length solves more than 61 of 120. (b) Without the early stop, gated NSGA-II ends with fewer blocks and fewer actions than random search. This is where gating should show its advantage: section 7, finding 6, already shows it at the first solution (fewer blocks in 12 of 43 shared runs, more in none).
- **What it decides:** whether "find any solution, then shrink it" is a workable division of labour between the generator and the gated objectives.

### P3: Better starting material (levels 8 and 10)

- **Reason:** levels 8 and 10 have 4 solutions in about a million programs of up to 5 blocks; random generation with the current budget does not reach them (H4).
- **Options, to be compared:** seed the first population with program skeletons (for example a loop containing an if-else), or with all enumerated programs up to a small size.
- **Depends on:** E4 (what the generator produces now), and on P1, because wrap moves may make seeding unnecessary.

### P4: Exact enumeration as a first stage

- **Reason:** section 8, finding 7. Enumeration by increasing block count returns the smallest solutions for every level within seconds.
- **Role:** run before the search for small programs; MOMoT handles repair and programs beyond the enumerable size.
- **Depends on:** open question 1 (must the solution stay inside MOMoT/Henshin?).

### Order

1. P2 runs now; it needs no new code.
2. P1 is tested in the landscape tool in parallel.
3. P3 and P4 are decided after P1 and P2.

### What is no longer planned

- A further behaviour-based guiding objective (step 3 as originally written, and the Generality idea): measured and found uninformative on the levels that need it.
- Refining `closestToGoal`: no variant differs from it by more than 0.02 in rank correlation.

## 10. Open questions

1. Must the solution stay inside MOMoT/Henshin, or is an exact fallback acceptable?
2. Which use case is the priority: synthesis from an empty program, or repair of a nearly correct one?
3. What budget is acceptable in the game (seconds per search)?
4. Should the level's block limit be enforced as a constraint in the fitness? It is currently only enforced by the game.
5. Is the benchmark target "at least one run in ten succeeds" or a reliable success rate (for example 90%)?
6. Are the thresholds in E1 (correlation below 0.1 as uninformative, above 0.3 as informative) acceptable?
