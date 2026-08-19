import migration.AuditUtils
import migration.ContentSnapshot
import migration.ReportBuilder
import migration.checks.ContentCompare

import com.kms.katalon.core.configuration.RunConfiguration

/**
 * The whole compare half of the project, run outside Katalon over the snapshots on disk:
 * diff every captured page, write findings.csv / score.csv / the verdict, then build the
 * reports. No browser, no VPN, no Studio — seconds.
 *
 * This is `mode=recompare` plus the report test cases, and it exists so a change to a
 * matching rule or a score weight can be judged against real data before anything is run
 * against the live sites.
 */

String proj = System.getProperty('user.dir')
RunConfiguration.setExecutionSetting([(RunConfiguration.PROJECT_DIR_PROPERTY): proj])

List rows = []
new File(proj + '/Data Files/aem-url-mapping.csv').readLines('UTF-8').drop(1).each { String line ->
	if (!line?.trim()) return
	def c = line.split(',')
	if (c.length < 2) return
	String aem = c[1].trim()
	Map live = ContentSnapshot.load(aem, 'sitecore')
	Map neu = ContentSnapshot.load(aem, 'aem')
	if (live == null || neu == null) return

	Map result = ContentCompare.diff(live, neu)
	String outDir = AuditUtils.reportDir('ContentAudit', aem)
	ContentCompare.write(result, outDir)
	String summary = ContentCompare.summary(result)
	List errors = ((List) result.findings).findAll { ContentCompare.ERRORS.contains(it.verdict) }
	AuditUtils.recordResult(aem, 'content', errors.isEmpty() ? 'PASS' : 'FAIL', summary)

	Map s = (Map) result.score
	Map n = (Map) result.counts
	rows << [slug: AuditUtils.slugOf(aem), items: result.scItems, miss: (n.MISSING_ON_AEM ?: 0),
		tab: (n.WRONG_TAB ?: 0), num: (n.NUMBER_CHANGED ?: 0), chg: (n.TEXT_CHANGED ?: 0),
		cnt: (n.COUNT_MISMATCH ?: 0), only: (n.ONLY_ON_AEM ?: 0),
		score: s.score, grade: s.grade, conf: s.confidence, verdict: errors.isEmpty() ? 'PASS' : 'FAIL']
}

if (rows.isEmpty()) {
	println '  no page has both snapshots on disk — run mode=baseline and mode=capture first'
	return
}

printf('  %-42s %5s %5s %4s %4s %4s %4s %5s %7s %-6s %-7s%n',
	'page', 'items', 'miss', 'tab', 'num', 'chg', 'cnt', 'only', 'score', 'grade', 'verdict')
rows.each { r ->
	printf('  %-42s %5d %5d %4d %4d %4d %4d %5d %7s %-6s %-7s%s%n',
		r.slug.take(42), r.items, r.miss, r.tab, r.num, r.chg, r.cnt, r.only,
		r.score, r.grade, r.verdict, r.conf == 'ok' ? '' : ' low confidence')
}

List confident = rows.findAll { it.conf == 'ok' }
double avg = confident ? confident.sum { it.score as double } / confident.size() : 0d
println()
println "  ${rows.size()} page(s) compared, ${rows.count { it.verdict == 'FAIL' }} failed the verdict, " +
	"${rows.count { it.grade == 'FAIL' }} graded FAIL"
println "  average score over the ${confident.size()} confident page(s): ${Math.round(avg * 10d) / 10d}"
println '  worst: ' + rows.sort { it.score as double }.take(3).collect { "${it.slug} (${it.score})" }.join(', ')

println()
println '  building the reports...'
println '    ' + ReportBuilder.build()
println '    ' + ReportBuilder.buildPublishBundle()
println '    ' + ReportBuilder.buildMastersheetColumn()
println '    ' + ReportBuilder.buildBaselineSummary()
