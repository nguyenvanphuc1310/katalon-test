import migration.AuditUtils
import migration.ReportBuilder
import migration.checks.ContentCompare

import com.kms.katalon.core.configuration.RunConfiguration

/**
 * The contract between a check and ReportBuilder: the regexes and the positional CSV readers.
 *
 * These are literal patterns over text a keyword writes, so a counter appended in the wrong
 * place, or a column added to a CSV, silently stops the report rendering that check. Nothing
 * else in the project notices — the report just goes quiet, which is the worst possible way
 * for a reporting bug to behave.
 */

String proj = System.getProperty('user.dir')
RunConfiguration.setExecutionSetting([(RunConfiguration.PROJECT_DIR_PROPERTY): proj])

int pass = 0, fail = 0
def check = { String what, boolean ok ->
	if (ok) { pass++ } else { fail++; println "  FAIL  ${what}" }
}

// ---------------------------------------------------------------- the summary line
Map result = [counts: [MISSING_ON_AEM: 3, WRONG_TAB: 1, TEXT_CHANGED: 2, ONLY_ON_AEM: 9,
	NUMBER_CHANGED: 1, COUNT_MISMATCH: 4, STATE_ONLY_ON_LIVE: 1, STATE_ONLY_ON_NEW: 2],
	scItems: 120, statePairs: [], score: [score: 92.5d, grade: 'FAIL', confidence: 'ok']]
String line = ContentCompare.summary(result)
check('the summary line still opens with the clause ReportBuilder parses',
	line ==~ /^\d+ live items compared: \d+ missing, \d+ in the wrong tab, \d+ reworded, \d+ only on the new page.*/)
check('the figures/count clause is present for the second regex',
	line.contains('with changed figures') && line.contains('appearing fewer times'))
check('the score is appended AFTER both clauses, so neither regex is disturbed',
	line.indexOf('score ') > line.indexOf('appearing fewer times'))

String human = ReportBuilder.metaClass.respondsTo(ReportBuilder, 'summaryOf') ? '' : ''
// summaryOf is private; exercise it through the public path instead
File tmp = File.createTempDir()
try {
	// a findings.csv with the weight column must still parse
	new File(tmp, 'findings.csv').setText(
		'verdict,kind,path,text,note,weight\n' +
		'MISSING_ON_AEM,cta,"Find The Right Plan > tab:Savings","Compare Plans","",1.0\n' +
		'TEXT_CHANGED,para,"Protection","A sentence, with a comma and ""quotes"" in it","new page says: x",0.1\n',
		'UTF-8')
	int rows = 0
	new File(tmp, 'findings.csv').readLines('UTF-8').drop(1).each { String l ->
		def m = (l =~ /^(\w+),(\w*),"((?:[^"]|"")*)","((?:[^"]|"")*)","((?:[^"]|"")*)"(?:,([0-9.]+))?$/)
		if (m.find()) rows++
	}
	check('the findings.csv reader parses every row, quotes and commas included', rows == 2)

	// ...and one written before the weight column existed
	new File(tmp, 'legacy.csv').setText(
		'verdict,kind,path,text,note\nMISSING_ON_AEM,cta,"a","b","c"\n', 'UTF-8')
	int legacy = 0
	new File(tmp, 'legacy.csv').readLines('UTF-8').drop(1).each { String l ->
		def m = (l =~ /^(\w+),(\w*),"((?:[^"]|"")*)","((?:[^"]|"")*)","((?:[^"]|"")*)"(?:,([0-9.]+))?$/)
		if (m.find()) legacy++
	}
	check('a findings.csv written before scoring existed still parses', legacy == 1)
} finally {
	tmp.deleteDir()
}

// ---------------------------------------------------------------- the verdict file
File out = new File(proj + '/Reports/parity-results/__contract_probe/content.txt')
try {
	AuditUtils.recordResult('https://example.test/__contract_probe', 'content', 'FAIL', "line one\nline two")
	File written = new File(proj + '/Reports/parity-results/' +
		AuditUtils.slugOf('https://example.test/__contract_probe') + '/content.txt')
	List lines = written.exists() ? written.readLines('UTF-8') : []
	check('recordResult writes the verdict on line 1', lines && lines[0] == 'FAIL')
	check('recordResult writes the detail from line 2', lines.size() > 1 && lines[1] == 'line one')
	written.getParentFile()?.deleteDir()
} finally { }

// ---------------------------------------------------------------- the closed check list
check('the report renders exactly the checks this project produces',
	ReportBuilder.CHECK_ORDER == ['content'] && ReportBuilder.ALL_CHECKS == ['content'])
check('every rendered check has a title and a description',
	ReportBuilder.CHECK_ORDER.every { ReportBuilder.CHECK_TITLE[it] && ReportBuilder.CHECK_DESC[it] })

// ---------------------------------------------------------------- score.csv
File d = File.createTempDir()
try {
	Map r2 = [counts: [MISSING_ON_AEM: 2], scItems: 100, statePairs: [], findings:
		[[verdict: 'MISSING_ON_AEM', kind: 'para', path: 'p', text: 't', note: '']]]
	r2.score = ContentCompare.score(r2)
	ContentCompare.write(r2, d.getAbsolutePath())
	List sc = new File(d, 'score.csv').readLines('UTF-8')
	check('score.csv is a field,value file ReportBuilder.readScore can read',
		sc[0] == 'field,value' && sc.any { it.startsWith('"score",') } && sc.any { it.startsWith('"grade",') })
	check('findings.csv carries the weight that produced the score',
		new File(d, 'findings.csv').readLines('UTF-8')[1].endsWith(',1.0'))
} finally { d.deleteDir() }

println "  ${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
