// Inline scope names cannot distinguish these overloads, even though only one is annotated.
// Expect two violations: the unannotated declaration and the ambiguous copy of the annotated overload.
package negative
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
@IgnoreJRERequirement inline fun overloaded(thread: Thread): Long = thread.threadId()
inline fun overloaded(thread: Thread, unused: Int): Long = thread.threadId()
fun overloadedCaller(thread: Thread): Long = overloaded(thread)
