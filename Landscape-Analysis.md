# Landscape Analysis (Experiment E1)

Status: wrap-off analysis on levels 4–10 done 2026-10-01; wrap-on analysis on levels 4–10 done 2026-10-02, at the block limit of the wrap-off runs. A gate sweep on levels 6–10 (which Progress threshold a relaxed gate would admit, section 8) was added on 2026-10-02. The 6-block runs on levels 8–10 (sensitivity to the block limit) have not been run. This document is the dedicated report of the analysis; the surrounding argument is in `Exploration-Proposal.md` (sections 4 and 8).

## 1. Question

Does a better objective value mean a program that is fewer block edits away from a solution?

This is asked for:

- today's Progress objective (`closestToGoal`), to test hypothesis H2a of the proposal ("`closestToGoal` is not informative");
- finer definitions of Progress;
- three candidates for a second guiding objective (A, B and C below);
- the same objectives when the wrap and unwrap moves of proposal section 9 (P1) count as single edits.

No search is run, so the result does not depend on the algorithm, the generator or a random seed. Every program in a bounded space is enumerated and scored once.

## 2. Setup

**Tool:** `blocky_momot/src/blocky_momot/LandscapeAnalysis.java`.

```
LandscapeAnalysis <level.xmi> <no_conds|no_else|full> <maxBlocks> <outDir> [wrap]
```

### Program space

Every program up to a block limit, in the block vocabulary of the rule set the level uses. Bodies may be empty, because the rules create them. An if without else and an if with an empty else are the same program.

| Levels | Rule set | Limit | Programs |
|---|---|---|---:|
| 4, 5 | `no_conds` (move, turn, loop) | 8 blocks | 2,270,689 |
| 6, 7 | `no_else` (adds if without else) | 6 blocks | 4,612,469 |
| 8, 9, 10 | full (adds if with else) | 5 blocks | 1,014,161 |

Program text used in the output files: `F`/`L`/`R` = move forward / turn left / turn right, `W(..)` = repeat until goal, `a(..)` `l(..)` `r(..)` = if path ahead / left / right; in the full vocabulary an if is written `a(then|else)`.

### Evaluation

For each program:

- `GoalReached`, `closestToGoal` and `Actions` come from the same `BlockySimulator` calls the search uses. A program that reaches the goal is a solution.
- The other measures come from a second, independent simulation that records what the robot visits.

### Edits and distance to a solution

The distance of a program is the number of single edits to the nearest solution, computed by breadth-first search outwards from all solutions.

| Edit | Wrap off | Wrap on |
|---|---|---|
| Insert one childless block | yes | yes |
| Delete one childless block | yes | yes |
| Change the kind of a block, or the condition of an if | yes | yes |
| **Wrap:** a block and everything after it in the same body moves into a new loop, if or if-else | no | yes |
| **Unwrap:** a loop, if or if-else that is the last block of its body is replaced by its content (else branch must be empty) | no | yes |

Wrap and unwrap follow the semantics of the `*_wrap.henshin` rules (`tools/henshin-prototype/WrapOpsPatcher.java`). Each costs 1. A wrap adds a block, so it is only allowed for programs below the block limit. Unwrapping an empty body is left out, because it equals deleting the childless block.

### Objectives compared (all minimised)

**Progress and variants**

| Name | Definition |
|---|---|
| `closestToGoal_official` | As the search computes it (`BlockySimulator.distanceToGoalOrPenalty`) |
| `minDistanceVisited` | Smallest maze distance to the goal over every cell the robot visits |
| `finalDistance` | Distance of the cell where the robot ends |
| `minDist_thenNoCrash` | `minDistanceVisited`, ties broken by not ending in a crash |
| `minDist_thenCells` | `minDistanceVisited`, ties broken by more distinct cells visited |
| `minDist_thenNoCrash_thenCells` | Both tie-breakers |
| `cellsVisited` | More distinct cells visited, on its own |

**Candidates for a second guiding objective.** For A and B the robot is started at every cell of the shortest start-to-goal route, with the heading it has on arrival there.

| Name | Definition |
|---|---|
| **A**, `suffixCoverage` | Number of route cells from which the program reaches the goal |
| **B**, `routeFollowing` | From each route cell, the number of cells the robot then follows along the route, summed |
| **C**, `programShape` | Reference only: has a loop, has an if inside a loop |

### Measures

- **Rank correlation** (Spearman) between objective value and distance, over all non-solutions. Positive means a better value goes with fewer edits. Thresholds set beforehand: below 0.1 uninformative, above 0.3 informative.
- **Standing of programs one edit from a solution:** their average rank among all non-solutions. 0.5 means they score like a random program, 1.0 that they score best.
- **Neighbour test:** for a single edit that brings a program closer to a solution, how often the value gets better and how often worse.
- **Most common value (plateau share):** the share of programs that have it. A high share means most edits change nothing.
- Also written to the CSV files: local optima, rank correlation restricted to programs at the block limit, the number of distinct values.

