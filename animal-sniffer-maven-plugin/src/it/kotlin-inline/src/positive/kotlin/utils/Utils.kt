// Positive coverage for issue #311: a guarded annotated inline getter, an annotated inline function,
// and repeated getter expansions within the same JVM class. All copied threadId() calls should be
// suppressed, so checking against the Java 8 signature must report no violations from this file.
package utils

import android.os.Build
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

val Thread.threadIdCompat: Long
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            threadIdApi36
        } else {
            @Suppress("DEPRECATION")
            id
        }

// An extension property without a backing field needs an explicit getter target.
@get:IgnoreJRERequirement
inline val Thread.threadIdApi36: Long
    get() = threadId()

@IgnoreJRERequirement
inline fun annotatedId(thread: Thread): Long = thread.threadId()

fun sameClassFunction(thread: Thread): Long = annotatedId(thread)

fun repeatedCopies(thread: Thread): Long = thread.threadIdApi36 + thread.threadIdApi36
