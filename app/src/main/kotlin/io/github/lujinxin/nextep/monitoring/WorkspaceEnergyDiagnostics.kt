package io.github.x647368maker.atomicworkbench.monitoring

import java.util.concurrent.atomic.AtomicLong

/** Work counters only. They do not estimate power or attribute shared-host CPU to NeXtep. */
internal object WorkspaceEnergyDiagnostics {
    val taskQueries = AtomicLong()
    val previewCaptures = AtomicLong()
}