### Checks

- **Wrap off, every level:** the number of programs equals the closed-form count; the second simulation agrees with `BlockySimulator` on goal reached and step count for every program (0 differences); the distance equals `BlockyProgramDistance` to the nearest solution on a random sample of 300 programs (0 mismatches).
- **Wrap on:** `BlockyProgramDistance` has no wrap or unwrap, so that check does not apply. It is replaced by a symmetry check on a sample of 3,000 programs: every enumerated neighbour of a program must have that program as a neighbour (0 mismatches on levels 4–10). The new edits were not compared against the output of the Henshin rules themselves.
- **Regression (2026-10-02):** after the wrap edits to the tool, the wrap-off run on levels 4–10 reproduces the 21 result files of 2026-10-01 byte for byte.

## 3. Solutions per level

| Level | Solutions in the space | Smallest solution |
|---:|---:|---:|
| 4 | 428 | 5 blocks |
| 5 | 985 | 5 blocks |
| 6 | 1,995 | 4 blocks |
| 7 | 1,948 | 4 blocks |
| 8 | 4 | 5 blocks |
| 9 | 138 | 4 blocks |
| 10 | 4 | 5 blocks |

Most solutions on levels 4–7 and 9 are a small solution plus blocks that do no harm (for example after the goal is reached). On level 9 there are 3 solutions with 4 blocks and 135 with 5. Levels 8 and 10 have 4 solutions in about a million programs, 1 in 250,000. Enumeration solves every level: all solutions up to the limit are found in 20–30 seconds including the analysis.

## 4. Results without wrap moves (levels 4–10, 2026-10-01)

### How to read the measures

Every program that does not reach the goal (a non-solution) has two numbers: its **objective value** (all objectives are minimised, so a smaller value is better) and its **distance**, the number of single edits to the nearest solution. An objective is a good guide for a search if a better value means a smaller distance. The measures below check this in different ways. Each table in this section is read per level (the columns L4–L10, or the rows of the first table).

**Rank correlation** (Spearman).
- *What it is:* all non-solution programs are ordered by objective value, and separately by distance (equal values share an average position). The rank correlation says how closely the two orderings agree. It runs from −1 to +1.
- *Sign:* positive means programs with a better value tend to be fewer edits from a solution, which is what a guide should do. Near 0 means the value tells nothing about the distance. Negative means it misleads: better-valued programs tend to be farther away.
- *How to read the size:* it is a measure of tendency over all programs, not a probability for a single pair. The thresholds fixed before the analysis are **below 0.1 uninformative, above 0.3 informative**, and in between weak. Example: on level 5 a value of 0.31 means the objective follows the distance to a clear extent; on level 8 a value of −0.02 means knowing the objective value tells nothing about how far a program is from a solution.

**Standing one edit from a solution.**
- *What it is:* take the programs that are exactly one edit from a solution, rank every non-solution by objective value, and average where those programs land. 1.0 means they have the best value of all, 0.5 means they score like a random program, 0 means the worst.
- *How to read it:* a good guide puts near-solutions at the top, so values close to 1.0 are good. It only looks at distance 1, while the rank correlation looks at all distances, so the two can differ.

**Closer neighbour better / worse** (the neighbour test).
- *What it is:* take every pair of programs that are one edit apart and where the edit moves towards a solution (the distance gets smaller). Two percentages: in how many of these edits the objective value gets better, and in how many it gets worse. The rest stay unchanged, which is why the two numbers do not add up to 100%.
- *How to read it:* what matters is the comparison of the two numbers. Better clearly above worse means that a step in the right direction is rewarded. About equal means a step towards a solution is as likely to hurt as to help, so the search gets no signal. Worse above better means the objective punishes the right move. The numbers are small when the objective has large plateaus, because most edits leave the value unchanged.

**Most common value (plateau share).** The share of non-solution programs that have the single most frequent objective value. A high share (for example 84%) means that most programs, and so most edits, cannot be told apart by this objective.

### Today's Progress (hypothesis H2a)

How to read: one row per level, for the objective the search uses today (`closestToGoal`: the smallest maze distance to the goal that the robot reaches). The four columns are the measures above. Row 5, for example: correlation 0.31 (informative), programs one edit from a solution rank at 0.81 on average (better than 81% of the non-solutions), an edit towards a solution improves Progress in 14.5% of cases and worsens it in 2.6%, and 73% of programs share the most common value. Row 6 shows the opposite: correlation 0.01 and 4.6% better against 4.7% worse, so Progress cannot tell a good edit from a bad one.

