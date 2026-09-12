// A simple inline getter without an ignore annotation must receive no suppression.
// Expect two violations: threadId() in the getter declaration and in its caller's inline copy.
package negative
inline val Thread.unannotatedApi: Long get() = threadId()
fun unannotated(thread: Thread): Long = thread.unannotatedApi
