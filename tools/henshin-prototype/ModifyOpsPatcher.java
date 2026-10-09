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
 * PROTOTYPE: builds the compiled *_edit_anywhere.henshin modules used by the game (the .henshin_text
 * source is NOT updated). Reads each original X.henshin, leaves it untouched, and writes
 * X_edit_anywhere.henshin next to it: all original rules + the operators below.
 *
 *   EditAnywhere(k, cnd)              independent unit, the ONLY search move MOMoT uses (see blocky_custom).
 *                                     Henshin tries its sub-units in random order until one applies, so no
 *                                     step is wasted on a move with nothing to act on (e.g. on an empty model):
 *     CreateThenInsertContainerThenPopulate(k, cnd)   existing insert unit
 *     DeleteContainerAnywhere                         delete a user-placed block (sub-units replaced)
 *     ModifyStatementAnywhere(k, cnd)                 modify a user-placed block
 *
 *   ModifyStatementAnywhere(k, cnd)   independent unit
 *     ChangeAtomicKind(k)             set AtomicStatement.kind = k   (only if it differs)
 *     ChangeIfCondition(cnd)          set IfStmt.condition = cnd     (only if it differs)
 *
 *   DeleteContainerAnywhere           independent unit
 *     Delete<Content>At<Position>     Content  = Empty | Atomic | EmptyLoop | EmptyIf | EmptyIfElse
 *                                     Position = OnlyInBody | BodyHead | Between | Last
 *     Loops/ifs are deletable once their bodies are empty (checkDangling stays on, so no orphans).
 *
 * Modify and delete only match user-placed blocks (generated=false): changing or deleting blocks the
 * search inserted itself adds no reachable programs and only cancels out insertions.
 * The old DeleteOnlyContainerFromBody / DeleteHeadContainerWithNext / DeleteBetweenContainerWithNext /
 * DeleteLastContainer rules are replaced. Re-running regenerates the output files from the originals.
 *
 * Usage: java ModifyOpsPatcher <repoRoot> <original.henshin>...   (see run.sh)
 */
public class ModifyOpsPatcher {
    static final HenshinFactory HF = HenshinFactory.eINSTANCE;
    static EPackage pkg;

    static final String[] OLD_DELETE_RULES = {
        "DeleteOnlyContainerFromBody", "DeleteHeadContainerWithNext", "DeleteBetweenContainerWithNext", "DeleteLastContainer" };
    static final String[] CONTENTS = { "Empty", "Atomic", "EmptyLoop", "EmptyIf", "EmptyIfElse" };
    static final String[] POSITIONS = { "OnlyInBody", "BodyHead", "Between", "Last" };

    static final String SUFFIX = "_edit_anywhere";

    static EClass cls(String n) { return (EClass) pkg.getEClassifier(n); }
    static EReference ref(String c, String f) { return (EReference) cls(c).getEStructuralFeature(f); }
    static EAttribute attr(String c, String f) { return (EAttribute) cls(c).getEStructuralFeature(f); }

