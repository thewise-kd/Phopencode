# R8 rules for the release build. P3.10 adds the SSH stack's rules; P12.4 switches R8 on and
# hardens the rest. Broad keeps on purpose: narrowing them is only safe with the emulator's
# release SSH test of P12.4 to prove it.

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
