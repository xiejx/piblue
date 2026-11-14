package com.google.common.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * The one and only type that the Guava artifact {@code com.google.guava:listenablefuture:1.0}
 * ships: a bare interface, no implementation.
 *
 * <p>Why it lives here instead of on the dependency list:
 *
 * <p>{@code androidx.concurrent:concurrent-futures} (pulled in by androidx.core) declares a
 * dependency on that artifact, but the offline Gradle cache on the build machine only has its
 * .pom, not its .jar, so the build used to force the "9999.0-empty-to-avoid-conflict-with-guava"
 * placeholder. That placeholder is *empty* - it is meant for projects that already depend on full
 * Guava, whose classes then supply the interface.
 *
 * <p>This app has no Guava, so the placeholder left the APK referencing a class that simply was
 * not there. Nothing touched it during startup, which is why the app appeared fine - until
 * {@code androidx.profileinstaller} ran its delayed profile write 5-6 seconds after launch and
 * the ART verifier rejected {@code ProfileVerifier} ("can't resolve returned type
 * com.google.common.util.concurrent.ListenableFuture"), throwing VerifyError on a worker thread
 * and killing the process.
 *
 * <p>Declaring the interface here restores the missing type. Signatures must stay in sync with
 * Guava's; if a real Guava jar is ever added, delete this file (it would become a duplicate
 * class).
 */
public interface ListenableFuture<V> extends Future<V> {

    void addListener(Runnable listener, Executor executor);
}
