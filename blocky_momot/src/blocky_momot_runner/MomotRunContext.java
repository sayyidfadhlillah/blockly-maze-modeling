package blocky_momot_runner;

import blocky_momot.listener.IParetoFrontSubscriber;
import java.nio.file.Path;

/**
 * Per-thread MoMoT run parameters for safe parallel execution.
 */
public final class MomotRunContext {

    public static final class Config {
        public final int populationSize;
        public final int maxEvaluations;
        public final int nrRuns;
        public final int solutionLength;
        public final Path outputDirectory;
        public final IParetoFrontSubscriber paretoFrontSubscriber;
        public final boolean stopOnFirstGoal;
        public final int seed;

        public Config(int populationSize, int maxEvaluations, int nrRuns, int solutionLength, Path outputDirectory) {
            this(populationSize, maxEvaluations, nrRuns, solutionLength, outputDirectory, null, false, -1);
        }

        public Config(
                int populationSize,
                int maxEvaluations,
                int nrRuns,
                int solutionLength,
                Path outputDirectory,
                IParetoFrontSubscriber paretoFrontSubscriber) {
            this(populationSize, maxEvaluations, nrRuns, solutionLength, outputDirectory, paretoFrontSubscriber, false, -1);
        }

        public Config(
                int populationSize,
                int maxEvaluations,
                int nrRuns,
                int solutionLength,
                Path outputDirectory,
                IParetoFrontSubscriber paretoFrontSubscriber,
                boolean stopOnFirstGoal) {
            this(populationSize, maxEvaluations, nrRuns, solutionLength, outputDirectory, paretoFrontSubscriber, stopOnFirstGoal, -1);
        }

        public Config(
                int populationSize,
                int maxEvaluations,
                int nrRuns,
                int solutionLength,
                Path outputDirectory,
                IParetoFrontSubscriber paretoFrontSubscriber,
                boolean stopOnFirstGoal,
                int seed) {
            this.populationSize = populationSize;
            this.maxEvaluations = maxEvaluations;
            this.nrRuns = nrRuns;
            this.solutionLength = solutionLength;
            this.outputDirectory = outputDirectory;
            this.paretoFrontSubscriber = paretoFrontSubscriber;
            this.stopOnFirstGoal = stopOnFirstGoal;
            this.seed = seed;
        }
    }

    private static final ThreadLocal<Config> CURRENT = new ThreadLocal<>();

    private MomotRunContext() {}

    public static void set(Config config) {
        CURRENT.set(config);
    }

    public static Config get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
