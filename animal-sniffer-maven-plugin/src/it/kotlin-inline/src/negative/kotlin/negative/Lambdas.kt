// Annotating an inline function must not suppress code supplied by its caller's lambda.
// This function's body is unsupported, so both threadId() calls in the expanded caller remain violations.
package negative
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
@IgnoreJRERequirement inline fun accepting(block: () -> Thread): Long = block().threadId()
fun lambdaCaller(thread: Thread): Long = accepting { thread.threadId(); thread }
