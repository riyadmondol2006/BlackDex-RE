package top.niunaijun.blackdex.data.entity

/**
 * Presentation state of the single dump operation the UI is tracking. Held by the ViewModel so
 * it survives configuration changes; the Activity and dialogs only render it.
 */
data class DumpUiState(
    /** Monotonic id; events carrying an older id are ignored. */
    val operationId: Long,
    /** What was dumped: a package name or an imported APK path/URI. */
    val source: String,
    /** Target package, when known up front (null for imported APKs). */
    val packageName: String?,
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
    val outputDir: String? = null,
    val error: Error = Error.NONE,
    val errorMessage: String? = null,
    /** True once the Activity has shown the result dialog for this operation. */
    val resultDelivered: Boolean = false,
) {
    enum class Phase { LAUNCHING, DUMPING, FINISHING, SUCCESS, FAILED }

    enum class Error {
        NONE,
        /** The dump engine reported an error ([errorMessage] has details). */
        ENGINE,
        /** The sandbox failed to launch or produced nothing (likely incompatible/detected). */
        UNKNOWN,
        /** The picked APK could not be read. */
        IMPORT,
        /** The picked APK only ships native code this device cannot run. */
        ABI_MISMATCH,
    }

    val isActive: Boolean
        get() = phase == Phase.LAUNCHING || phase == Phase.DUMPING || phase == Phase.FINISHING

    val isFinished: Boolean
        get() = !isActive

    val hasValidProgress: Boolean
        get() = total > 0 && current in 0..total
}