| Level | Rank correlation | Standing one edit from a solution | Closer neighbour better / worse | Most common value |
|---:|---:|---:|---|---:|
| 4 | 0.12 | 0.78 | 8.0% / 3.5% | 73% |
| 5 | 0.31 | 0.81 | 14.5% / 2.6% | 73% |
| 6 | 0.01 | 0.71 | 4.6% / 4.7% | 83% |
| 7 | 0.01 | 0.71 | 4.6% / 4.7% | 83% |
| 8 | −0.02 | 0.55 | 2.0% / 5.7% | 84% |
| 9 | −0.04 | 0.66 | 3.0% / 5.2% | 84% |
| 10 | −0.03 | 0.56 | 2.1% / 5.7% | 84% |

### Finer definitions of Progress (rank correlation)

How to read: each row is a different way of defining "how close the robot got to the goal", and each cell is the rank correlation of that definition on that level. Compare every row with the first one in the same column: a better definition would show a clearly higher value. A row with a large negative value (here "did not crash") is not just useless but misleading: it prefers programs that are farther from a solution. The rows are described in section 2.

| Definition | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Today's Progress | 0.12 | 0.31 | 0.01 | 0.01 | −0.02 | −0.04 | −0.03 |
| Closest cell ever visited | 0.13 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |
| Cell where the robot ends | 0.09 | 0.30 | 0.02 | 0.02 | −0.02 | −0.03 | −0.02 |
| Closest cell, then "did not crash" | 0.13 | 0.31 | −0.42 | −0.42 | −0.44 | −0.52 | −0.44 |
| Closest cell, then more cells visited | 0.15 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |
| Cells visited alone | 0.16 | 0.31 | 0.02 | 0.02 | −0.01 | −0.03 | −0.01 |

### Candidates A, B and C (rank correlation)

How to read: each row is a candidate for a second guiding objective (defined in section 2), each cell is the rank correlation on that level, and the thresholds are the same (below 0.1 uninformative, above 0.3 informative). A candidate is worth using if it is clearly higher than Today's Progress in the table above. C is only a reference: it ignores the robot's behaviour and scores whether the program has a loop and an if inside a loop. Its high values on levels 6–10 mostly reflect that the solutions are loop programs and the edit distance (without wrap) cannot turn a loop-free program into a loop in one step. They are not evidence that C would guide a search.

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---:|---:|---:|---:|---:|---:|---:|
| A, suffix coverage | 0.11 | 0.33 | 0.01 | 0.01 | −0.01 | −0.01 | −0.01 |
| B, route following | 0.20 | 0.38 | 0.01 | −0.03 | −0.02 | −0.05 | −0.03 |
| C, program shape | 0.12 | −0.03 | 0.88 | 0.88 | 0.56 | 0.83 | 0.56 |

Closer neighbour better / worse, in percent:

How to read: the neighbour test of the candidates. Each cell has two numbers, "better / worse": among edits that move a program towards a solution, the percentage in which the candidate's value improves, and the percentage in which it worsens. A cell like 16.1 / 3.0 (A, level 5) means a step in the right direction is rewarded about five times as often as it is punished. A cell like 2.1 / 7.7 (B, level 8) means the objective punishes right steps more often than it rewards them. 3.6 / 0.9 for C on level 6 rewards them, but see the note on C above.

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---|---|---|---|---|---|---|
| A, suffix coverage | 3.1 / 2.2 | 16.1 / 3.0 | 4.1 / 3.9 | 4.2 / 4.0 | 1.7 / 6.1 | 3.6 / 5.7 | 0.3 / 1.1 |
| B, route following | 10.2 / 6.8 | 18.8 / 3.8 | 5.2 / 5.7 | 6.5 / 7.6 | 2.1 / 7.7 | 4.8 / 8.9 | 2.2 / 8.4 |
| C, program shape | 0.2 / 0.0 | 0.1 / 0.1 | 3.6 / 0.9 | 3.6 / 0.9 | 3.0 / 7.0 | 4.3 / 2.7 | 3.0 / 7.0 |

### Reading

1. **H2a is confirmed for levels 6–10.** Where the robot gets to says nothing about how close the program is to a solution. A single edit towards a solution improves Progress as often as it worsens it (levels 6, 7) or less often (levels 8–10).
2. **On levels 4 and 5 Progress is informative**, and gated NSGA-II still does not beat random search (proposal section 7). H2a does not explain these two levels.
3. **No behaviour-based measure works on the loop levels.** From one start or from every route cell, a program one block short of a solution behaves like a random program. On level 10, programs one edit from a solution reach the goal from 0.2 route cells on average, the solutions from 8.75.
4. **"Did not crash" points the wrong way** (−0.42 to −0.52): an almost-right loop ends in a crash, and the programs that end cleanly are short straight-line ones.
5. **C follows the distance, but partly by construction.** The edit distance has no wrap step, so a program without a loop is far from every loop solution. On levels 8 and 10, 74% of programs sit at the maximum distance of 10.

