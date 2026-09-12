def cases = ['positive-19', 'positive-23', 'positive-scopes', 'negative-19', 'negative-23', 'negative-scopes']
cases.each { testCase ->
    assert new File(basedir, "target/${testCase}/classes/utils/UtilsKt.class").isFile()
}

def logs = basedir.listFiles().findAll { it.name.startsWith('build') && it.name.endsWith('.log') }
assert !logs.isEmpty()
def errors = logs.collectMany { it.readLines('UTF-8') }.findAll { it.contains('[ERROR]') && it.contains('Undefined reference:') }
// Every negative build must report precisely its controls; all positive Utils methods remain clean.
def counts = [Direct: 1, Unannotated: 2, Overloaded: 2, Lambdas: 2, CrossClass: 1, Complex: 1]
assert errors.size() == 3 * counts.values().sum() : errors.join('\n')
cases.findAll { it.startsWith('negative') }.each { testCase ->
    def caseErrors = errors.findAll { it.contains(testCase) }
    counts.each { name, count ->
        assert caseErrors.count { it.contains(name) && it.contains('long Thread.threadId()') } == count : caseErrors.join('\n')
    }
}
return true
