import java.util.*;
import java.util.function.Supplier;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.*;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.henshin.interpreter.*;
import org.eclipse.emf.henshin.interpreter.impl.*;
import org.eclipse.emf.henshin.model.*;
import org.eclipse.emf.henshin.model.Module;
import org.eclipse.emf.henshin.model.resource.HenshinResourceSet;

/**
 * PROTOTYPE check: applies the *_edit_anywhere.henshin rules to small programs and asserts the outcome
 * (modify/delete only touch user-placed blocks; EditAnywhere never wastes a step on an empty program).
 * Usage: java VerifyPatchedRules <repoRoot> <X_edit_anywhere.henshin>...   (see run.sh)
 */
public class VerifyPatchedRules {
    static EPackage pkg;
    static int failures = 0;

    static EClass cls(String n) { return (EClass) pkg.getEClassifier(n); }
    static EObject mk(String n) { return pkg.getEFactoryInstance().create(cls(n)); }
    static void set(EObject o, String f, Object v) { o.eSet(o.eClass().getEStructuralFeature(f), v); }
    static Object get(EObject o, String f) { return o.eGet(o.eClass().getEStructuralFeature(f)); }
    static Object lit(String e, String l) { return ((EEnum) pkg.getEClassifier(e)).getEEnumLiteral(l).getInstance(); }

    static EObject atomic(String kind, boolean generated) {
        EObject s = mk("AtomicStatement"); set(s, "kind", lit("AtomicStatementKind", kind)); set(s, "generated", generated); return s;
    }
    static EObject loop(EObject... inner) { EObject s = mk("Loop"); set(s, "body", body(inner)); return s; }
    static EObject ifStmt(String cond, boolean withElse, EObject... thenInner) {
        EObject s = mk("IfStmt"); set(s, "condition", lit("ConditionKind", cond));
        set(s, "thenBody", body(thenInner));
        if (withElse) set(s, "elseBody", body());
        return s;
    }
    /** A body whose containers hold the given statements (null = empty container). */
    static EObject body(EObject... stmts) {
        EObject b = mk("Body"); EObject prev = null;
        for (EObject s : stmts) {
            EObject c = mk("Container"); if (s != null) set(c, "statement", s);
            if (prev == null) set(b, "firstContainer", c); else set(prev, "next", c);
            prev = c;
        }
        return b;
    }
    static EObject container(EObject body, int idx) {
        EObject c = (EObject) get(body, "firstContainer");
        for (int i = 0; i < idx; i++) c = (EObject) get(c, "next");
        return c;
    }
    static String show(EObject body) {
        StringBuilder sb = new StringBuilder("[");
        for (EObject c = (EObject) get(body, "firstContainer"); c != null; c = (EObject) get(c, "next")) {
            EObject s = (EObject) get(c, "statement");
            if (sb.length() > 1) sb.append(", ");
            if (s == null) sb.append("_");
            else if (s.eClass().getName().equals("AtomicStatement")) sb.append(get(s, "kind"));
            else if (s.eClass().getName().equals("Loop")) sb.append("Loop").append(show((EObject) get(s, "body")));
            else {
                sb.append("If(").append(get(s, "condition")).append(")").append(show((EObject) get(s, "thenBody")));
                if (get(s, "elseBody") != null) sb.append("Else").append(show((EObject) get(s, "elseBody")));
            }
        }
        return sb.append("]").toString();
    }
    static void check(String what, Object actual, Object expected) {
        boolean ok = Objects.equals(actual, expected);
        if (!ok) failures++;
        System.out.printf("  %s %-62s %s%n", ok ? "PASS" : "FAIL", what, ok ? actual : ("got " + actual + ", expected " + expected));
    }

    /** Which delete rules can remove the container at idx; applies the first one and returns the program. */
    static String[] tryDelete(Module m, Engine engine, Supplier<EObject> program, int idx) {
        return tryDelete(m, engine, program, b -> container(b, idx));
    }

    static String[] tryDelete(Module m, Engine engine, Supplier<EObject> program,
                              java.util.function.Function<EObject, EObject> target) {
        EObject b = program.get();
        EObject curr = target.apply(b);
        EGraph g = new EGraphImpl(b);
        List<String> rules = new ArrayList<>();
        Unit deleteAny = m.getUnit("DeleteContainerAnywhere");
        for (Unit u : ((IndependentUnit) deleteAny).getSubUnits()) {
            Rule r = (Rule) u;
            Match pm = new MatchImpl(r);
            pm.setNodeTarget(r.getLhs().getNode("curr"), curr);
            if (engine.findMatches(r, g, pm).iterator().hasNext()) rules.add(r.getName());
        }
        if (rules.size() == 1) {
            Rule r = (Rule) m.getUnit(rules.get(0));
            RuleApplication app = new RuleApplicationImpl(engine, g, r, null);
            app.setPartialMatch(new MatchImpl(r));
            app.getPartialMatch().setNodeTarget(r.getLhs().getNode("curr"), curr);
            if (!app.execute(null)) return new String[]{ String.join(",", rules), "APPLY FAILED" };
            long orphans = g.getRoots().stream().filter(x -> x != b).count();
            return new String[]{ rules.get(0), show(b) + (orphans > 0 ? " +" + orphans + " orphan roots" : "") };
        }
        return new String[]{ rules.isEmpty() ? "none" : String.join(",", rules), show(b) };
    }

