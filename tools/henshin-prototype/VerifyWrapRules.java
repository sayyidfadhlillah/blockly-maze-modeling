import java.util.*;
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
 * PROTOTYPE check: applies the wrap/unwrap rules of the *_wrap.henshin modules to small programs and
 * asserts the outcome. Usage: java VerifyWrapRules <repoRoot> <X_wrap.henshin>...   (see run.sh verify-wrap)
 */
public class VerifyWrapRules {
    static EPackage pkg;
    static int failures = 0;

    static EClass cls(String n) { return (EClass) pkg.getEClassifier(n); }
    static EObject mk(String n) { return pkg.getEFactoryInstance().create(cls(n)); }
    static void set(EObject o, String f, Object v) { o.eSet(o.eClass().getEStructuralFeature(f), v); }
    static Object get(EObject o, String f) { return o.eGet(o.eClass().getEStructuralFeature(f)); }
    static Object lit(String e, String l) { return ((EEnum) pkg.getEClassifier(e)).getEEnumLiteral(l).getInstance(); }

    static EObject atomic(String kind) {
        EObject s = mk("AtomicStatement"); set(s, "kind", lit("AtomicStatementKind", kind)); return s;
    }
    static EObject loop(EObject... inner) { EObject s = mk("Loop"); set(s, "body", body(inner)); return s; }
    static EObject ifElse(String cond, EObject[] thenInner, EObject[] elseInner) {
        EObject s = mk("IfStmt"); set(s, "condition", lit("ConditionKind", cond));
        set(s, "thenBody", body(thenInner));
        if (elseInner != null) set(s, "elseBody", body(elseInner));
        return s;
    }
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
        System.out.printf("  %s %-58s %s%n", ok ? "PASS" : "FAIL", what, ok ? actual : ("got " + actual + ", expected " + expected));
    }

    static EObject[] ffl() { return new EObject[] { atomic("MOVE_FORWARD"), atomic("MOVE_FORWARD"), atomic("TURN_LEFT") }; }

    /** Applies a rule with node `nodeName` bound to target; returns the program afterwards, or a failure note. */
    static String apply(Module m, Engine engine, String ruleName, EObject program, String nodeName, EObject target, String cnd) {
        Rule r = (Rule) m.getUnit(ruleName);
        if (r == null) return "NO RULE " + ruleName;
        EGraph g = new EGraphImpl(program);
        RuleApplication app = new RuleApplicationImpl(engine, g, r, null);
        Match pm = new MatchImpl(r);
        pm.setNodeTarget(r.getLhs().getNode(nodeName), target);
        app.setPartialMatch(pm);
        if (r.getParameter("cnd") != null) app.setParameterValue("cnd", lit("ConditionKind", cnd));
        if (!app.execute(null)) return "NOT APPLICABLE";
        long orphans = g.getRoots().stream().filter(x -> x != program).count();
        return show(program) + (orphans > 0 ? " +" + orphans + " orphan roots" : "");
    }

    static boolean applyUnit(Module m, Engine engine, String unit, EGraph g, Random rnd) {
        Unit u = m.getUnit(unit);
        UnitApplication ua = new UnitApplicationImpl(engine, g, u, null);
        if (u.getParameter("k") != null)
            ua.setParameterValue("k", lit("AtomicStatementKind", new String[]{ "TURN_LEFT", "TURN_RIGHT", "MOVE_FORWARD" }[rnd.nextInt(3)]));
        if (u.getParameter("cnd") != null)
            ua.setParameterValue("cnd", lit("ConditionKind", new String[]{ "CHECK_FORWARD", "CHECK_LEFT", "CHECK_RIGHT" }[rnd.nextInt(3)]));
        return ua.execute(null);
    }

    static int count(EObject body, String type) {
        int n = 0;
        for (EObject c = (EObject) get(body, "firstContainer"); c != null; c = (EObject) get(c, "next")) {
            EObject st = (EObject) get(c, "statement");
            if (st == null) continue;
            if (type == null || st.eClass().getName().equals(type)) n++;
            for (String f : new String[]{ "body", "thenBody", "elseBody" }) {
                if (st.eClass().getEStructuralFeature(f) != null && get(st, f) != null) n += count((EObject) get(st, f), type);
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
            boolean hasIf = m.getUnit("WrapTailInIfAtHead") != null;
            boolean hasIfElse = m.getUnit("WrapTailInIfElseAtHead") != null;

            // --- Wrap in a loop
            EObject p = body(ffl());
            check("wrap whole program in a loop",
                    apply(m, engine, "WrapTailInLoopAtHead", p, "c", container(p, 0), null),
                    "[Loop[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]]");
            check("new loop and its container are marked generated",
                    get(container(p, 0), "generated") + "/" + get((EObject) get(container(p, 0), "statement"), "generated"), "true/true");
            EObject inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "body"), 0);
            check("unwrap it again",
                    apply(m, engine, "UnwrapLoopAtHead", p, "c", inner, null), "[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]");

            p = body(ffl());
            check("wrap the tail from the second block",
                    apply(m, engine, "WrapTailInLoopAtAfter", p, "c", container(p, 1), null),
                    "[MOVE_FORWARD, Loop[MOVE_FORWARD, TURN_LEFT]]");
            inner = container((EObject) get((EObject) get(container(p, 1), "statement"), "body"), 0);
            check("unwrap it again",
                    apply(m, engine, "UnwrapLoopAtAfter", p, "c", inner, null), "[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]");

            p = body(ffl());
            check("head rule does not match a later block",
                    apply(m, engine, "WrapTailInLoopAtHead", p, "c", container(p, 1), null), "NOT APPLICABLE");

            // --- Unwrap limits
            p = body(loop(atomic("MOVE_FORWARD")), atomic("TURN_LEFT"));
            inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "body"), 0);
            check("loop followed by another block is not unwrapped",
                    apply(m, engine, "UnwrapLoopAtHead", p, "c", inner, null), "NOT APPLICABLE");
            check("empty loop is not unwrapped",
                    applyUnit(m, engine, "UnwrapAnywhere", new EGraphImpl(body(loop())), new Random(1)), false);
            p = body(loop(loop(atomic("MOVE_FORWARD"))));
            inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "body"), 0);
            EObject innermost = container((EObject) get((EObject) get(inner, "statement"), "body"), 0);
            check("unwrap the inner of two nested loops",
                    apply(m, engine, "UnwrapLoopAtHead", p, "c", innermost, null), "[Loop[MOVE_FORWARD]]");

            // --- Wrap in an if
            if (hasIf) {
                p = body(ffl());
                check("wrap whole program in an if",
                        apply(m, engine, "WrapTailInIfAtHead", p, "c", container(p, 0), "CHECK_LEFT"),
                        "[If(CHECK_LEFT)[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]]");
                inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "thenBody"), 0);
                check("unwrap it again",
                        apply(m, engine, "UnwrapIfAtHead", p, "c", inner, null), "[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]");
            }
            if (hasIfElse) {
                p = body(ffl());
                check("wrap the tail in an if-else",
                        apply(m, engine, "WrapTailInIfElseAtAfter", p, "c", container(p, 2), "CHECK_RIGHT"),
                        "[MOVE_FORWARD, MOVE_FORWARD, If(CHECK_RIGHT)[TURN_LEFT]Else[]]");
                inner = container((EObject) get((EObject) get(container(p, 2), "statement"), "thenBody"), 0);
                check("unwrap it again",
                        apply(m, engine, "UnwrapIfElseAtAfter", p, "c", inner, null), "[MOVE_FORWARD, MOVE_FORWARD, TURN_LEFT]");

                p = body(ifElse("CHECK_LEFT", new EObject[] { atomic("MOVE_FORWARD") }, new EObject[] { atomic("TURN_LEFT") }));
                inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "thenBody"), 0);
                check("if-else with a non-empty else is not unwrapped",
                        apply(m, engine, "UnwrapIfElseAtHead", p, "c", inner, null), "NOT APPLICABLE");
                p = body(ifElse("CHECK_LEFT", new EObject[] { atomic("MOVE_FORWARD") }, new EObject[0]));
                inner = container((EObject) get((EObject) get(container(p, 0), "statement"), "thenBody"), 0);
                check("the plain-if rule does not match an if-else",
                        apply(m, engine, "UnwrapIfAtHead", p, "c", inner, null), "NOT APPLICABLE");
            }

            // --- The search move, as MOMoT calls it
            check("EditAnywhere exists", m.getUnit("EditAnywhere") != null, true);
            Random rnd = new Random(7);
            EObject empty = body();
            EGraph g = new EGraphImpl(empty);
            int ok = 0;
            for (int i = 0; i < 15; i++) if (applyUnit(m, engine, "EditAnywhere", g, rnd)) ok++;
            check("empty program: 15/15 EditAnywhere steps apply", ok, 15);
            check("empty program: no orphan roots after 15 steps", g.getRoots().size(), 1);

            for (String unit : new String[] { "CreateThenInsertContainerThenPopulate", "InsertBlock2", "WrapAnywhere", "UnwrapAnywhere", "Restructure" }) {
                int applied = 0;
                for (int i = 0; i < 100; i++) {
                    if (applyUnit(m, engine, unit, new EGraphImpl(body(atomic("MOVE_FORWARD"), loop(atomic("TURN_LEFT")))), rnd)) applied++;
                }
                check(unit + " applies to [F, Loop[L]] (100 tries)", applied, 100);
            }

            int wraps = 0, unwraps = 0, inserts = 0, steps = 2000;
            for (int i = 0; i < steps; i++) {
                EObject b = body(atomic("MOVE_FORWARD"), loop(atomic("TURN_LEFT")));
                String before = show(b);
                if (!applyUnit(m, engine, "EditAnywhere", new EGraphImpl(b), rnd)) continue;
                String after = show(b);
                int blocks = count(b, null), atomics = count(b, "AtomicStatement");
                boolean emptyBlockInserted = after.contains("Loop[]") || after.contains(")[]");
                // The program starts with 3 blocks: F, the loop, and L inside it.
                if (blocks == 4 && atomics == 2 && !emptyBlockInserted) wraps++;   // one more loop/if around existing blocks
                else if (blocks == 2 && atomics == 2) unwraps++;                   // the loop is gone, its content stays
                else if (blocks == 4) inserts++;
            }
            System.out.printf("  EditAnywhere on [F, Loop[L]], %d steps: %d inserts, %d wraps, %d unwraps%n", steps, inserts, wraps, unwraps);
            check("EditAnywhere inserts, wraps and unwraps", inserts > 0 && wraps > 0 && unwraps > 0, true);
            check("inserting stays the most frequent move", inserts > wraps + unwraps, true);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
