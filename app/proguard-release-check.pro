# Test-only R8 rules for the releaseCheck build type (P12.4); release never reads this file.
#
# releaseCheck is the release minified by R8 so that the emulator's instrumented SSH tests run
# against R8's output. The test APK calls into the app's classes, and R8 removes, merges or
# inlines whatever the app itself does not use, so these rules keep exactly what the test APK
# references and would otherwise be missing at runtime: nothing more, so that the code under test
# stays minified like the release. The SSH stack's own rules are in proguard-rules.pro.
#
# Generated, then grouped. Not covered: references from androidx.compose.ui.test and
# kotlinx.coroutines.test. The convention plugin puts them on every androidTest classpath, but no
# test in app/src/androidTest uses them, so their classes are never loaded. Annotations
# (kotlin.Metadata, JvmStatic, CheckReturnValue) need no keep either.
#
# To regenerate, after a change to the tests or to what they call:
#   1. Build with this file emptied:
#        ./gradlew :app:assembleReleaseCheck :app:assembleReleaseCheckAndroidTest \
#          -Pphopencode.testBuildType=releaseCheck
#   2. List what the test APK references that the minified app lacks, with R8's TraceReferences,
#      both APKs as sources and android.jar as the library:
#        java -cp $ANDROID_HOME/cmdline-tools/latest/lib/r8.jar \
#          com.android.tools.r8.tracereferences.TraceReferences --check \
#          --lib $ANDROID_HOME/platforms/android-<compileSdk>/android.jar \
#          --source app/build/outputs/apk/androidTest/releaseCheck/Phopencode-releaseCheck-androidTest.apk \
#          --source app/build/outputs/apk/releaseCheck/Phopencode-releaseCheck.apk
#      Keep the "Missing ..." entries referenced from test classes (io.github.thewisekd.phopencode,
#      androidx.test, androidx.concurrent, JUnit, Hamcrest) whose holder is one of the app's own
#      classes: in app/build/outputs/mapping/releaseCheck/mapping.txt or usage.txt. Missing
#      Android platform members (sun.misc.Unsafe, hidden APIs) are not the app's and stay out.
#   3. Translate obfuscated names back with that mapping.txt, write one rule per entry: -keep for a
#      missing class, -keepclassmembers for a missing member of a class that exists. A Kotlin
#      multifile facade (CollectionsKt, BuildersKt, ...) either inherits its members from its
#      parts (CollectionsKt__*) or delegates to them (BuildersKt), so its members are kept on
#      the facade and its parts together, and the facade itself with -keep.
#   4. Rebuild with the rules and repeat step 2 until it lists nothing in scope: a kept member can
#      change what R8 removes elsewhere.

