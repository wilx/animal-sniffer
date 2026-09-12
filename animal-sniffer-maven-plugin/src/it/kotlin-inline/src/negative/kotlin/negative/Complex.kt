// The annotated declaration adds arithmetic after the invocation, outside the supported body shape.
// Its declaration is ignored, but its copied threadId() call must still produce one violation.
package negative
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
@IgnoreJRERequirement inline fun complex(thread: Thread): Long = thread.threadId() + 1
fun complexCaller(thread: Thread): Long = complex(thread)
