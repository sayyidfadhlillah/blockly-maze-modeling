import java.util.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.*;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.henshin.model.*;
import org.eclipse.emf.henshin.model.resource.HenshinResourceSet;
import org.eclipse.emf.henshin.model.Module;

/**
 * PROTOTYPE (Exploration-Proposal.md, section 9, P1): adds wrap/unwrap moves to a compiled rule module.
 * Reads X.henshin, leaves it untouched, and writes X_wrap.henshin next to it. Works on the original modules
 * and on the *_edit_anywhere modules. The game and the benchmark only use the *_wrap files when
 * -Dblocky.rules.wrap=true is set, so switching the property off (or deleting the files) reverts everything.
 *
 * Why: the existing rules insert and delete one block at a time. Turning "F F L" into "repeat { F F L }"
 * then means deleting the blocks and rebuilding them inside a loop. A wrap move does it in one step and
 * keeps what the program already does.
 *
 *   WrapTailIn<Wrapper>At<Head|After>(cnd)   a block and everything after it in the same body moves into a
 *                                            new Loop / If / IfElse, which takes the block's place
 *   Unwrap<Wrapper>At<Head|After>            the reverse: a Loop / If / IfElse that is the last block of its
 *                                            body is replaced by its content (the else branch must be empty)
 *   WrapAnywhere(cnd), UnwrapAnywhere        independent units over the rules above
 *   Restructure(cnd)                         independent unit over WrapAnywhere and UnwrapAnywhere
 *
 *   EditAnywhere(k, cnd)                     the single search move (see blocky_custom). Created here if the
 *                                            module has none; Restructure is added to it. Henshin tries the
 *                                            sub-units in random order, so INSERT_WEIGHT - 1 alias units of the
 *                                            insert move keep inserting INSERT_WEIGHT times as likely as
 *                                            restructuring.
 *
 * Wrappers are taken from the module's own vocabulary (it only wraps in what it can also insert).
 *
 * Usage: java WrapOpsPatcher <repoRoot> <X.henshin>...   (see run.sh wrap)
 */
public class WrapOpsPatcher {
    static final HenshinFactory HF = HenshinFactory.eINSTANCE;
    static EPackage pkg;

    static final String SUFFIX = "_wrap";
    static final int INSERT_WEIGHT = 3;
    static final String INSERT_UNIT = "CreateThenInsertContainerThenPopulate";
    static final String[] POSITIONS = { "Head", "After" };

    static EClass cls(String n) { return (EClass) pkg.getEClassifier(n); }
    static EReference ref(String c, String f) { return (EReference) cls(c).getEStructuralFeature(f); }
    static EAttribute attr(String c, String f) { return (EAttribute) cls(c).getEStructuralFeature(f); }

    public static void main(String[] args) throws Exception {
        String repo = args[0];
        ResourceSet ers = new ResourceSetImpl();
        ers.getResourceFactoryRegistry().getExtensionToFactoryMap().put("ecore", new XMIResourceFactoryImpl());
        Resource ecoreRes = ers.getResource(URI.createFileURI(repo + "/blocky_model/model/blocky.ecore"), true);
        pkg = (EPackage) ecoreRes.getContents().get(0);
        // Serialize type references as http://www.example.org/blocky#//X (as MOMoT expects), not as file paths.
        ecoreRes.setURI(URI.createURI(pkg.getNsURI()));

        for (int i = 1; i < args.length; i++) {
            HenshinResourceSet hrs = new HenshinResourceSet(repo + "/blocky_model/transformations");
            hrs.getPackageRegistry().put(pkg.getNsURI(), pkg);
            Module module = hrs.getModule(args[i], false);
            List<String> wrappers = patch(module);
            String out = args[i].replaceFirst("\\.henshin$", SUFFIX + ".henshin");
            module.eResource().setURI(module.eResource().getURI().trimSegments(1).appendSegment(out));
            module.eResource().save(null);
            System.out.println("Wrote " + out + " (" + module.getUnits().size() + " units, wrappers " + wrappers + ") from " + args[i]);
        }
    }

    /** Wrappers this module can insert, in the same vocabulary as its Populate rules. */
    static List<String> wrappers(Module module) {
        List<String> w = new ArrayList<>();
        if (module.getUnit("PopulateEmptyContainerWithLoop") != null) w.add("Loop");
        if (module.getUnit("PopulateEmptyContainerWithIf") != null) w.add("If");
        if (module.getUnit("PopulateEmptyContainerWithIfElse") != null) w.add("IfElse");
        return w;
    }

