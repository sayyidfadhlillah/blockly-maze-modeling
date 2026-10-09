package blocky_momot;

import org.moeaframework.core.PRNG;

import java.util.Random;

/**
 * Thread-isolated Random proxy for MOEA's PRNG.
 * Prevents concurrent MoMoT searches from interleaving pseudo-random streams.
 */
public class ThreadLocalRandomProxy extends Random {

    private static final class DelegatedRandom extends Random {
        public DelegatedRandom() {
            super();
        }

        public DelegatedRandom(long seed) {
            super(seed);
        }

        public int delegateNext(int bits) {
            return super.next(bits);
        }
    }

    private static final ThreadLocal<DelegatedRandom> THREAD_RANDOM = ThreadLocal.withInitial(DelegatedRandom::new);
    private static volatile boolean installed = false;

    public static synchronized void install() {
        if (!installed) {
            PRNG.setRandom(new ThreadLocalRandomProxy());
            installed = true;
        }
    }

    public static void setThreadSeed(long seed) {
        THREAD_RANDOM.get().setSeed(seed);
    }

    public static void clearThreadRandom() {
        THREAD_RANDOM.remove();
    }

    @Override
    public void setSeed(long seed) {
        DelegatedRandom dr = THREAD_RANDOM.get();
        if (dr != null) {
            dr.setSeed(seed);
        }
    }

    @Override
    protected int next(int bits) {
        return THREAD_RANDOM.get().delegateNext(bits);
    }

    @Override
    public int nextInt() {
        return THREAD_RANDOM.get().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return THREAD_RANDOM.get().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return THREAD_RANDOM.get().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return THREAD_RANDOM.get().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return THREAD_RANDOM.get().nextFloat();
    }

    @Override
    public double nextDouble() {
        return THREAD_RANDOM.get().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return THREAD_RANDOM.get().nextGaussian();
    }

    @Override
    public void nextBytes(byte[] bytes) {
        THREAD_RANDOM.get().nextBytes(bytes);
    }
}