# The app's own code the tests call.
-keepclassmembers class io.github.thewisekd.phopencode.core.model.FileEntry {
    io.github.thewisekd.phopencode.core.model.FileEntryType getType();
    java.lang.Long getSizeBytes();
}
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.api.HostKey {
    java.lang.String getFingerprintSha256();
    java.lang.String getType();
}
-keep class io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.api.SftpClient {
    java.lang.Object delete$default(io.github.thewisekd.phopencode.core.ssh.api.SftpClient,java.lang.String,boolean,kotlin.coroutines.Continuation,int,java.lang.Object);
    java.lang.Object delete(java.lang.String,boolean,kotlin.coroutines.Continuation);
    java.lang.Object list(java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object mkdir$default(io.github.thewisekd.phopencode.core.ssh.api.SftpClient,java.lang.String,boolean,kotlin.coroutines.Continuation,int,java.lang.Object);
    java.lang.Object read(java.lang.String,long,kotlin.coroutines.Continuation);
    java.lang.Object writeAtomic$default(io.github.thewisekd.phopencode.core.ssh.api.SftpClient,java.lang.String,java.io.InputStream,io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions,kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation,int,java.lang.Object);
}
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions {
    <init>(long,kotlin.time.Duration,int,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.api.SshCredential$PrivateKey {
    <init>(char[],char[],int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.api.SshException$HostKeyRejected {
    io.github.thewisekd.phopencode.core.ssh.api.HostKey getHostKey();
}
-keep class io.github.thewisekd.phopencode.core.ssh.api.SshSession {
    java.lang.Object openSftp(java.lang.String,kotlin.coroutines.Continuation);
    javax.net.SocketFactory socketFactory();
    kotlinx.coroutines.flow.StateFlow getConnected();
    void close();
}
-keepclassmembers class io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector {
    java.lang.Object connect(io.github.thewisekd.phopencode.core.ssh.api.SshTarget,io.github.thewisekd.phopencode.core.ssh.api.SshCredential,io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier,io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions,kotlin.coroutines.Continuation);
}
-keep class io.github.thewisekd.phopencode.security.BouncyCastle {
    io.github.thewisekd.phopencode.security.BouncyCastle INSTANCE;
    org.bouncycastle.jce.provider.BouncyCastleProvider install();
}
-keep class io.github.thewisekd.phopencode.security.ProviderReplacement {
    io.github.thewisekd.phopencode.security.ProviderReplacement INSTANCE;
    java.security.Provider replace(java.lang.String,java.lang.Class,kotlin.jvm.functions.Function0);
}

# Kotlin standard library.
-keep class kotlin.LazyKt
-keepclassmembers class kotlin.LazyKt,kotlin.LazyKt__* {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keepclassmembers class kotlin.Pair {
    java.lang.Object component1();
    java.lang.Object component2();
}
-keepclassmembers class kotlin.Result {
    java.lang.Object constructor-impl(java.lang.Object);
    kotlin.Result$Companion Companion;
}
-keep class kotlin.Result$Companion
-keep class kotlin.ResultKt {
    java.lang.Object createFailure(java.lang.Throwable);
    void throwOnFailure(java.lang.Object);
}
-keep class kotlin.collections.AbstractIterator {
    <init>();
    void done();
    void setNext(java.lang.Object);
}
-keep class kotlin.collections.ArraysKt
-keepclassmembers class kotlin.collections.ArraysKt,kotlin.collections.ArraysKt__* {
    byte[] plus(byte[],byte[]);
    java.lang.Object last(java.lang.Object[]);
    java.util.List toList(java.lang.Object[]);
}
-keep class kotlin.collections.CollectionsKt
-keepclassmembers class kotlin.collections.CollectionsKt,kotlin.collections.CollectionsKt__* {
    int collectionSizeOrDefault(java.lang.Iterable,int);
    java.lang.Object first(java.util.List);
    java.lang.Object single(java.util.List);
    java.lang.String joinToString$default(java.lang.Iterable,java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence,int,java.lang.CharSequence,kotlin.jvm.functions.Function1,int,java.lang.Object);
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List toList(java.lang.Iterable);
    java.util.List zip(java.lang.Iterable,java.lang.Iterable);
    void throwCountOverflow();
}
-keep class kotlin.coroutines.ContinuationKt {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt
-keepclassmembers class kotlin.coroutines.intrinsics.IntrinsicsKt,kotlin.coroutines.intrinsics.IntrinsicsKt__* {
    java.lang.Object getCOROUTINE_SUSPENDED();
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.Boxing {
    java.lang.Boolean boxBoolean(boolean);
    java.lang.Integer boxInt(int);
    java.lang.Long boxLong(long);
}
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt {
    void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.SpillingKt {
    java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep class kotlin.coroutines.jvm.internal.SuspendFunction
-keepclassmembers class kotlin.coroutines.jvm.internal.SuspendLambda {
    <init>(int,kotlin.coroutines.Continuation);
}
-keep class kotlin.io.CloseableKt {
    void closeFinally(java.io.Closeable,java.lang.Throwable);
}
-keep class kotlin.jdk7.AutoCloseableKt {
    void closeFinally(java.lang.AutoCloseable,java.lang.Throwable);
}
-keep class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class kotlin.jvm.internal.Reflection {
    kotlin.reflect.KClass getOrCreateKotlinClass(java.lang.Class);
}
-keep class kotlin.jvm.internal.StringCompanionObject {
    kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keepclassmembers class kotlin.random.Random {
    byte[] nextBytes(int);
}
-keep class kotlin.random.RandomKt {
    kotlin.random.Random Random(int);
}
-keepclassmembers class kotlin.ranges.IntRange {
    <init>(int,int);
}
-keep class kotlin.reflect.KClass {
    java.lang.String getSimpleName();
}
-keep class kotlin.text.StringsKt
-keepclassmembers class kotlin.text.StringsKt,kotlin.text.StringsKt__* {
    boolean isBlank(java.lang.CharSequence);
    java.lang.CharSequence trim(java.lang.CharSequence);
}
-keepclassmembers class kotlin.time.Duration {
    kotlin.time.Duration$Companion Companion;
}
-keep class kotlin.time.Duration$Companion
-keep class kotlin.time.DurationKt {
    long toDuration(int,kotlin.time.DurationUnit);
}

# kotlinx.coroutines.
-keep class kotlinx.coroutines.BuildersKt
-keepclassmembers class kotlinx.coroutines.BuildersKt,kotlinx.coroutines.BuildersKt__* {
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2);
    java.lang.Object runBlockingK$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2);
}
-keepclassmembers class kotlinx.coroutines.CancellableContinuation {
    void invokeOnCancellation(kotlin.jvm.functions.Function1);
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
}
-keep class kotlinx.coroutines.CancellableContinuation$DefaultImpls {
    boolean cancel$default(kotlinx.coroutines.CancellableContinuation,java.lang.Throwable,int,java.lang.Object);
}
-keepclassmembers class kotlinx.coroutines.CancellableContinuationImpl {
    <init>(kotlin.coroutines.Continuation,int);
}
-keepclassmembers class kotlinx.coroutines.Deferred {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keepclassmembers class kotlinx.coroutines.Dispatchers {
    kotlinx.coroutines.CoroutineDispatcher getUnconfined();
    kotlinx.coroutines.MainCoroutineDispatcher getMain();
}
-keep class kotlinx.coroutines.ExecutorsKt {
    kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep class kotlinx.coroutines.Job$DefaultImpls {
    void cancel$default(kotlinx.coroutines.Job,java.util.concurrent.CancellationException,int,java.lang.Object);
}
-keep class kotlinx.coroutines.MainCoroutineDispatcher
-keep class kotlinx.coroutines.TimeoutKt {
    java.lang.Object withTimeout-KLykuaI(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}

# Libraries the androidx.test runtime calls.
-keepclassmembers class androidx.concurrent.futures.AbstractResolvableFuture {
    java.lang.Object getUninterruptibly(java.util.concurrent.Future);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter {
    com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter$Resolver
-keep class androidx.concurrent.futures.DirectExecutor {
    androidx.concurrent.futures.DirectExecutor INSTANCE;
}
-keepclassmembers class androidx.concurrent.futures.ResolvableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep class androidx.tracing.Trace {
    void beginSection(java.lang.String);
    void endSection();
    void forceEnableAppTracing();
}
-keep class com.google.common.util.concurrent.ListenableFuture {
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