    static List<String> patch(Module module) {
        List<String> wrappers = wrappers(module);
        if (wrappers.isEmpty()) throw new IllegalStateException(module.getName() + " has no loop or if to wrap in");
        boolean hasIf = wrappers.contains("If") || wrappers.contains("IfElse");

        IndependentUnit wrapAny = HF.createIndependentUnit();
        wrapAny.setName("WrapAnywhere");
        IndependentUnit unwrapAny = HF.createIndependentUnit();
        unwrapAny.setName("UnwrapAnywhere");
        Parameter wrapCnd = null;
        if (hasIf) {
            wrapCnd = inParam("cnd");
            wrapAny.getParameters().add(wrapCnd);
        }
        for (String w : wrappers) {
            for (String p : POSITIONS) {
                Rule wrap = wrapRule(w, p);
                module.getUnits().add(wrap);
                wrapAny.getSubUnits().add(wrap);
                if (wrap.getParameter("cnd") != null) mapBothWays(wrapAny, wrapCnd, wrap.getParameter("cnd"));
                Rule unwrap = unwrapRule(w, p);
                module.getUnits().add(unwrap);
                unwrapAny.getSubUnits().add(unwrap);
            }
        }
        module.getUnits().add(wrapAny);
        module.getUnits().add(unwrapAny);

        IndependentUnit restructure = HF.createIndependentUnit();
        restructure.setName("Restructure");
        restructure.getSubUnits().add(wrapAny);
        restructure.getSubUnits().add(unwrapAny);
        if (hasIf) {
            Parameter c = inParam("cnd");
            restructure.getParameters().add(c);
            mapBothWays(restructure, c, wrapCnd);
        }
        module.getUnits().add(restructure);

        // The single search move.
        Unit insert = module.getUnit(INSERT_UNIT);
        IndependentUnit edit = (IndependentUnit) module.getUnit("EditAnywhere");
        if (edit == null) {
            edit = HF.createIndependentUnit();
            edit.setName("EditAnywhere");
            edit.getParameters().add(inParam("k"));
            edit.getParameters().add(inParam("cnd"));
            addSubUnit(edit, insert);
        } else {
            module.getUnits().remove(edit); // keep it last in the file
        }
        for (int i = 2; i <= INSERT_WEIGHT; i++) {
            IndependentUnit alias = HF.createIndependentUnit();
            alias.setName("InsertBlock" + i);
            for (Parameter p : insert.getParameters()) alias.getParameters().add(inParam(p.getName()));
            addSubUnit(alias, insert);
            module.getUnits().add(alias);
            addSubUnit(edit, alias);
        }
        addSubUnit(edit, restructure);
        module.getUnits().add(edit);
        return wrappers;
    }

    /** Adds sub as a sub-unit of unit and connects the parameters they share by name. */
    static void addSubUnit(Unit unit, Unit sub) {
        if (unit instanceof IndependentUnit) ((IndependentUnit) unit).getSubUnits().add(sub);
        for (Parameter p : unit.getParameters()) {
            Parameter q = sub.getParameter(p.getName());
            if (q != null) mapBothWays(unit, p, q);
        }
    }

    /**
     * Container c (first block of the tail) moves, with everything after it, into the body of a new wrapper
     * statement held by a new container n; n takes c's place in the list.
     */
    static Rule wrapRule(String wrapper, String position) {
        Rule r = HF.createRule("WrapTailIn" + wrapper + "At" + position);
        Graph rhs = r.getRhs();
        Node c = preserved(r, "Container", "c");
        Node cR = r.getMappings().getImage(c, rhs);

        Node n = HF.createNode(rhs, cls("Container"), "n");
        generated(n);
        Node s = HF.createNode(rhs, cls(wrapper.equals("Loop") ? "Loop" : "IfStmt"), "s");
        generated(s);
        Node b = HF.createNode(rhs, cls("Body"), "b");
        HF.createEdge(n, s, ref("Container", "statement"));
        if (wrapper.equals("Loop")) {
            HF.createEdge(s, b, ref("Loop", "body"));
        } else {
            r.getParameters().add(inParam("cnd"));
            HF.createAttribute(s, attr("IfStmt", "condition"), "cnd");
            HF.createEdge(s, b, ref("IfStmt", "thenBody"));
            if (wrapper.equals("IfElse")) {
                Node e = HF.createNode(rhs, cls("Body"), "elseB");
                HF.createEdge(s, e, ref("IfStmt", "elseBody"));
            }
        }
        HF.createEdge(b, cR, ref("Body", "firstContainer"));

        attach(r, position, c, n);
        return r;
    }

