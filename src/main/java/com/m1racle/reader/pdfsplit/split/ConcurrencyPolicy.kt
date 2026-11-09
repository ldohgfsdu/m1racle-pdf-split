package com.m1racle.reader.pdfsplit.split

/**
 * How many parts may be written at the same time.
 *
 * Measured on the reference device (2026-11-08, synthetic 926 MB / 399 page
 * 600 dpi scan; logs under `output/pdf-split-ui-20261108/bench-*.log`):
 *
 * | setting                    | one load | scratch written   |
 * |----------------------------|----------|-------------------|
 * | tempFileOnly (old default) | 4.1 s    | 932 MB per worker |
 * | mixed(128 MB)              | 3.0 s    | 804 MB per worker |
 *
 * Every worker loads **its own** `PDDocument` over the same staged file, and
 * PDFBox spills that whole document into the scratch file, so N workers write
 * N x (document size) before the first part can even start. On the real
 * 420 page book that was about 5.6 GB of scratch for one split, while the 21
 * parts themselves finished in roughly 4 seconds regardless of worker count.
 *
 * Parallelism therefore buys nothing here and costs I/O amplification: one
 * worker does the same work with 1/N of the scratch traffic. The cap stays at
 * 1 until the engine can share a single parsed document between workers.
 */
object ConcurrencyPolicy {

    const val HARD_CAP = 1

    /** Default for users who never open advanced settings. */
    fun auto(cores: Int): Int = 1

    /** Even a manual choice cannot exceed what the device can plausibly hold. */
    fun maxAllowed(cores: Int): Int = 1

    fun sanitize(requested: Int, cores: Int): Int =
        requested.coerceIn(1, maxAllowed(cores))
}
