package dev.smartdns.core

/** The platform side of Private DNS: read the live value, write a target. */
interface PrivateDnsPort {
    fun read(): PrivateDnsState

    /**
     * @return false when the platform refused the write, which in practice means the app does not
     * hold WRITE_SECURE_SETTINGS (or a ROM policy rejects the key).
     */
    fun write(target: PrivateDnsTarget): Boolean
}

enum class EnforceError {
    WRITE_DENIED,

    /** The write reported success but the value read back is not what was asked for. */
    WRITE_NOT_APPLIED,
}

data class ReconcileResult(
    val desired: Desired,
    val changed: Boolean,
    val error: EnforceError?,
) {
    val target: PrivateDnsTarget? get() = (desired as? Desired.Apply)?.target
}

/**
 * Reconciles the system setting with the policy. The system value is the source of truth: there is
 * no cached "current" state, so an external edit, a reboot or a process restart is corrected by the
 * next run, and a value that already matches costs one read and zero writes.
 */
class DnsEnforcer(private val port: PrivateDnsPort) {
    fun reconcile(connection: ConnectionState, rule: DnsRule?, enabled: Boolean): ReconcileResult {
        if (!enabled) return ReconcileResult(Desired.Keep, changed = false, error = null)

        val desired = DnsPolicy.desired(connection, rule)
        val target = (desired as? Desired.Apply)?.target
            ?: return ReconcileResult(desired, changed = false, error = null)

        if (matches(port.read(), target)) return ReconcileResult(desired, changed = false, error = null)
        if (!port.write(target)) return ReconcileResult(desired, changed = false, EnforceError.WRITE_DENIED)

        return if (matches(port.read(), target)) {
            ReconcileResult(desired, changed = true, error = null)
        } else {
            ReconcileResult(desired, changed = false, EnforceError.WRITE_NOT_APPLIED)
        }
    }

    private fun matches(state: PrivateDnsState, target: PrivateDnsTarget): Boolean =
        state.mode == target.mode.value && (state.specifier ?: "") == target.specifier
}
