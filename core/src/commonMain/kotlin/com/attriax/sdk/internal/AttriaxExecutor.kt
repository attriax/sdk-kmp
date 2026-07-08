package com.attriax.sdk.internal

/**
 * Minimal command executor seam (replaces `java.util.concurrent.Executor`) used by
 * the consent manager for its background sync. Kept framework-free so tests inject a
 * synchronous fake; the real Android/JVM-backed executor lands in a later chunk.
 */
fun interface AttriaxExecutor {
    fun execute(command: () -> Unit)
}
