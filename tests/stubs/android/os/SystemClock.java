package android.os;

/** JVM replacement for the SDK's native clock, used only by the standalone tests. */
public final class SystemClock {
    public static long elapsedRealtime() {
        return System.nanoTime() / 1000000L;
    }
}