## 5. Results with wrap moves (levels 4–10, 2026-10-02)

Wrap off is the result of section 4, reproduced exactly. Wrap on counts wrap and unwrap as single edits (section 2). Each level uses the block limit of section 2: 8 blocks for levels 4–5, 6 for levels 6–7 and 5 for levels 8–10. Levels 4 and 5 use the vocabulary without if, so only loops can be wrapped there. Raw data: `blocky_momot/analysis/landscape_wrap/`. The measures are explained at the start of section 4. In the tables below, each cell shows "wrap off → wrap on".

### Distance to a solution

How to read: the distance is the number of edits to the nearest solution. If wrap works as intended, programs get closer to a solution, so the maximum and the mean distance fall and fewer programs sit at the maximum distance. A program at the maximum distance is as far from a solution as any program in the space.

| Level | Maximum distance | Mean distance | Programs at maximum distance |
|---:|---:|---:|---:|
| 4 | 10 → 8 | 5.24 → 4.96 | 0.3% → 2.4% |
| 5 | 8 → 8 | 4.78 → 4.73 | 0.9% → 0.8% |
| 6 | 8 → 7 | 6.03 → 4.25 | 31.6% → 0.2% |
| 7 | 8 → 7 | 6.03 → 4.26 | 31.6% → 0.2% |
| 8 | 10 → 8 | 9.27 → 6.55 | 74.2% → 28.8% |
| 9 | 8 → 7 | 6.87 → 4.82 | 55.7% → 1.8% |
| 10 | 10 → 8 | 9.27 → 6.55 | 74.2% → 28.8% |

Programs per distance (distance 0 = solutions) for levels 8–10. The other levels are in the `by_distance` files:

| Level | Wrap | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 8, 10 | off | 4 | 42 | 682 | 2,154 | 15,309 | 11,702 | 67,532 | 5,771 | 126,951 | 31,704 | 752,310 |
| 8, 10 | on | 4 | 46 | 1,148 | 5,251 | 50,006 | 82,507 | 402,292 | 180,887 | 292,020 | | |
| 9 | off | 138 | 1,101 | 9,712 | 27,413 | 64,576 | 76,795 | 182,973 | 86,253 | 565,200 | | |
| 9 | on | 138 | 1,219 | 18,111 | 73,458 | 274,384 | 369,733 | 258,788 | 18,330 | | | |

### Rank correlation

How to read: the same measure as in section 4 (positive and above 0.3 is informative, below 0.1 uninformative, negative misleading). A cell such as −0.04 → 0.29 means the objective did not follow the distance without wrap and follows it clearly with wrap. Values at or above 0.3 after wrap are in bold.

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Progress (`closestToGoal`) | 0.12 → 0.18 | 0.31 → **0.31** | 0.01 → **0.31** | 0.01 → **0.31** | −0.02 → 0.08 | −0.04 → 0.29 | −0.03 → 0.08 |
| Closest cell, then "did not crash" | 0.13 → 0.19 | 0.31 → **0.32** | −0.42 → 0.11 | −0.42 → 0.11 | −0.44 → −0.04 | −0.52 → 0.08 | −0.44 → −0.04 |
| Cells visited alone | 0.16 → 0.22 | 0.31 → **0.32** | 0.02 → **0.31** | 0.02 → **0.31** | −0.01 → 0.08 | −0.03 → 0.29 | −0.01 → 0.08 |
| A, suffix coverage | 0.11 → 0.16 | 0.33 → **0.33** | 0.00 → **0.32** | 0.01 → **0.32** | −0.01 → 0.09 | −0.01 → **0.35** | −0.01 → 0.10 |
| B, route following | 0.20 → 0.29 | 0.38 → **0.38** | 0.01 → **0.34** | −0.03 → **0.36** | −0.02 → 0.13 | −0.05 → **0.38** | −0.03 → 0.16 |
| C, program shape | 0.12 → −0.08 | −0.03 → −0.07 | 0.88 → 0.15 | 0.88 → 0.15 | 0.56 → 0.13 | 0.83 → 0.12 | 0.56 → 0.13 |

### Closer neighbour better / worse, in percent

