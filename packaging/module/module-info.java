/*
 * The bundles' app module: the whole fat jar (XDM, Kotlin, kotlinx-serialization, OkHttp, okio, FlatLaf)
 * as one module, linked into the runtime image by jlink (packaging/ModularJar.java builds the jar).
 *
 * Why a module at all: the JVM checks a classpath jar against the AppCDS archive by size and exact
 * mtime, and an MSI install moves the mtime by the time-zone difference between the build machine and
 * the installing one (cabinets store local time). Classes in the runtime image are checked by size only
 * (APPCDS.md).
 *
 * open: reflection and resource lookups into every package work as they do on the classpath.
 * requires: what jdeps reports (java.desktop, java.logging) plus jdk.unsupported (sun.misc.Unsafe,
 * reached reflectively by the Kotlin/okio stack). jdk.crypto.ec is only linked into the image.
 * uses: every ServiceLoader.load in the jar; a named module must declare it or the call throws.
 * exports xdm.app: makes the module a default root, so a class-path launch still finds AppMain in the
 * image. That is what a per-user launcher .cfg from before the module says (JitOverride's Full JIT copy:
 * app.classpath=$APPDIR/xdm-app.jar, a jar no longer installed); XDM then starts and sync() rewrites it.
 */
open module xdm.app {
    requires java.desktop;
    requires java.logging;
    requires jdk.unsupported;

    exports xdm.app;

    uses com.formdev.flatlaf.FlatDefaultsAddon;
}
