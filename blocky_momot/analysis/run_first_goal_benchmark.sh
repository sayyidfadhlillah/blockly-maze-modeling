#!/usr/bin/env bash
# First-Goal Benchmark Runner Script for MoMoT across all 10 Blockly maze levels.
# Measures time to first goal and generation number with early stopping on goal discovery.
# Default parameters: 30 runs, pop=150, 100 iterations (15,000 max evals).
# Overrides supported via BLOCKY_* environment variables.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SESSION="${1:-first_goal_$(date +%Y%m%d_%H%M%S)}"
FROM_LEVEL="${BLOCKY_FROM_LEVEL:-1}"
TO_LEVEL="${BLOCKY_TO_LEVEL:-10}"
RUNS="${BLOCKY_RUNS:-30}"
POP_SIZE="${BLOCKY_POP_SIZE:-150}"
ITERATIONS="${BLOCKY_ITERATIONS:-100}"
MAX_EVAL="${BLOCKY_MAX_EVAL:-$((POP_SIZE * ITERATIONS))}"
# NSGA_II (default), MEMETIC_NSGA_II, RANDOM_SEARCH (baseline without selection) or
# IMMIGRANTS_NSGA_II (NSGA-II + new random candidates every generation)
ALGORITHM="${BLOCKY_ALGORITHM:-NSGA_II}"
# Multiplies the canonical solutionLength of every level (default 1)
SOL_LEN_FACTOR="${BLOCKY_SOL_LEN_FACTOR:-1}"
# CURRENT (default) or GATED (Edits, Actions and Blocks only count for candidates that reach the goal)
OBJECTIVES="${BLOCKY_OBJECTIVES:-CURRENT}"
# true = use the *_wrap.henshin rule files (wrap/unwrap moves, see tools/henshin-prototype); default false
WRAP="${BLOCKY_WRAP:-false}"

LOG="$ROOT/blocky_momot/analysis/first_goal_benchmark_${SESSION}.log"
CP_FILE="$ROOT/blocky_game/target/first-goal-benchmark.cp"

echo "======================================================="
echo "First-Goal MOMoT Benchmark"
echo "Session:     $SESSION"
echo "Levels:      $FROM_LEVEL..$TO_LEVEL"
echo "Runs/Level:  $RUNS"
echo "Pop Size:    $POP_SIZE"
echo "Iterations:  $ITERATIONS"
echo "Max Evals:   $MAX_EVAL"
echo "Algorithm:   $ALGORITHM"
echo "SolLen x:    $SOL_LEN_FACTOR"
echo "Objectives:  $OBJECTIVES"
echo "Wrap moves:  $WRAP"
echo "Log File:    $LOG"
echo "======================================================="

mvn -pl blocky_game,blocky_momot -am compile -q
mvn -pl blocky_game -q dependency:build-classpath -Dmdep.outputFile=target/first-goal-benchmark.cp -DincludeScope=runtime

cd "$ROOT/blocky_game"

java \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  -cp "target/classes;../blocky_momot/target/classes;../blocky_model/src-gen;$(cat "$CP_FILE")" \
  -Dblocky.benchmarkSession="$SESSION" \
  -Dblocky.analysisDir="$ROOT/blocky_momot/analysis" \
  -Dblocky.fromLevel="$FROM_LEVEL" \
  -Dblocky.toLevel="$TO_LEVEL" \
  -Dblocky.runs="$RUNS" \
  -Dblocky.populationSize="$POP_SIZE" \
  -Dblocky.iterations="$ITERATIONS" \
  -Dblocky.maxEvaluations="$MAX_EVAL" \
  -Dblocky.algorithm="$ALGORITHM" \
  -Dblocky.solutionLengthFactor="$SOL_LEN_FACTOR" \
  -Dblocky.objectives="$OBJECTIVES" \
  -Dblocky.rules.wrap="$WRAP" \
  blocky_game.MomotFirstGoalBenchmarkRunner \
  2>&1 | tee "$LOG"

echo "Benchmark completed."
RAW_CSV="$ROOT/blocky_momot/analysis/first_goal_benchmark_raw_${SESSION}.csv"
SUMMARY_CSV="$ROOT/blocky_momot/analysis/first_goal_benchmark_summary_${SESSION}.csv"
echo "Raw CSV:     $RAW_CSV"
echo "Summary CSV: $SUMMARY_CSV"

echo "Generating visualization plots..."
cd "$ROOT/blocky_momot/analysis"
python plot_first_goal_benchmark.py "$SESSION"
