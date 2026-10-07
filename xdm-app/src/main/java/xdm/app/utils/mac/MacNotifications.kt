package xdm.app.utils.mac

import xdm.app.AppContext
import xdm.app.OS
import xdm.app.utils.detectOS
import xdm.core.util.Logger
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Notifications through `UNUserNotificationCenter`, called through java.lang.foreign.
 *
 * Why not `TrayIcon.displayMessage`: on macOS the JDK posts through the deprecated `NSUserNotification`,
 * which never asks for permission. macOS registers such an app with "Allow notifications" off, accepts
 * every post and shows nothing (usernoted logs it as "Presenting … as none"). `UNUserNotificationCenter`
 * asks the user the first time, reports a refusal so the caller can fall back, and tells us when a
 * notification is clicked.
 *
 * Only for a real app bundle (the jpackage `.app`): `currentNotificationCenter` raises an Objective-C
 * exception in a process without one, such as `java -jar`, and that would kill the JVM. Those runs keep
 * the tray path.
 */
object MacNotifications {

    private const val AUTH_SOUND = 1L shl 1
    private const val AUTH_ALERT = 1L shl 2
    private const val PRESENT_SOUND = 1L shl 1
    private const val PRESENT_LIST = 1L shl 3
    private const val PRESENT_BANNER = 1L shl 4
    private const val STATUS_DENIED = 1L

    /** Null until macOS has told us; true when XDM's notifications are off. Only used for logging. */
    @Volatile
    private var denied: Boolean? = null

    /** What a click does, by request identifier, for notifications that have an action of their own. */
    private val clickActions = ConcurrentHashMap<String, () -> Unit>()

    /** Work waiting for the next authorization answer: posts, and "tell me if refused" callbacks. */
    private val pending = ConcurrentLinkedQueue<(Boolean) -> Unit>()

    private lateinit var center: MemorySegment
    private lateinit var authorizationBlock: MemorySegment
    private lateinit var settingsBlock: MemorySegment
    private lateinit var addedBlock: MemorySegment

    /** The bundle id, when this process is an app bundle; notifications are keyed by it. */
    private var bundleId: String? = null

    /** True when notifications go through `UNUserNotificationCenter`. Set up on first use. */
    val isAvailable: Boolean by lazy {
        detectOS() == OS.MacOS && runCatching { setUp() }
            .onFailure { Logger.error("Notifications", "UNUserNotificationCenter unavailable", it) }
            .getOrDefault(false)
    }

    /**
     * Posts a notification, asking for permission first if the user has never been asked. A click runs
     * [onClick] (on a system thread), or brings up the window when it is null. [onDenied] runs (on a
     * background thread) instead when XDM's notifications are turned off.
     */
    fun post(title: String, body: String, onClick: (() -> Unit)?, onDenied: () -> Unit) {
        if (!isAvailable) return onDenied()
        pending += { granted -> if (granted) add(title, body, onClick) else onDenied() }
        requestAuthorization()
    }

    /**
     * Asks for permission now (the system prompt appears only the first time) and calls [onDenied],
     * on a background thread, when notifications are off for XDM.
     */
    fun requestPermission(onDenied: () -> Unit) {
        if (!isAvailable) return
        pending += { granted -> if (!granted) onDenied() }
        requestAuthorization()
    }

    /** Opens XDM's page in System Settings → Notifications. */
    fun openSystemSettings() {
        val id = bundleId ?: return
        runCatching {
            ProcessBuilder("open", "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=$id").start()
        }.onFailure { Logger.error("Notifications", "Could not open System Settings", it) }
    }