    /** Names of all rules/units this patcher adds (used for idempotency and for MOMoT ignore lists). */
    static List<String> addedRuleNames() {
        List<String> names = new ArrayList<>(List.of("ChangeAtomicKind", "ChangeIfCondition", "ModifyStatementAnywhere", "EditAnywhere"));
        for (String p : POSITIONS) for (String c : CONTENTS) names.add("Delete" + c + "At" + p);
        return names;
    }

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
            patch(module);
            module.eResource().setURI(module.eResource().getURI().trimSegments(1).appendSegment(outputName(args[i])));
            module.eResource().save(null);
            System.out.println("Wrote " + outputName(args[i]) + " (" + module.getUnits().size() + " units) from " + args[i]);
        }
    }

    static String outputName(String original) {
        return original.replaceFirst("\\.henshin$", SUFFIX + ".henshin");
    }

    static void patch(Module module) {
        // Idempotency: drop old delete rules and anything a previous run added.
        Set<String> drop = new HashSet<>(Arrays.asList(OLD_DELETE_RULES));
        drop.addAll(addedRuleNames());
        module.getUnits().removeIf(u -> drop.contains(u.getName()));

        // --- Modify ---
        Rule changeKind = changeEnumRule("ChangeAtomicKind", "AtomicStatement", "kind", "k");
        Rule changeCond = changeEnumRule("ChangeIfCondition", "IfStmt", "condition", "cnd");
        module.getUnits().add(changeKind);
        module.getUnits().add(changeCond);

        IndependentUnit modify = HF.createIndependentUnit();
        modify.setName("ModifyStatementAnywhere");
        Parameter uk = inParam("k"), ucnd = inParam("cnd");
        modify.getParameters().add(uk);
        modify.getParameters().add(ucnd);
        modify.getSubUnits().add(changeKind);
        modify.getSubUnits().add(changeCond);
        mapBothWays(modify, uk, changeKind.getParameter("k"));
        mapBothWays(modify, ucnd, changeCond.getParameter("cnd"));
        module.getUnits().add(modify);

        // --- Delete (any origin) ---
        IndependentUnit deleteAny = (IndependentUnit) module.getUnit("DeleteContainerAnywhere");
        if (deleteAny == null) {
            deleteAny = HF.createIndependentUnit();
            deleteAny.setName("DeleteContainerAnywhere");
            module.getUnits().add(deleteAny);
        }
        deleteAny.getSubUnits().clear();
        for (String p : POSITIONS) {
            for (String c : CONTENTS) {
                Rule r = deleteRule(p, c);
                // Insert before the high-level units so rules stay grouped at the top of the file.
                module.getUnits().add(module.getUnits().indexOf(deleteAny), r);
                deleteAny.getSubUnits().add(r);
            }
        }

        // --- EditAnywhere: the single search move over insert / delete / modify ---
        {
            Unit insert = module.getUnit("CreateThenInsertContainerThenPopulate");
            IndependentUnit edit = HF.createIndependentUnit();
            edit.setName("EditAnywhere");
            Parameter ek = inParam("k"), ecnd = inParam("cnd");
            edit.getParameters().add(ek);
            edit.getParameters().add(ecnd);
            edit.getSubUnits().add(insert);
            edit.getSubUnits().add(deleteAny);
            edit.getSubUnits().add(modify);
            for (Unit sub : List.of(insert, modify)) {
                if (sub.getParameter("k") != null) mapBothWays(edit, ek, sub.getParameter("k"));
                if (sub.getParameter("cnd") != null) mapBothWays(edit, ecnd, sub.getParameter("cnd"));
            }
            module.getUnits().add(edit);
        }
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

    /** node s : type { feature: old -> param }, with attribute condition old != param (no no-op edits). */
    static Rule changeEnumRule(String name, String type, String feature, String param) {
        Rule r = HF.createRule(name);
        r.getParameters().add(inParam(param));
        Parameter old = HF.createParameter("old");
        old.setKind(ParameterKind.VAR);
        r.getParameters().add(old);

        Node l = HF.createNode(r.getLhs(), cls(type), "s");
        Node rr = HF.createNode(r.getRhs(), cls(type), "s");
        r.getMappings().add(l, rr);
        userOnly(l);
        userOnly(rr); // preserved node: the RHS must repeat the attribute, or Henshin reads it as "unset generated"
        HF.createAttribute(l, attr(type, feature), "old");
        HF.createAttribute(rr, attr(type, feature), param);

        AttributeCondition differs = HF.createAttributeCondition();
        differs.setName("differs");
        differs.setConditionText("old != " + param);
        r.getAttributeConditions().add(differs);
        return r;
    }

    /** Removes container `curr` at the given position together with its (empty) content, relinking the list. */
    static Rule deleteRule(String position, String content) {
        Rule r = HF.createRule("Delete" + content + "At" + position);
        Graph lhs = r.getLhs(), rhs = r.getRhs();
        Node curr = HF.createNode(lhs, cls("Container"), "curr");

        // Position: how curr is attached, and how the list is relinked.
        switch (position) {
            case "OnlyInBody": {
                Node body = preserved(r, "Body", "body");
                HF.createEdge(body, curr, ref("Body", "firstContainer"));
                forbidEdge(r, curr, "Container", "next", "after");
                break;
            }
            case "BodyHead": {
                Node body = preserved(r, "Body", "body");
                Node next = preserved(r, "Container", "next");
                HF.createEdge(body, curr, ref("Body", "firstContainer"));
                HF.createEdge(curr, next, ref("Container", "next"));
                HF.createEdge(r.getMappings().getImage(body, rhs), r.getMappings().getImage(next, rhs), ref("Body", "firstContainer"));
                break;
            }
            case "Between": {
                Node prev = preserved(r, "Container", "prev");
                Node next = preserved(r, "Container", "next");
                HF.createEdge(prev, curr, ref("Container", "next"));
                HF.createEdge(curr, next, ref("Container", "next"));
                HF.createEdge(r.getMappings().getImage(prev, rhs), r.getMappings().getImage(next, rhs), ref("Container", "next"));
                break;
            }
            case "Last": {
                Node prev = preserved(r, "Container", "prev");
                HF.createEdge(prev, curr, ref("Container", "next"));
                forbidEdge(r, curr, "Container", "next", "after");
                break;
            }
            default: throw new IllegalArgumentException(position);
        }

        // Content: what curr holds. Everything matched here is deleted; bodies must be empty.
        switch (content) {
            case "Empty":
                forbidEdge(r, curr, "Container", "statement", "s0");
                userOnly(curr);
                break;
            case "Atomic": {
                Node s = HF.createNode(lhs, cls("AtomicStatement"), "s");
                HF.createEdge(curr, s, ref("Container", "statement"));
                userOnly(s);
                break;
            }
            case "EmptyLoop": {
                Node s = HF.createNode(lhs, cls("Loop"), "s");
                Node b = HF.createNode(lhs, cls("Body"), "b");
                HF.createEdge(curr, s, ref("Container", "statement"));
                userOnly(s);
                HF.createEdge(s, b, ref("Loop", "body"));
                forbidEdge(r, b, "Body", "firstContainer", "bHead");
                break;
            }
            case "EmptyIf":
            case "EmptyIfElse": {
                Node s = HF.createNode(lhs, cls("IfStmt"), "s");
                Node t = HF.createNode(lhs, cls("Body"), "thenB");
                HF.createEdge(curr, s, ref("Container", "statement"));
                userOnly(s);
                HF.createEdge(s, t, ref("IfStmt", "thenBody"));
                forbidEdge(r, t, "Body", "firstContainer", "thenHead");
                if (content.equals("EmptyIfElse")) {
                    Node e = HF.createNode(lhs, cls("Body"), "elseB");
                    HF.createEdge(s, e, ref("IfStmt", "elseBody"));
                    forbidEdge(r, e, "Body", "firstContainer", "elseHead");
                }
                // EmptyIf: an existing elseBody would dangle, so checkDangling rejects if-else statements here.
                break;
            }
            default: throw new IllegalArgumentException(content);
        }
        return r;
    }

    /** Only match elements the user placed (generated=false). */
    static void userOnly(Node lhsNode) {
        HF.createAttribute(lhsNode, (EAttribute) lhsNode.getType().getEStructuralFeature("generated"), "false");
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