How to read: among edits that move a program towards a solution, the percentage that improve the objective and the percentage that worsen it (section 4). Better clearly above worse is a good guide; about equal gives no signal; worse above better misleads.

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---|---|---|---|---|---|---|
| Progress | 8.0 / 3.5 → 8.4 / 2.6 | 14.5 / 2.6 → 13.4 / 2.3 | 4.6 / 4.7 → 9.0 / 2.0 | 4.6 / 4.7 → 9.0 / 2.0 | 2.0 / 5.7 → 3.5 / 4.7 | 3.0 / 5.2 → 8.7 / 2.6 | 2.1 / 5.7 → 3.6 / 4.6 |
| A, suffix coverage | 3.1 / 2.2 → 3.0 / 1.8 | 16.1 / 3.0 → 14.9 / 2.6 | 4.1 / 3.9 → 8.5 / 1.1 | 4.2 / 4.0 → 8.6 / 1.1 | 1.7 / 6.1 → 3.2 / 4.9 | 3.6 / 5.7 → 9.5 / 2.5 | 0.3 / 1.1 → 1.3 / 0.5 |
| B, route following | 10.2 / 6.8 → 10.4 / 5.6 | 18.8 / 3.8 → 17.3 / 3.2 | 5.2 / 5.7 → 10.4 / 2.3 | 6.5 / 7.6 → 13.1 / 3.4 | 2.1 / 7.7 → 5.3 / 5.6 | 4.8 / 8.9 → 13.9 / 4.6 | 2.2 / 8.4 → 5.9 / 5.8 |
| C, program shape | 0.2 / 0.0 → 0.1 / 0.2 | 0.1 / 0.1 → 0.1 / 0.1 | 3.6 / 0.9 → 2.1 / 5.9 | 3.6 / 0.9 → 2.1 / 5.9 | 3.0 / 7.0 → 2.3 / 8.5 | 4.3 / 2.7 → 1.8 / 6.7 | 3.0 / 7.0 → 2.3 / 8.5 |

### Standing of programs one edit from a solution

How to read: 1.0 means programs one edit from a solution have the best value of all non-solutions, 0.5 means they score like a random program (section 4). Wrap changes which programs are one edit from a solution, so this value moves only a little.

| Objective | L4 | L5 | L6 | L7 | L8 | L9 | L10 |
|---|---|---|---|---|---|---|---|
| Progress | 0.78 → 0.78 | 0.81 → 0.81 | 0.71 → 0.72 | 0.71 → 0.73 | 0.55 → 0.58 | 0.66 → 0.68 | 0.56 → 0.59 |
| A, suffix coverage | 0.53 → 0.53 | 0.81 → 0.81 | 0.70 → 0.72 | 0.71 → 0.73 | 0.60 → 0.60 | 0.74 → 0.75 | 0.56 → 0.57 |
| B, route following | 0.76 → 0.76 | 0.84 → 0.84 | 0.74 → 0.76 | 0.77 → 0.78 | 0.64 → 0.66 | 0.76 → 0.78 | 0.71 → 0.72 |
| C, program shape | 0.50 → 0.50 | 0.50 → 0.50 | 0.85 → 0.80 | 0.85 → 0.80 | 0.92 → 0.86 | 0.92 → 0.86 | 0.92 → 0.86 |

The most common value does not change with wrap (73% for Progress on levels 4–5, 83% on levels 6–7, 84% on levels 8–10), because wrap changes the edits, not the objective values.

### Predictions against results

The predictions were written before the wrap runs.

| Prediction | Result |
|---|---|
| With wrap, the rank correlation of Progress rises above 0.1 on levels 6–10 | **Held for levels 6, 7 and 9** (0.31, 0.31, 0.29). **Failed for levels 8 and 10** (0.08). |
| The circularity of C shrinks: if C is still strong with wrap it carries real information, if it drops the earlier value came from the missing wrap step | **C drops** from 0.56–0.88 to 0.12–0.15 on levels 6–10, so the earlier value came from the missing wrap step. On levels 4 and 5 it was already about 0 and is slightly negative now. |
| A and B change little | **Wrong on levels 6, 7 and 9**, where both rise from about 0 to 0.32–0.38. Right on levels 4, 5, 8 and 10, where they move no more than Progress does. |

### Reading

