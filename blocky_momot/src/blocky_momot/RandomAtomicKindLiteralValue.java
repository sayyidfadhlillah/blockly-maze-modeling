package blocky_momot;

import org.moeaframework.core.PRNG;

import at.ac.tuwien.big.momot.problem.unit.parameter.IParameterValue;
import blocky.AtomicStatementKind;

/**
 * Supplies a random AtomicStatementKind literal name (TURN_LEFT / TURN_RIGHT / MOVE_FORWARD).
 *
 * We use String literals because Henshin casts enum-typed attributes from strings
 * (via EFactory#createFromString), and integer values are not valid for EEnums.
 */
public final class RandomAtomicKindLiteralValue implements IParameterValue<AtomicStatementKind> {
	@Override
	public AtomicStatementKind getInitialValue() {
		return nextValue();
	}

	@Override
	public AtomicStatementKind nextValue() {
		int v = PRNG.nextInt(3);
		if (v == 0) return AtomicStatementKind.TURN_LEFT;
		if (v == 1) return AtomicStatementKind.TURN_RIGHT;
		return AtomicStatementKind.MOVE_FORWARD;
	}
}

