// The annotated getter is declared in UtilsKt, outside this caller's JVM class.
// Its copied threadId() call must still produce one violation because cross-class inference is unsupported.
package negative
import utils.threadIdApi36
fun crossClass(thread: Thread): Long = thread.threadIdApi36
