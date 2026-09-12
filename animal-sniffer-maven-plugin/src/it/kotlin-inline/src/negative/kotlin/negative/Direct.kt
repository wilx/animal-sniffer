// An annotated getter expansion and a direct threadId() call share the same caller source line.
// Only the copied invocation is suppressed; the direct call must produce one violation.
package negative
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
@get:IgnoreJRERequirement
inline val Thread.directApi: Long get() = threadId()
fun direct(thread: Thread): Long = thread.directApi + thread.threadId()
