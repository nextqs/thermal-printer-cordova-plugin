package android.content;

/** JVM-only constructor shim; production is compiled against the real Android SDK first. */
public abstract class BroadcastReceiver {
    public BroadcastReceiver() {}

    public abstract void onReceive(Context context, Intent intent);
}
