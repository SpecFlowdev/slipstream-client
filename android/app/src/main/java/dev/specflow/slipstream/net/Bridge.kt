package dev.specflow.slipstream.net

/**
 * The packet bridge, which is a library rather than a program.
 *
 * Its standalone binary makes its own tun device with an ioctl an ordinary
 * Android app is not allowed to perform, so the only usable entry point is
 * this one: hand it the descriptor Android already granted and a config file
 * naming the proxy to speak to.
 *
 * The native side registers these four methods against this exact class, so
 * the package and class name here are compile-time arguments to the library
 * (`-DPKGNAME` and `-DCLSNAME`); renaming either without changing the build
 * produces a library that loads and then finds nothing to bind to.
 */
class Bridge {

    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** Packets and bytes, in and out, as the bridge itself counted them. */
    external fun TProxyGetStats(): LongArray

    companion object {
        /**
         * True when the library for this device's processor is in the APK.
         * A build without one still installs and runs; it says so rather than
         * failing at the moment someone taps connect.
         */
        val available: Boolean = try {
            System.loadLibrary("hev-socks5-tunnel")
            true
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (missing library, wrong ABI, an unresolved
            // symbol) is the expected shape of "not available," but this is
            // deliberately caught broader than that: a native library load is
            // one of the few JVM operations that can also throw a bare Error
            // outside that hierarchy depending on the device and Android
            // version, and every one of those cases means the same thing —
            // no tunnel on this device — not a reason to crash on Connect.
            android.util.Log.e("SlipstreamBridge", "packet bridge library did not load", e)
            false
        }
    }
}