1. **Wrap shortens the distance to a solution on every level except 5.** On levels 6–7 and 9, nearly all programs are now close to a solution: the share at the maximum distance drops to 0.2% and 1.8%. Level 5 does not change (maximum distance 8, mean 4.78 → 4.73).
2. **Levels 6, 7 and 9: the move set was the bottleneck.** Progress goes from uninformative to 0.31, 0.31 and 0.29, and an edit towards a solution improves it about four times as often as it worsens it on levels 6–7 (9.0% against 2.0%) and about three times as often on level 9 (8.7% against 2.6%). A and B follow, to 0.32–0.38. This changes the conclusion for hypothesis H2a (section 4, point 1): on these levels the conclusion that Progress is uninformative was partly a statement about the move set, not only about Progress.
3. **Levels 4 and 5: little change.** Both were already informative without wrap, and both only have loops to wrap. Progress goes from 0.12 to 0.18 on level 4 and stays at 0.31 on level 5. The share of right-direction edits that improve Progress even drops slightly on level 5 (14.5% to 13.4%).
4. **Levels 8 and 10: wrap alone is not enough.** The maximum distance falls from 10 to 8, but Progress stays below 0.1 and an edit towards a solution improves it about as often as it worsens it (3.5% against 4.7%, and 3.6% against 4.6%). These two levels have 4 solutions in about a million programs. Whether the sparse solutions are the reason was not tested.
5. **B is consistently the best of the objectives with wrap**, but by a small margin: it is 0.03–0.11 above Progress on all seven levels (for example 0.38 against 0.29 on level 9, 0.16 against 0.08 on level 10). A is about equal to Progress. Whether this margin matters for a search is not measured here.
6. **C is not a useful guide.** Without the missing wrap step its correlation is 0.12–0.15 on levels 6–10, and its neighbour test points the wrong way on all five (about 2% better against 6–8% worse).
7. **The "did not crash" misleading effect disappears** on levels 6–10 (−0.42 to −0.52 becomes −0.04 to +0.11). It was a property of the missing wrap step too.
8. **Levels 6 and 7 give nearly the same numbers, and levels 8 and 10 give the same numbers** for Progress, its variants and C. Levels 8 and 10 differ for A and B (A 0.09 and 0.10, B 0.13 and 0.16; the plateau share of A is 84% on level 8 and 98% on level 10). The cause of the identical values was not investigated.

## 6. Limits

- **Block limit.** The distance is computed over enumerated programs only, so a path through a program above the limit is invisible and the distance can be overestimated, never underestimated. A wrap adds a block, so a program at the limit cannot be wrapped. The programs at the limit are the largest part of the space. This affects the wrap-on results most. On levels 6, 7 and 9 almost no program is left at the maximum distance (0.2% and 1.8%), so the limit does not seem to distort them much; on levels 8 and 10, 29% still are, and the limit may be part of why those stay uninformative. The 6-block runs below test it.
- **Programs are weighted equally and limited to 5–8 blocks.** The search runs at 8–12 rule applications and samples programs differently. The real search move inserts a block three times as often as it restructures; the analysis does not model that weighting.
- **The edit distance is a choice.** Block-level edits with wrap and unwrap are one way to define "closer to a solution". The search recombines rule sequences and does not move in this neighbourhood, so the analysis measures whether the objectives are informative about program structure, not whether they are informative for the operators of the search. That second question is experiment E2 of the proposal.
- **`closestToGoal` as implemented is not exactly "closest cell visited".** For a top-level loop it only considers where the loop ends. It differs from the closest cell visited in 0.2–6% of programs (for example 48,583 of 1,014,161 on level 8); the effect on the results is negligible.
- **The wrap rules are modelled, not executed.** The analysis implements wrap and unwrap as string edits with the semantics of the `*_wrap.henshin` rules. It was checked for symmetry, not compared with the output of the Henshin rules.
- **With millions of programs, every correlation is statistically significant.** The thresholds are on effect size. A value of 0.29 against 0.31 should not be read as a difference between informative and uninformative.

## 7. Not done

1. **6-block runs on levels 8–10, wrap off and on.** The sensitivity check for the block limit (about 25 million programs per level, roughly 6 GB, a larger JVM heap). The plan is to report both the whole space and the programs of at most 5 blocks from the same run. A +2 or +3 limit is not feasible with the current design (about 620 million and 15 billion programs on levels 8–10).
2. **Why levels 8 and 10 stay uninformative with wrap.** Candidates are the block limit and the sparse solutions (4 in about a million programs). Neither was tested.
3. **Whether the gain on levels 6, 7 and 9 carries over to the search.** E1 only says that the landscape is more climbable. Whether NSGA-II makes use of it is measured by the benchmark of proposal section 9, P1 (gated objectives, with and without wrap moves). That benchmark has not been run, and it was planned for levels 8–10 only, which are the levels where wrap did not make Progress informative here.

## 8. Gate sweep: which Progress threshold for the gated objectives (levels 6–10, 2026-10-02)

### Question

The gated objectives (`blocky.objectives=GATED`, proposal section 7) give `Edits`, `Actions` and `Blocks` their real value only for candidates that reach the goal, that is, Progress = 0. The proposal asks whether the gate can be relaxed to "Progress ≤ T" for some T > 0, so that candidates close to the goal already compete on size. This section measures which programs such a gate would admit, for every T, on the enumerated programs of levels 6–10. It does not run a search.

### Setup