    static boolean applyEdit(Module m, Engine engine, EGraph g, Random rnd) {
        UnitApplication ua = new UnitApplicationImpl(engine, g, m.getUnit("EditAnywhere"), null);
        ua.setParameterValue("k", lit("AtomicStatementKind", new String[]{ "TURN_LEFT", "TURN_RIGHT", "MOVE_FORWARD" }[rnd.nextInt(3)]));
        ua.setParameterValue("cnd", lit("ConditionKind", new String[]{ "CHECK_FORWARD", "CHECK_LEFT", "CHECK_RIGHT" }[rnd.nextInt(3)]));
        return ua.execute(null);
    }

    /** v3: EditAnywhere never wastes a step on an empty program, and still edits user blocks. */
    static void checkEditAnywhere(Module m, Engine engine) {
        check("EditAnywhere exists", m.getUnit("EditAnywhere") != null, true);
        Random rnd = new Random(7);

        EObject empty = body();
        EGraph g = new EGraphImpl(empty);
        int ok = 0;
        for (int i = 0; i < 15; i++) if (applyEdit(m, engine, g, rnd)) ok++;
        check("empty program: 15/15 EditAnywhere steps apply", ok, 15);
        check("empty program: every step inserted a block (size 15)", countStatements(empty), 15);

        boolean sawInsert = false, sawDelete = false, sawModify = false;
        for (int i = 0; i < 200; i++) {
            EObject b = body(atomic("MOVE_FORWARD", false), atomic("TURN_LEFT", false));
            String before = show(b);
            if (!applyEdit(m, engine, new EGraphImpl(b), rnd)) continue;
            int n = countStatements(b);
            if (n > 2) sawInsert = true;
            else if (n < 2) sawDelete = true;
            else if (!show(b).equals(before)) sawModify = true;
        }
        check("user program: EditAnywhere inserts, deletes and modifies", sawInsert && sawDelete && sawModify, true);
    }

