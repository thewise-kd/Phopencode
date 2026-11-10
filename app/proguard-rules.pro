# R8 rules for the release build. P3.10 added the SSH stack's rules; P12.4 switched R8 on and
# hardened the rest. Broad keeps on purpose for the SSH stack: the emulator's connected tests on
# the releaseCheck build type (see app/build.gradle.kts) prove any narrowing.

# BouncyCastle. The provider registers its algorithm classes by name and loads them with
# reflection, so R8 cannot see that they are used. sshj also looks the provider class up by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj. Algorithm factories, key file readers and the SFTP packet types are wired through
# configuration and names; keep them whole.
-keep class net.schmizz.sshj.** { *; }
-keep class net.schmizz.concurrent.** { *; }
-keep class net.schmizz.keepalive.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class com.hierynomus.asn1.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**

# sshj refers to GSSAPI and JAAS for Kerberos login, which Android does not ship and the app
# never uses.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.naming.**

# sshj logs through SLF4J; no binding is packaged, so SLF4J falls back to no logging.
-dontwarn org.slf4j.**

# No logs in the release (P12.4). R8 drops these calls wherever they appear, in the app and in its
# libraries. Diagnostics are already off in a non-debuggable build; this removes the call sites.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
# ConnectionEndLog.JavaLogging logs through java.util.logging, which reaches Logcat on Android.
-assumenosideeffects class java.util.logging.Logger {
    public static java.util.logging.Logger getLogger(...);
    public void log(...);
    public void logp(...);
    public void severe(...);
    public void warning(...);
    public void info(...);
    public void config(...);
    public void fine(...);
    public void finer(...);
    public void finest(...);
}
# Standard output reaches Logcat too, but the app's own code never prints. Kotlin's println is
# inlined into System.out, so a rule on kotlin.io.ConsoleKt would match nothing, and a rule on
# java.io.PrintStream would also drop writes to files. The System.out and System.err call sites
# left in the release are in libraries: Compose, OkHttp, joni, SLF4J and BouncyCastle's tools.