- **Programs, solutions, edits and distance:** as in section 2. Wrap off uses the edits of section 4, wrap on those of section 5. The levels use the same block limits as before (6 blocks for levels 6–7, 5 blocks for levels 8–10).
- **Route length R:** the maze distance from the start cell to the goal. Progress is the smallest maze distance to the goal over the cells the robot visits, so it runs from R (the robot never leaves the start) down to 0 (the goal is reached). R is 16 on levels 6 and 8, 12 on level 7 and 10 on levels 9 and 10.
- **A gate at T** admits a non-solution if its Progress is at most T. Every solution has Progress 0 and always passes. T = 0 is the strict gate used so far.
- **Measures per T**, computed over the admitted non-solutions:
  - the number and the share of all non-solutions admitted;
  - *purity*: the share of everything that passes that is a real solution, solutions ÷ (solutions + admitted non-solutions);
  - the mean edit distance to a solution of the admitted programs, and of the programs that fail the gate;
  - the share of admitted programs within 2 edits of a solution;
  - the share smaller than the smallest solution (the tiny programs that a relaxed gate would reward for being small, the problem that gating was introduced to remove).
- **Output:** `blocky_momot/analysis/landscape_gate/{off,wrap}/levelN_gate.csv`, one row per T from 0 to R. The other result files of the tool are unchanged.

### How to read the measures

- **Purity** close to 1 means that the gate admits almost nothing but solutions, so it behaves like the strict gate. A purity near 0 means that the admitted non-solutions far outnumber the solutions, and the gate no longer tells solutions and non-solutions apart.
- **Mean distance of admitted against failing programs.** If a relaxed gate selects programs that are close to a solution, the admitted programs have a much smaller distance than the failing ones. Equal values mean the gate selects nothing useful, and a larger distance for the admitted ones means it selects the wrong programs.
- **Within 2 edits** is the share of the admitted programs that are almost solutions. High values are what a relaxed gate is for.
- **Tiny share:** a high value means that the gate rewards programs for being small before they solve anything.

### Results

**What each threshold admits.** The number of non-solutions admitted, and in brackets their share of all non-solutions when it is large enough to show:

| Level | R | Solutions | T = 3 | T = R − 5 | Last T below the jump | First T at the jump |
|---:|---:|---:|---:|---:|---|---|
| 6 | 16 | 1,995 | 3 | 83 | 11: 83 | 12: 142,603 (3.1%) |
| 7 | 12 | 1,948 | 37 | 2,010 | 7: 2,010 | 8: 139,000 (3.0%) |
| 8 | 16 | 4 | 0 | 283 | 13: 726 | 14: 78,690 (7.8%) |
| 9 | 10 | 138 | 8 | 16 | 8: 4,318 | 9: 159,771 (15.8%) |
| 10 | 10 | 4 | 0 | 0 | 7: 320 | 8: 57,843 (5.7%) |

Out of 1–5 million programs per level, the number admitted is tiny up to the jump and then rises by a factor of 35 to 1,700 in a single step of T.

**What the admitted programs look like** (wrap off → wrap on). "Admitted" and "failing" are the mean distance to a solution:

| Level | T | Admitted | Failing | Within 2 edits | Tiny share |
|---:|---:|---:|---:|---:|---:|
| 6 | 11 | 5.98 → 2.55 | 6.03 → 4.25 | 0.20 → 0.36 | 0% |
| 7 | 7 | 1.35 → 1.17 | 6.03 → 4.26 | 0.96 → 0.97 | 0% |
| 8 | 11 | 4.87 → 3.92 | 9.27 → 6.55 | 0.13 → 0.15 | 2.1% |
| 8 | 13 | 7.67 → 4.77 | 9.27 → 6.55 | 0.06 → 0.07 | 2.1% |
| 9 | 5 | 4.94 → 2.94 | 6.87 → 4.82 | 0.50 → 0.50 | 0% |
| 9 | 8 | 6.66 → 3.54 | 6.87 → 4.82 | 0.08 → 0.20 | 0.1% |
| 10 | 7 | 9.42 → 5.02 | 9.27 → 6.55 | 0.003 → 0.009 | 2.2% |

For T = 3 and for level 10 at T = R − 5 = 5 there is nothing to describe, because (almost) no non-solution passes. For T = 3, the numbers of admitted non-solutions above show the whole effect.

### Reading

