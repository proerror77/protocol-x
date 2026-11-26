package com.mandonothing.ops.comm.rns

/**
 * Safely loads the Rust `libprotocolx_rns.so` native library on Android while degrading
 * gracefully on host JVM unit tests where Android ABI `.so` artifacts are absent.
 */
internal object RnsNativeLoader {
    private const val LIB_NAME = "protocolx_rns"

    @Volatile
    private var attempted = false

    @Volatile
    var isNativeAvailable: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (attempted) return isNativeAvailable
        attempted = true
        return try {
            System.loadLibrary(LIB_NAME)
            isNativeAvailable = true
            loadError = null
            true
        } catch (error: UnsatisfiedLinkError) {
            isNativeAvailable = false
            loadError = error.message ?: "Native library $LIB_NAME not available"
            false
        } catch (error: SecurityException) {
            isNativeAvailable = false
            loadError = error.message ?: "Native library $LIB_NAME blocked by security policy"
            false
        }
    }
}
