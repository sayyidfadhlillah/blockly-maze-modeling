package blocky_momot;

import at.ac.tuwien.big.momot.problem.unit.parameter.IParameterValue;
import blocky.AtomicStatement;
import blocky.AtomicStatementKind;
import blocky.BlockyFactory;
import blocky.BlockyPackage;
import blocky.Statement;
import org.moeaframework.core.PRNG;

/**
 * Provides a fresh (detached) Statement instance for INOUT parameters like "stmt".
 *
 * <p>Many low-level insertion units in the statement-edit module expect an INOUT Statement which
 * is then linked into the statement list. During random solution generation, MoMoT may select
 * these units directly; without a generator, Henshin fails with "INOUT Parameter ... not set".
 *
 * <p>We return a new AtomicStatement with a randomized kind (bounded to valid enum values).
 */
public final class RandomStatementParameterValue implements IParameterValue<Statement> {

	static {
		// Ensure the EPackage and its EFactory are initialized/registered before Henshin inspects the node type.
		BlockyPackage.eINSTANCE.eClass();
	}

	@Override
	public Statement getInitialValue() {
		return nextValue();
	}

	@Override
	public Statement nextValue() {
		AtomicStatement stmt = BlockyFactory.eINSTANCE.createAtomicStatement();
		stmt.setKind(randomKind());
		return stmt;
	}

	private static AtomicStatementKind randomKind() {
		// 0 TURN_LEFT, 1 TURN_RIGHT, 2 MOVE_FORWARD
		int v = PRNG.nextInt(3);
		if (v == 0) return AtomicStatementKind.TURN_LEFT;
		if (v == 1) return AtomicStatementKind.TURN_RIGHT;
		return AtomicStatementKind.MOVE_FORWARD;
	}
}