1. **A threshold of 3 is the strict gate in practice.** On levels 6–10 no more than 37 non-solutions out of millions of programs reach a Progress of 3 or less (3, 37, 0, 8 and 0). On levels 8 and 10 none does. Relaxing the gate to 3 would change the search on these levels very little.
2. **The admitted share does not grow smoothly with T. It jumps.** Up to a certain T only a handful of programs pass. At the next T, 3–16% of all programs do, and purity falls by a factor of 30–120 in that one step (to 0.014 or less). The jump is at T = R − 4 on levels 6 and 7, R − 2 on levels 8 and 10, and R − 1 on level 9. I did not investigate why; it is consistent with the fact that most programs that move at all move only a few cells from the start.
3. **A threshold of R − 5 is below the jump on all five levels.** It is the largest value of the form R − c that is below the jump on all five levels; a larger c would be safe too, a smaller one would not (R − 4 jumps on levels 6 and 7). The value is read off five levels and not derived from a principle.
4. **What R − 5 admits depends strongly on the level.**
   - **Level 7:** about 2,000 non-solutions, which is as many as there are solutions, and 96% are within 2 edits of a solution. This is the one level where a relaxed gate admits a useful set of near-solutions.
   - **Level 6:** 83 non-solutions. Without wrap they are no closer to a solution than the failing programs (5.98 against 6.03 edits); with wrap they are (2.55 against 4.25).
   - **Level 9:** 16 non-solutions, half of them within 2 edits. Just below the jump (T = 8) there are 4,318, of which 8% (wrap off) or 20% (wrap on) are within 2 edits.
   - **Level 8:** 283 non-solutions, 13% within 2 edits. They are closer to a solution than the failing ones (4.9 against 9.3 edits), and about 2% are smaller than the smallest solution.
   - **Level 10:** no program passes at T = 5. At T = 7, 320 do, and they are not close to a solution (0.3% within 2 edits). Without wrap they are even farther from a solution (9.4 edits) than the failing programs (9.3).
5. **Only levels 8 and 10 admit tiny programs** (about 2% of the admitted programs). On levels 6, 7 and 9 almost none of the admitted programs is smaller than the smallest solution, so the risk that motivated the gate does not show up there at these thresholds.
6. **Wrap does not change which programs pass.** The number admitted and the purity are identical with wrap off and on, because wrap changes the edits and not the Progress values. It changes how close the admitted programs are to a solution: for example on level 6 at T = 11 the share within 2 edits rises from 0.20 to 0.36, and on level 9 at T = 8 from 0.08 to 0.20.
7. **There is no single best fixed threshold.** The levels differ in R (10, 12 and 16) and in where the jump is. A threshold relative to the route length, such as R − 5, is the only rule that is safe on all five. The measurement does not show that it helps: with R − 5 the gate admits 0–283 non-solutions on four of the five levels, and the search draws 150 candidates per generation from a space where these programs are rarer than 1 in 3,000.

### Limits

- **Landscape, not search.** The sweep counts enumerated programs. It does not show what NSGA-II meets. A benchmark of a relaxed gate against the strict gate (61 of 120 on levels 4–9, section 7 of the proposal) has not been run.
- **Programs up to the block limit only,** weighted equally, as in section 6. The search runs at 8–12 rule applications and samples differently.
- **Levels 4 and 5 were not swept,** and neither were levels 1–3.
- **Small counts.** Several numbers rest on a few dozen programs (for example 16 on level 9 at T = 5, 83 on level 6 at T = 11). Their shares are not reliable to more than one digit.
- **R − 5 is an empirical rule fitted to five levels.** It has not been tested on another maze.

## 9. Reproduce

Compile (the classes in `blocky_model/target/classes` and `blocky_momot/target/classes` must be built; the jars are the EMF ecore, common and ecore.xmi jars from the local Maven repository):

```
javac -cp <emf jars>;blocky_model/target/classes;blocky_momot/target/classes -d <out> blocky_momot/src/blocky_momot/LandscapeAnalysis.java
```

Run from the repository root, one level at a time. Levels 8–10 take about 30 seconds each at 5 blocks with a 4 GB heap; levels 6–7 (`no_else`, limit 6) take about 2.5 minutes and levels 4–5 (`no_conds`, limit 8) about 1 minute each with 8 GB:

```
java -Xmx4g -cp <out>;<same classpath> blocky_momot.LandscapeAnalysis blocky_momot/model/input/8.xmi full 5 blocky_momot/analysis/landscape
java -Xmx4g -cp <out>;<same classpath> blocky_momot.LandscapeAnalysis blocky_momot/model/input/8.xmi full 5 blocky_momot/analysis/landscape_wrap wrap
```

The arguments are the level file, the rule set (`no_conds` for levels 4–5, `no_else` for 6–7, `full` for 8–10), the block limit (8, 6 and 5) and the output directory, followed by `wrap` for the wrap-on analysis.

Output per level in the given directory: `levelN_summary.csv` (one row per objective and all measures), `levelN_by_distance.csv` (mean objective values per distance), `levelN_solutions.txt` (solutions per block count and up to 200 programs) and `levelN_gate.csv` (the gate sweep of section 8: one row per threshold).

| Files | Contents |
|---|---|
| `blocky_momot/analysis/landscape/` | Levels 4–10, wrap off (2026-10-01) |
| `blocky_momot/analysis/landscape_wrap/` | Levels 4–10, wrap on, same limits (2026-10-02) |
| `blocky_momot/analysis/landscape_gate/off/`, `.../wrap/` | Gate sweep, levels 6–10, wrap off and on (2026-10-02) |