    /**
     * Reverse of wrapRule: wrapper container n is the last block of its body; its (non-empty) content c takes
     * n's place and n, the wrapper statement and its bodies are deleted. An else branch must be empty.
     */
    static Rule unwrapRule(String wrapper, String position) {
        Rule r = HF.createRule("Unwrap" + wrapper + "At" + position);
        Graph lhs = r.getLhs();
        Node c = preserved(r, "Container", "c");
        Node cR = r.getMappings().getImage(c, r.getRhs());

        Node n = HF.createNode(lhs, cls("Container"), "n");
        Node s = HF.createNode(lhs, cls(wrapper.equals("Loop") ? "Loop" : "IfStmt"), "s");
        Node b = HF.createNode(lhs, cls("Body"), "b");
        HF.createEdge(n, s, ref("Container", "statement"));
        HF.createEdge(s, b, wrapper.equals("Loop") ? ref("Loop", "body") : ref("IfStmt", "thenBody"));
        HF.createEdge(b, c, ref("Body", "firstContainer"));
        forbidEdge(r, n, "Container", "next", "after");
        if (wrapper.equals("IfElse")) {
            Node e = HF.createNode(lhs, cls("Body"), "elseB");
            HF.createEdge(s, e, ref("IfStmt", "elseBody"));
            forbidEdge(r, e, "Body", "firstContainer", "elseHead");
        }
        // "If" on a statement that has an else body: the elseBody edge would dangle, so the rule does not match.

        // Position: how n is attached on the left, and c in its place on the right.
        if (position.equals("Head")) {
            Node body = preserved(r, "Body", "body");
            HF.createEdge(body, n, ref("Body", "firstContainer"));
            HF.createEdge(r.getMappings().getImage(body, r.getRhs()), cR, ref("Body", "firstContainer"));
        } else {
            Node prev = preserved(r, "Container", "prev");
            HF.createEdge(prev, n, ref("Container", "next"));
            HF.createEdge(r.getMappings().getImage(prev, r.getRhs()), cR, ref("Container", "next"));
        }
        return r;
    }

    /** Left: c hangs at the given position. Right: the new container n hangs there instead. */
    static void attach(Rule r, String position, Node c, Node n) {
        if (position.equals("Head")) {
            Node body = preserved(r, "Body", "body");
            HF.createEdge(body, c, ref("Body", "firstContainer"));
            HF.createEdge(r.getMappings().getImage(body, r.getRhs()), n, ref("Body", "firstContainer"));
        } else {
            Node prev = preserved(r, "Container", "prev");
            HF.createEdge(prev, c, ref("Container", "next"));
            HF.createEdge(r.getMappings().getImage(prev, r.getRhs()), n, ref("Container", "next"));
        }
    }

    static void generated(Node rhsNode) {
        HF.createAttribute(rhsNode, (EAttribute) rhsNode.getType().getEStructuralFeature("generated"), "true");
    }

    static Parameter inParam(String name) {
        Parameter p = HF.createParameter(name);
        p.setKind(ParameterKind.IN);
        p.setType(EcorePackage.Literals.EENUMERATOR);
        return p;
    }

    static void mapBothWays(Unit unit, Parameter a, Parameter b) {
        ParameterMapping in = HF.createParameterMapping();
        in.setSource(a);
        in.setTarget(b);
        ParameterMapping out = HF.createParameterMapping();
        out.setSource(b);
        out.setTarget(a);
        unit.getParameterMappings().add(in);
        unit.getParameterMappings().add(out);
    }

    /** Creates a node present in both LHS and RHS (preserved) and returns the LHS node. */
    static Node preserved(Rule r, String type, String name) {
        Node l = HF.createNode(r.getLhs(), cls(type), name);
        Node rr = HF.createNode(r.getRhs(), cls(type), name);
        r.getMappings().add(l, rr);
        return l;
    }

    /** NAC: forbid an outgoing `feature` edge from lhsNode to any target of the feature's type. */
    static void forbidEdge(Rule r, Node lhsNode, String ownerType, String feature, String targetName) {
        EReference ref = ref(ownerType, feature);
        NestedCondition nac = r.getLhs().createNAC(null);
        Graph g = nac.getConclusion();
        Node src = HF.createNode(g, lhsNode.getType(), lhsNode.getName());
        nac.getMappings().add(lhsNode, src);
        Node tgt = HF.createNode(g, ref.getEReferenceType(), targetName);
        HF.createEdge(src, tgt, ref);
    }
}