    private fun setUp(): Boolean = ObjC.autoreleasing {
        val bundle = ObjC.call(ObjC.cls("NSBundle"), "mainBundle")
        val path = ObjC.string(ObjC.call(bundle, "bundlePath"))
        val id = ObjC.string(ObjC.call(bundle, "bundleIdentifier"))
        if (id == null || path?.endsWith(".app") != true) {
            Logger.info("Notifications", "Not an app bundle ($path); using the tray icon")
            return@autoreleasing false
        }
        bundleId = id
        // Registers UNUserNotificationCenter and friends with the runtime.
        SymbolLookup.libraryLookup("/System/Library/Frameworks/UserNotifications.framework/UserNotifications", Arena.global())

        val self = MacNotifications::class.java
        authorizationBlock = ObjC.globalBlock(
            ObjC.upcall(self, "onAuthorization", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BYTE, ADDRESS)), "v@?B@"
        )
        settingsBlock = ObjC.globalBlock(
            ObjC.upcall(self, "onSettings", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)), "v@?@"
        )
        addedBlock = ObjC.globalBlock(
            ObjC.upcall(self, "onAdded", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)), "v@?@"
        )
        val delegateMethod = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
        val delegateClass = ObjC.defineClass(
            "XDMNotificationDelegate", mapOf(
                "userNotificationCenter:willPresentNotification:withCompletionHandler:" to
                        (ObjC.upcall(self, "willPresent", delegateMethod) to "v@:@@@?"),
                "userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:" to
                        (ObjC.upcall(self, "didReceive", delegateMethod) to "v@:@@@?"),
            )
        )
        // alloc/init without a release: the center holds its delegate weakly, so this one lives forever.
        val delegate = ObjC.call(ObjC.call(delegateClass, "alloc"), "init")
        center = ObjC.call(ObjC.cls("UNUserNotificationCenter"), "currentNotificationCenter")
        sendVoid(center, "setDelegate:", delegate)
        // Logs the current state without prompting.
        sendVoid(center, "getNotificationSettingsWithCompletionHandler:", settingsBlock)
        Logger.info("Notifications", "Using UNUserNotificationCenter for $id")
        true
    }

    private fun requestAuthorization() = runCatching {
        ObjC.autoreleasing {
            ObjC.send(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)).invoke(
                center, ObjC.sel("requestAuthorizationWithOptions:completionHandler:"), AUTH_ALERT or AUTH_SOUND,
                authorizationBlock
            )
        }
    }.onFailure { Logger.error("Notifications", "requestAuthorization failed", it) }

    private fun add(title: String, body: String, onClick: (() -> Unit)?) = runCatching {
        ObjC.autoreleasing {
            val content = ObjC.call(ObjC.call(ObjC.cls("UNMutableNotificationContent"), "alloc"), "init")
            try {
                sendVoid(content, "setTitle:", ObjC.nsString(title))
                sendVoid(content, "setBody:", ObjC.nsString(body))
                sendVoid(content, "setSound:", ObjC.call(ObjC.cls("UNNotificationSound"), "defaultSound"))
                val identifier = ObjC.call(ObjC.call(ObjC.cls("NSUUID"), "UUID"), "UUIDString")
                if (onClick != null) ObjC.string(identifier)?.let { clickActions[it] = onClick }
                val request = ObjC.send(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
                    .invoke(
                        ObjC.cls("UNNotificationRequest"), ObjC.sel("requestWithIdentifier:content:trigger:"),
                        identifier, content, MemorySegment.NULL
                    ) as MemorySegment
                sendVoid(center, "addNotificationRequest:withCompletionHandler:", request, addedBlock)
            } finally {
                sendVoid(content, "release")
            }
        }
    }.onFailure { Logger.error("Notifications", "Could not post a notification", it) }

    private fun sendVoid(receiver: MemorySegment, selector: String, vararg args: MemorySegment) {
        val layouts = arrayOf(ADDRESS, ADDRESS) + Array(args.size) { ADDRESS }
        ObjC.send(FunctionDescriptor.ofVoid(*layouts))
            .invokeWithArguments(listOf(receiver, ObjC.sel(selector)) + args)
    }

    // ---- callbacks: on system dispatch threads. An exception escaping an upcall ends the process. ----

    @JvmStatic
    fun onAuthorization(block: MemorySegment, granted: Byte, error: MemorySegment) {
        runCatching {
            val ok = granted.toInt() != 0
            if (denied != !ok) {
                Logger.info("Notifications", if (ok) "Allowed" else "Turned off for XDM in System Settings")
            }
            denied = !ok
            while (true) {
                val action = pending.poll() ?: break
                runCatching { action(ok) }.onFailure { Logger.error("Notifications", "Callback failed", it) }
            }
        }
    }

    @JvmStatic
    fun onSettings(block: MemorySegment, settings: MemorySegment) {
        runCatching {
            val status = ObjC.send(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS))
                .invoke(settings, ObjC.sel("authorizationStatus")) as Long
            // Not determined (0) stays null: the first post will ask.
            if (status == STATUS_DENIED) denied = true else if (status > STATUS_DENIED) denied = false
            Logger.info("Notifications", "Authorization status $status")
        }
    }

    @JvmStatic
    fun onAdded(block: MemorySegment, error: MemorySegment) {
        runCatching {
            if (error != MemorySegment.NULL) {
                ObjC.autoreleasing {
                    Logger.error("Notifications", "Not posted: ${ObjC.string(ObjC.call(error, "localizedDescription"))}")
                }
            }
        }
    }

    /** XDM is frontmost: show the banner anyway (the default would be to drop it). */
    @JvmStatic
    fun willPresent(self: MemorySegment, cmd: MemorySegment, center: MemorySegment, notification: MemorySegment, handler: MemorySegment) {
        runCatching {
            ObjC.invokeBlock(handler, FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG), PRESENT_BANNER or PRESENT_LIST or PRESENT_SOUND)
        }
    }

    /**
     * A click on a notification: its own action when it has one, else bring the window up, as a click on
     * the tray icon does. Notifications left over from an earlier run have no action here any more.
     */
    @JvmStatic
    fun didReceive(self: MemorySegment, cmd: MemorySegment, center: MemorySegment, response: MemorySegment, handler: MemorySegment) {
        runCatching {
            val id = ObjC.autoreleasing {
                ObjC.string(ObjC.call(ObjC.call(ObjC.call(response, "notification"), "request"), "identifier"))
            }
            val action = id?.let { clickActions.remove(it) }
            (action ?: AppContext.app::showAppWindow)()
        }.onFailure { Logger.error("Notifications", "Click action failed", it) }
        runCatching { ObjC.invokeBlock(handler, FunctionDescriptor.ofVoid(ADDRESS)) }
    }
}
