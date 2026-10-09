package blocky_game;

/**
 * Binomial proportion statistics for MoMoT success-rate estimation.
 */
public final class BinomialStats {

    private BinomialStats() {}

    public static final class Estimate {
        public final int successes;
        public final int trials;
        public final double rate;
        public final double ciLow;
        public final double ciHigh;

        Estimate(int successes, int trials, double rate, double ciLow, double ciHigh) {
            this.successes = successes;
            this.trials = trials;
            this.rate = rate;
            this.ciLow = ciLow;
            this.ciHigh = ciHigh;
        }
    }

    public static Estimate estimate(int successes, int trials, double confidenceLevel) {
        if (trials <= 0) {
            return new Estimate(0, 0, 0, 0, 0);
        }
        double rate = (double) successes / trials;
        double z = zForConfidence(confidenceLevel);
        double low = wilsonLower(successes, trials, z);
        double high = wilsonUpper(successes, trials, z);
        return new Estimate(successes, trials, rate, low, high);
    }

    /**
     * E[L*] where L* is the first solutionLength that yields a successful run,
     * using independent Bernoulli trials with estimated per-length success rates.
     */
    public static double expectedMinimumLength(int[] lengths, double[] successRates) {
        if (lengths == null || successRates == null || lengths.length == 0 || lengths.length != successRates.length) {
            return Double.NaN;
        }

        double survival = 1.0;
        double expected = 0.0;
        for (int i = 0; i < lengths.length; i++) {
            double p = clamp01(successRates[i]);
            double fail = 1.0 - p;
            expected += lengths[i] * survival * p;
            survival *= fail;
        }
        // Mass remaining after last tested length: treat as unsolved at the last length + 1 penalty
        if (survival > 1e-9) {
            expected += (lengths[lengths.length - 1] + 1) * survival;
        }
        return expected;
    }

    private static double wilsonLower(int successes, int trials, double z) {
        double p = (double) successes / trials;
        double z2 = z * z;
        double denom = 1.0 + z2 / trials;
        double center = p + z2 / (2.0 * trials);
        double margin = z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * trials)) / trials);
        return clamp01((center - margin) / denom);
    }

    private static double wilsonUpper(int successes, int trials, double z) {
        double p = (double) successes / trials;
        double z2 = z * z;
        double denom = 1.0 + z2 / trials;
        double center = p + z2 / (2.0 * trials);
        double margin = z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * trials)) / trials);
        return clamp01((center + margin) / denom);
    }

    private static double zForConfidence(double confidenceLevel) {
        double level = confidenceLevel;
        if (level <= 0 || level >= 1) {
            level = 0.95;
        }
        // Two-sided normal quantiles for common confidence levels.
        if (level >= 0.999) return 3.291;
        if (level >= 0.99) return 2.576;
        if (level >= 0.95) return 1.96;
        if (level >= 0.90) return 1.645;
        return 1.96;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
