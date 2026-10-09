package android.util;

/** JVM-test stand-in for android.util.Log: prints to stdout when -Dvx.log is set so parser diagnostics are visible. */
public final class Log {
    private static final boolean ON = System.getProperty("vx.log") != null;
    private static int p(String l, String t, String m, Throwable e) {
        if (ON) { System.out.println(l + "/" + t + ": " + m); if (e != null) e.printStackTrace(System.out); }
        return 0;
    }
    public static int v(String t, String m) { return p("V", t, m, null); }
    public static int d(String t, String m) { return p("D", t, m, null); }
    public static int i(String t, String m) { return p("I", t, m, null); }
    public static int w(String t, String m) { return p("W", t, m, null); }
    public static int w(String t, String m, Throwable e) { return p("W", t, m, e); }
    public static int e(String t, String m) { return p("E", t, m, null); }
    public static int e(String t, String m, Throwable e) { return p("E", t, m, e); }
}
