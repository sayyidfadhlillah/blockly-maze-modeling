#!/usr/bin/env bash
# PROTOTYPE: build the *_edit_anywhere.henshin modules from the originals, or verify them.
# Usage (from repo root): tools/henshin-prototype/run.sh [patch|verify|wrap|verify-wrap]   (default: patch)
#
# wrap:        writes <name>_wrap.henshin (wrap/unwrap moves) for the originals and the *_edit_anywhere modules.
# verify-wrap: checks the *_wrap.henshin modules.
# patch:  reads blocky_model/transformations/<name>.henshin (left untouched) and writes
#         <name>_edit_anywhere.henshin next to it (see ModifyOpsPatcher.java for the added rules).
# verify: applies the generated rules to small programs and checks the expected behaviour.
# Needs: JDK 17+, Henshin/EMF jars from libs/ and EMF from the local Maven repo (see CP below).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)"
M2="${M2_REPO:-$HOME/.m2/repository}/org/eclipse/emf"
SEP=":"; [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && SEP=";" && M2="$(cygpath -m "$M2")"

CP="$REPO/libs/org.eclipse.emf.henshin.interpreter_1.8.0.202302121604.jar"
CP+="$SEP$REPO/libs/org.eclipse.emf.henshin.model_1.8.0.202302121604.jar"
CP+="$SEP$REPO/libs/org.eclipse.equinox.common_3.20.300.v20251111-0312.jar"
CP+="$SEP$(ls "$M2"/org.eclipse.emf.common/*/org.eclipse.emf.common-*.jar | grep -v sources | sort -V | tail -1)"
CP+="$SEP$(ls "$M2"/org.eclipse.emf.ecore/*/org.eclipse.emf.ecore-*.jar | grep -v sources | sort -V | tail -1)"
CP+="$SEP$(ls "$M2"/org.eclipse.emf.ecore.xmi/*/org.eclipse.emf.ecore.xmi-*.jar | grep -v sources | sort -V | tail -1)"
# Henshin evaluates attribute conditions with a JS engine; JDK 15+ ships none, so add Nashorn (+ ASM).
CP+="$SEP$REPO/libs/nashorn-core-15.4.jar"
for j in "$REPO"/libs/asm*.jar; do CP+="$SEP$j"; done

MODULES=(statement_insertions_henshin_text statement_insertions_no_else statement_insertions_no_conds statement_insertions_atomic_only)
# Wrap moves (WrapOpsPatcher.java): for the originals and for the *_edit_anywhere modules. atomic_only has
# no loop or if to wrap in. The *_wrap files are only used with -Dblocky.rules.wrap=true.
WRAP_BASES=(statement_insertions_henshin_text statement_insertions_no_else statement_insertions_no_conds
            statement_insertions_henshin_text_edit_anywhere statement_insertions_no_else_edit_anywhere statement_insertions_no_conds_edit_anywhere)
case "${1:-patch}" in
  verify)      MAIN=VerifyPatchedRules; FILES=("${MODULES[@]/%/_edit_anywhere.henshin}") ;;
  wrap)        MAIN=WrapOpsPatcher;     FILES=("${WRAP_BASES[@]/%/.henshin}") ;;
  verify-wrap) MAIN=VerifyWrapRules;    FILES=("${WRAP_BASES[@]/%/_wrap.henshin}") ;;
  *)           MAIN=ModifyOpsPatcher;   FILES=("${MODULES[@]/%/.henshin}") ;;
esac
OUT="$(mktemp -d)"; [[ "$SEP" == ";" ]] && OUT="$(cygpath -m "$OUT")"
javac -nowarn -cp "$CP" -d "$OUT" "$REPO/tools/henshin-prototype/$MAIN.java"
java -cp "$CP$SEP$OUT" "$MAIN" "$REPO" "${FILES[@]}"