    static int countStatements(EObject body) {
        int n = 0;
        for (EObject c = (EObject) get(body, "firstContainer"); c != null; c = (EObject) get(c, "next")) {
            EObject st = (EObject) get(c, "statement");
            if (st == null) continue;
            n++;
            for (String f : new String[]{ "body", "thenBody", "elseBody" }) {
                if (st.eClass().getEStructuralFeature(f) != null && get(st, f) != null) n += countStatements((EObject) get(st, f));
            }
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        String repo = args[0];
        ResourceSet ers = new ResourceSetImpl();
        ers.getResourceFactoryRegistry().getExtensionToFactoryMap().put("ecore", new XMIResourceFactoryImpl());
        pkg = (EPackage) ers.getResource(URI.createFileURI(repo + "/blocky_model/model/blocky.ecore"), true).getContents().get(0);

        for (int f = 1; f < args.length; f++) {
            HenshinResourceSet hrs = new HenshinResourceSet(repo + "/blocky_model/transformations");
            hrs.getPackageRegistry().put(pkg.getNsURI(), pkg);
            Module m = hrs.getModule(args[f], false);
            Engine engine = new EngineImpl();
            System.out.println("== " + args[f]);

            // --- Delete: every content type at every position; user-placed (generated=false) and generated ones.
            check("delete only user atomic", tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false)), 0)[0], "DeleteAtomicAtOnlyInBody");
            check("delete head user atomic -> result",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), atomic("TURN_LEFT", false)), 0)[1], "[TURN_LEFT]");
            check("delete middle GENERATED atomic is rejected",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), atomic("TURN_RIGHT", true), atomic("TURN_LEFT", false)), 1)[1],
                    "[MOVE_FORWARD, TURN_RIGHT, TURN_LEFT]");
            check("delete GENERATED empty loop is rejected",
                    tryDelete(m, engine, () -> {
                        EObject l = loop(); set(l, "generated", true);
                        return body(atomic("MOVE_FORWARD", false), l);
                    }, 1)[0], "none");
            check("delete last user atomic -> result",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), atomic("TURN_LEFT", false)), 1)[1], "[MOVE_FORWARD]");
            check("delete empty container (between) -> result",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), null, atomic("TURN_LEFT", false)), 1)[1],
                    "[MOVE_FORWARD, TURN_LEFT]");
            check("delete user empty loop (between) -> rule",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), loop(), atomic("TURN_LEFT", false)), 1)[0],
                    "DeleteEmptyLoopAtBetween");
            check("delete user empty loop -> result (no orphans)",
                    tryDelete(m, engine, () -> body(atomic("MOVE_FORWARD", false), loop(), atomic("TURN_LEFT", false)), 1)[1],
                    "[MOVE_FORWARD, TURN_LEFT]");
            check("non-empty loop is NOT deletable",
                    tryDelete(m, engine, () -> body(loop(atomic("MOVE_FORWARD", false))), 0)[0], "none");
            check("inner block of loop IS deletable -> result",
                    tryDelete(m, engine, () -> body(loop(atomic("MOVE_FORWARD", false))),
                            b -> container((EObject) get((EObject) get(container(b, 0), "statement"), "body"), 0))[1], "[Loop[]]");
            check("delete user empty if (head) -> rule",
                    tryDelete(m, engine, () -> body(ifStmt("CHECK_LEFT", false), atomic("TURN_LEFT", false)), 0)[0], "DeleteEmptyIfAtBodyHead");
            check("delete user empty if-else (last) -> rule",
                    tryDelete(m, engine, () -> body(atomic("TURN_LEFT", false), ifStmt("CHECK_LEFT", true)), 1)[0], "DeleteEmptyIfElseAtLast");
            check("non-empty if is NOT deletable",
                    tryDelete(m, engine, () -> body(ifStmt("CHECK_LEFT", false, atomic("MOVE_FORWARD", false))), 0)[0], "none");

            // --- Modify: through the exposed unit, as MOMoT calls it.
            EObject b = body(atomic("MOVE_FORWARD", false), ifStmt("CHECK_FORWARD", false));
            EGraph g = new EGraphImpl(b);
            Unit modify = m.getUnit("ModifyStatementAnywhere");
            check("ModifyStatementAnywhere exists", modify != null, true);
            Set<String> seen = new TreeSet<>();
            for (int i = 0; i < 40; i++) {
                UnitApplication ua = new UnitApplicationImpl(engine, g, modify, null);
                ua.setParameterValue("k", lit("AtomicStatementKind", i % 2 == 0 ? "TURN_RIGHT" : "TURN_LEFT"));
                ua.setParameterValue("cnd", lit("ConditionKind", i % 2 == 0 ? "CHECK_LEFT" : "CHECK_RIGHT"));
                if (ua.execute(null)) seen.add(show(b));
            }
            check("modify changes atomic kind and if condition", seen.stream().anyMatch(s -> s.startsWith("[TURN_")) && seen.stream().anyMatch(s -> s.contains("If(CHECK_LEFT)") || s.contains("If(CHECK_RIGHT)")), true);

            EObject b2 = body(atomic("MOVE_FORWARD", false));
            UnitApplication noop = new UnitApplicationImpl(engine, new EGraphImpl(b2), m.getUnit("ChangeAtomicKind"), null);
            noop.setParameterValue("k", lit("AtomicStatementKind", "MOVE_FORWARD"));
            check("no-op change (same kind) is rejected", noop.execute(null), false);

            // --- Modify/delete leave blocks the search inserted itself (generated=true) alone.
            UnitApplication genKind = new UnitApplicationImpl(engine, new EGraphImpl(body(atomic("MOVE_FORWARD", true))),
                    m.getUnit("ChangeAtomicKind"), null);
            genKind.setParameterValue("k", lit("AtomicStatementKind", "TURN_LEFT"));
            check("modify GENERATED atomic kind is rejected", genKind.execute(null), false);
            EObject genIf = ifStmt("CHECK_FORWARD", false); set(genIf, "generated", true);
            UnitApplication genCond = new UnitApplicationImpl(engine, new EGraphImpl(body(genIf)), m.getUnit("ChangeIfCondition"), null);
            genCond.setParameterValue("cnd", lit("ConditionKind", "CHECK_LEFT"));
            check("modify GENERATED if condition is rejected", genCond.execute(null), false);

            // --- Existing insert unit still works.
            EObject b3 = body(atomic("MOVE_FORWARD", false));
            UnitApplication ins = new UnitApplicationImpl(engine, new EGraphImpl(b3), m.getUnit("CreateThenInsertContainerThenPopulate"), null);
            ins.setParameterValue("k", lit("AtomicStatementKind", "TURN_LEFT"));
            if (m.getUnit("CreateThenInsertContainerThenPopulate").getParameter("cnd") != null)
                ins.setParameterValue("cnd", lit("ConditionKind", "CHECK_LEFT"));
            check("insert unit still applies", ins.execute(null), true);

            checkEditAnywhere(m, engine);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
