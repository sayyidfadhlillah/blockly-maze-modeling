# Agent Instructions

This repository contains **Blocky Maze**, a Java/JavaFX application integrating an EMF model with a Blockly web app via an embedded WebView.

## Architecture & Entry Points
- **JavaFX App**: `blocky_game/src/blocky_game/Main.java`
- **EMF Model**: `blocky_model/model/blocky.ecore` (metamodel) and `src-gen/` (generated API).
- **Blockly Web App**: Embedded in `blocky_game/src/blocky_game/blockly-games-web/`.
- **Sync Logic**: `BlockyUI.java` (JSBridge) and `GameEngine.java` (Logic).

## Critical Commands
- **Build**: `mvn clean compile` (run from root).
- **Run App**: `mvn -pl blocky_game javafx:run` (run from root).
- **Working Directory**: Must be `blocky_game` when running (Maven handles this via `-pl`). If running manually, `load.xmi` and assets are expected in the working directory.
- **EMF Codegen**: `src-gen/` is checked in. If you edit `.ecore`, you **must** regenerate code using Eclipse EMF (right-click `.genmodel` -> Generate Model Code). Maven only compiles existing code.
- **NEVER MODIFY SRC-GEN**: Any file in a `src-gen` folder is automatically managed. **NEVER manually edit files in `src-gen`.** If a change is needed, modify the source model/configuration and ask the user to regenerate the code in Eclipse.

## Framework & Toolchain Quirks
- **JSBridge**: JavaFX WebView uses **weak references** for `window.javaBridge`. `BlockyUI` keeps a strong field reference `jsBridge` to prevent GC.
- **Case Sensitivity**: Blockly uses camelCase (e.g., `turnRight`). `GameEngine` methods like `rebuildProgram` and `parseCondition` use `.toLowerCase()` for robust matching—preserve this.
- **WebView Sync**: Injected JS in `BlockyUI.injectSyncScript` polls for workspace and hooks the "Run" button via `MutationObserver`.
- **Sync Safety**: Use the `suppressSync` flag in `BlockyUI` when applying state from Java to WebView to prevent stale JS state from overwriting the model during the update.
- **Deterministic Simulation**: `GameEngine.simulateUserProgram` relies on `determineStartOrientation`. If not explicitly set in EMF, it infers it from the `START` cell's neighbors.

## Modeling (Henshin & MOMoT)
- **Henshin Rules**: MOMoT requires `.henshin` (XMI). `blocky_model/transformations/statement_insertions.henshin_text` is a textual source, but it is **out of date**: it has no if-else rule, and the variants (`no_else`, `no_conds`, `atomic_only`, `no_loops`) have no text source. Treat the compiled `.henshin` files as the source of truth, and do not recompile them from the text without checking the result.
- **Rule Compilation**: Right-click `.henshin_text` -> **Transform to Henshin** in Eclipse (only for the original base module, see above).
- **NSURI Patching**: After compiling `.henshin`, ensures the EPackage URI is `http://www.example.org/blocky#` instead of a relative path to `.ecore`.
- **MOMoT Config**: `.momot` files must register the EMF package in `initialization` and `model.adapt`.

### Generated rule modules (do not edit by hand)
- `statement_insertions_*_edit_anywhere.henshin`, `*_wrap.henshin` and `*_edit_anywhere_wrap.henshin` are **generated** by `tools/henshin-prototype/run.sh` from the original `.henshin` files, which are left untouched. They have **no `.henshin_text` source**: never recompile them from text and never edit them by hand, regenerate them.
- `run.sh patch` writes `*_edit_anywhere` (insert + delete + modify of user-placed blocks, as one search move `EditAnywhere`). `run.sh wrap` writes `*_wrap` and `*_edit_anywhere_wrap` (adds wrap/unwrap moves); run `patch` first, because `wrap` reads the `*_edit_anywhere` files.
- After regenerating, run `run.sh verify` and `run.sh verify-wrap`; both must end with `ALL CHECKS PASSED`.
- The game loads the `*_edit_anywhere_wrap` module by default (`MomotFirstGoalBenchmarkRunner.withWrapMoves`). The switches only turn parts off: `-Dblocky.rules.wrap=false` drops the wrap/unwrap moves, `-Dblocky.rules.editAnywhere=false` keeps the original module name. There is no variant for `atomic_only`, `no_loops` and `_uri`.
- If a module has a unit named `EditAnywhere`, `blocky_custom.createModuleManager` makes it the **only** search move (all other units are removed).

### Search objectives and switches
- The search objectives are overridden in `blocky_momot/src/blocky_momot_runner/blocky_custom.java`, not in `src-gen`: `closestToGoal` is computed per evaluation (the generated version read `Cell.distanceToGoal`, which was only set for one input and returned the constant 100000 for other levels), and `Edits`, `Actions` and `Blocks` are **gated** (only counted for candidates that reach the goal).
- JVM system properties. Everything is on by default; a property only switches a feature off (the Docker entrypoint and the benchmark script pass `BLOCKY_OBJECTIVES` and `BLOCKY_WRAP`, defaults `GATED` and `true`):

| Property | Values (default) | Meaning |
|---|---|---|
| `blocky.objectives` | `GATED` (default), `CURRENT` | `CURRENT` turns the gate off (original objectives) |
| `blocky.rules.wrap` | `true` (default), `false` | `false` turns the `*_wrap` rule variants (wrap/unwrap) off |
| `blocky.rules.editAnywhere` | `true` (default), `false` | `false` turns the `*_edit_anywhere` variant (insert, delete, modify) off for base module names |
| `blocky.algorithm` | `NSGA_II` (default), `MEMETIC_NSGA_II`; benchmarks: `RANDOM_SEARCH`, `IMMIGRANTS_NSGA_II` | search algorithm |
| `blocky.nonGoalArchive` | integer, `0` in code, `10` set by `Main.java` | number of non-goal candidates kept for the solution panel (0 = off) |

- **Solution panel**: lists every candidate, including those that do not reach the goal (no checkbox). Under gated objectives a goal-reaching candidate dominates all non-goal ones, so the Pareto front keeps at most one near-miss; `ParetoFrontPublisherListener` therefore also keeps an archive of the non-goal candidates that came closest to the goal (copies with real `Edits`, `Actions` and `Blocks`), and `blocky_custom` writes them with the front. The panel finds each model file through the objective values in its name, so two files with the same values would overwrite each other.
- Background, benchmark results and the reasoning are in `Improvement-Plan.md`, `Benchmark-Analysis.md`, `Exploration-Proposal.md` and `Landscape-Analysis.md`.

## File Storage
- **load.xmi / save.xmi**: Default locations for model persistence in `blocky_game/`.
- **direct_manipulation_request.xmi**: Written when the user clicks a cell in the WebView to trigger synthesis.

## Verification
- No automated Java tests are currently implemented. The only automated checks are the rule checks `tools/henshin-prototype/run.sh verify` (edit/delete/modify) and `verify-wrap` (wrap/unwrap), which apply the generated rules to small programs.
- **Manual Verification**: Run the app, load a level, click "Run Program", and verify the execution trace appears in the console/output.
- **Linter/Checkstyle**: None configured in Maven; follow existing Eclipse/Java conventions (4-space indent).
