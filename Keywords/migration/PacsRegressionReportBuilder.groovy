package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * One HTML report for the PACS AEM regression pack (PRUDiscovery now, Custom pages next).
 * AEM UAT only — same screenshot cards as the Discovery report. Sitecore is not in this run.
 *
 * Each recorded row has a {@code section} (tab). startRun(section) clears that tab only
 * so a later product suite does not wipe Discovery results.
 */
public class PacsRegressionReportBuilder {

	static final String RUN_FOLDER = 'Reports/PACS-Regression/latest'
	static final String OUT_FOLDER = 'Reports/pacs-regression-report'

	static final List SECTION_ORDER = [
		'prudiscovery', 'ppc', 'prushield', 'pruchat', 'opus', 'awards', 'shariah', 'pruadvisers',
	]

	static final Map SECTION_TITLE = [
		prudiscovery: 'PRUDiscovery',
		ppc         : 'PPC Extended Panel',
		prushield   : 'PRUShield',
		pruchat     : 'PRUChat Widget',
		opus        : 'Opus',
		awards      : 'Agency Awards Night',
		shariah     : 'Shariah Quiz',
		pruadvisers : 'PRUAdvisers',
	]

	@Keyword
	static File runDir() {
		File d = new File(RunConfiguration.getProjectDir() + '/' + RUN_FOLDER)
		d.mkdirs()
		return d
	}

	@Keyword
	static File evidenceDir(String section) {
		File d = new File(runDir(), 'evidence/' + slug(section))
		d.mkdirs()
		return d
	}

	/** Do not wipe. Kept so old suite calls do not erase other tabs. */
	@Keyword
	static void startRun() {
		runDir()
		KeywordUtil.logInfo('PACS regression: startRun() does not wipe other sections. Use startRun("prudiscovery") to refresh one tab.')
	}

	/**
	 * Clear ONLY this tab (screenshots + rows). Other tabs stay on disk.
	 * Discovery suite: startRun('prudiscovery') — Custom pages / PRUAdvisers are not re-run.
	 */
	@Keyword
	static void startRun(String section) {
		File d = runDir()
		d.mkdirs()
		if (!section) {
			startRun()
			return
		}
		String key = slug(section)
		Map data = readJson()
		List rows = (data.scenarios instanceof List) ? new ArrayList((List) data.scenarios) : []
		rows.removeAll { slug(((Map) it).section ?: 'prudiscovery') == key }
		data.scenarios = rows
		data.generatedAt = now()
		writeJson(data)
		File ev = new File(d, 'evidence/' + key)
		if (ev.directory) ev.deleteDir()
		KeywordUtil.logInfo('PACS regression section reset: ' + key)
	}

	/** Clear every Custom-pages tab. Leaves the PRUDiscovery tab in place. */
	@Keyword
	static void startCustomPagesRun() {
		['ppc', 'prushield', 'pruchat', 'opus', 'awards', 'shariah'].each { startRun(it) }
	}

	@Keyword
	static void record(Map scenario) {
		if (!scenario.section) scenario.section = 'prudiscovery'
		scenario.section = slug(scenario.section.toString())
		Map data = readJson()
		List rows = (data.scenarios instanceof List) ? new ArrayList((List) data.scenarios) : []
		String id = scenario.testId?.toString()
		String sec = scenario.section.toString()
		rows.removeAll { ((Map) it).testId == id && slug(((Map) it).section ?: 'prudiscovery') == sec }
		rows << scenario
		data.scenarios = rows
		data.generatedAt = now()
		writeJson(data)
	}

	@Keyword
	static String build() {
		Map data = readJson()
		File out = new File(RunConfiguration.getProjectDir() + '/' + OUT_FOLDER)
		if (out.exists()) out.deleteDir()
		out.mkdirs()
		File assets = new File(out, 'assets')
		assets.mkdirs()
		copyEvidence(assets)

		File html = new File(out, 'index.html')
		html.setText(render(data), 'UTF-8')
		KeywordUtil.logInfo('PACS regression report -> ' + html.absolutePath)
		return html.absolutePath
	}

	private static void copyEvidence(File assets) {
		File src = new File(runDir(), 'evidence')
		if (!src.directory) return
		src.eachFileRecurse { File f ->
			if (!f.name.toLowerCase().endsWith('.png')) return
			String rel = f.absolutePath.replace('\\', '/')
				.replace(src.absolutePath.replace('\\', '/') + '/', '')
			File dest = new File(assets, rel)
			dest.parentFile.mkdirs()
			java.nio.file.Files.copy(f.toPath(), dest.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private static String render(Map data) {
		List rows = (data.scenarios instanceof List) ? (List) data.scenarios : []
		List sections = sectionKeys(rows)
		int pass = rows.count { ((Map) it).status == 'PASS' } as int
		int fail = rows.count { ((Map) it).status == 'FAIL' } as int
		String first = sections ? sections[0] : 'prudiscovery'
		StringBuilder h = new StringBuilder()
		h << '<!doctype html><html lang="en"><head><meta charset="utf-8">'
		h << '<meta name="viewport" content="width=device-width, initial-scale=1">'
		h << '<title>PACS AEM regression</title>'
		h << '<style>' << css() << '</style></head><body><main>'
		h << '<header class="cover"><p class="eyebrow">PACS AEM regression</p>'
		h << '<h1>AEM regression pack</h1>'
		h << '<p class="lede">AEM UAT only. Same screenshot cards as PRUDiscovery. '
		h << 'Tabs are pack areas from the 2026-08-15 sheet. Sitecore is not part of this run.</p>'
		h << '<dl class="facts"><div><dt>Scenarios</dt><dd>' << rows.size() << '</dd></div>'
		h << '<div><dt>Passed</dt><dd class="good">' << pass << '</dd></div>'
		h << '<div><dt>Failed</dt><dd class="' << (fail ? 'bad' : '') << '">' << fail << '</dd></div>'
		h << '<div><dt>Sections</dt><dd>' << sections.size() << '</dd></div>'
		h << '<div><dt>Generated</dt><dd class="when">' << esc(data.generatedAt) << '</dd></div></dl></header>'

		h << '<nav class="tabs" aria-label="Pack area">'
		sections.eachWithIndex { String key, int i ->
			List part = rows.findAll { slug(((Map) it).section ?: 'prudiscovery') == key }
			int fp = part.count { ((Map) it).status == 'FAIL' } as int
			h << '<a href="#section-' << escAttr(key) << '" class="' << (i == 0 ? 'is-on' : '') << '" data-tab="' << escAttr(key) << '">'
			h << esc(sectionTitle(key)) << ' <span class="tab-n">' << part.size() << '</span>'
			if (fp) h << ' <span class="tab-fail">' << fp << '</span>'
			h << '</a>'
		}
		h << '</nav>'

		sections.eachWithIndex { String key, int i ->
			List part = rows.findAll { slug(((Map) it).section ?: 'prudiscovery') == key }
			h << '<div class="tab-panel' << (i == 0 ? ' is-on' : '') << '" id="section-' << escAttr(key) << '" data-panel="' << escAttr(key) << '">'
			h << matrix(part)
			part.each { Map r -> h << article(r) }
			h << '</div>'
		}

		h << '<footer class="foot"><p class="sub">AEM host aem-uat.prudential.com.sg · generated '
		h << esc(data.generatedAt) << '</p></footer></main>'
		h << '<script>' << tabsJs() << '</script></body></html>'
		return h.toString()
	}

	private static String matrix(List rows) {
		StringBuilder h = new StringBuilder()
		h << '<section><h2 class="minor">Scenarios</h2><div class="scroll"><table class="matrix">'
		h << '<thead><tr><th>ID</th><th>Scenario</th><th>Status</th><th>Preconditions</th>'
		h << '<th>Expected</th><th>Actual</th></tr></thead><tbody>'
		rows.each { Map r ->
			h << '<tr><th scope="row"><a href="#' << escAttr(r.testId) << '">' << esc(r.testId) << '</a></th>'
			h << '<td>' << esc(r.name) << '</td>'
			h << '<td><span class="chip ' << esc(r.status) << '">' << esc(r.status) << '</span></td>'
			h << '<td>' << esc(r.preconditions) << '</td>'
			h << '<td class="mono">' << esc(r.expected ?: r.expectedRoute) << '</td>'
			h << '<td class="mono">' << esc(r.actual ?: r.actualRoute) << '</td></tr>'
		}
		h << '</tbody></table></div></section>'
		return h.toString()
	}

	private static String article(Map r) {
		StringBuilder h = new StringBuilder()
		h << '<article class="scenario" id="' << escAttr(r.testId) << '">'
		h << '<div class="check-head"><span class="badge ' << esc(r.status) << '">' << esc(r.status) << '</span>'
		h << '<h2>' << esc(r.testId) << ' — ' << esc(r.name) << '</h2></div>'
		if (r.detail) h << '<p class="sub">' << esc(r.detail) << '</p>'
		h << '<table class="difftable"><tr><th>Step</th><th>Status</th><th>aemsite</th></tr>'
		((List) (r.steps ?: [])).each { Map s ->
			h << '<tr><td><div class="mono">' << esc(s.name) << '</div>'
			if (s.detail) h << '<div class="sub">' << esc(s.detail) << '</div>'
			h << '<div class="sub"><a href="' << escAttr(s.aemUrl) << '">' << esc(s.aemUrl) << '</a></div></td>'
			h << '<td><span class="chip ' << esc(s.status ?: '') << '">' << esc(s.status ?: '') << '</span></td>'
			h << '<td class="evcell">' << img(s.aemShot, 'AEM') << '</td></tr>'
		}
		h << '</table></article>'
		return h.toString()
	}

	private static List sectionKeys(List rows) {
		Set seen = new LinkedHashSet()
		rows.each { Map r -> seen << slug(r.section ?: 'prudiscovery') }
		List ordered = SECTION_ORDER.findAll { seen.contains(it) }
		seen.each { if (!ordered.contains(it)) ordered << it }
		return ordered
	}

	private static String sectionTitle(String key) {
		return SECTION_TITLE[key] ?: key
	}

	private static String slug(String s) {
		return (s ?: 'prudiscovery').toString().trim().toLowerCase().replaceAll('[^a-z0-9]+', '-')
	}

	private static String img(Object path, String label) {
		String p = (path ?: '').toString().replace('\\', '/')
		int ev = p.indexOf('evidence/')
		String rel = ev >= 0 ? 'assets/' + p.substring(ev + 'evidence/'.length()) : ''
		if (!rel) return '<span class="no-pic">No screenshot</span>'
		return '<a href="' + escAttr(rel) + '" target="_blank" rel="noopener">' +
			'<img class="evimg" src="' + escAttr(rel) + '" alt="' + escAttr(label) + '"></a>'
	}

	private static Map readJson() {
		File f = new File(runDir(), 'results.json')
		if (!f.exists()) return [generatedAt: now(), scenarios: []]
		return (Map) new JsonSlurper().parseText(f.getText('UTF-8'))
	}

	private static void writeJson(Map data) {
		new File(runDir(), 'results.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(data)), 'UTF-8')
	}

	private static String now() { return new Date().format("yyyy-MM-dd HH:mm") }

	private static String esc(Object s) {
		return (s == null ? '' : s.toString()).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
	}

	private static String escAttr(Object s) {
		return esc(s).replace('"', '&quot;')
	}

	private static String tabsJs() {
		return '''
(function(){
  var tabs=document.querySelectorAll(".tabs a[data-tab]");
  var panels=document.querySelectorAll(".tab-panel");
  function show(key){
    tabs.forEach(function(a){a.classList.toggle("is-on",a.getAttribute("data-tab")===key);});
    panels.forEach(function(p){p.classList.toggle("is-on",p.getAttribute("data-panel")===key);});
  }
  tabs.forEach(function(a){a.addEventListener("click",function(e){e.preventDefault();show(a.getAttribute("data-tab"));history.replaceState(null,"","#section-"+a.getAttribute("data-tab"));});});
  var hash=(location.hash||"").replace("#section-","");
  if(hash) show(hash);
})();
'''
	}

	private static String css() {
		return '''
:root{--ground:#FBFBFC;--surface:#FFF;--sunk:#F4F6F9;--ink:#14181F;--muted:#5B6572;
--line:#E2E6EB;--accent:#2C4A7C;--fail:#B3261E;--fail-soft:#FBEEEC;--pass:#146C43;--pass-soft:#EBF5EF;
--serif:'Iowan Old Style',Palatino,Georgia,serif;--sans:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
--mono:ui-monospace,Menlo,Consolas,monospace}
*{box-sizing:border-box}body{margin:0;padding:36px 16px 80px;background:var(--ground);color:var(--ink);
font-family:var(--sans);font-size:15px;line-height:1.55}
main{max-width:1680px;margin:0 auto;display:flex;flex-direction:column;gap:36px}
.cover{border-bottom:3px double var(--line);padding-bottom:28px}
.eyebrow{margin:0;font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);font-weight:600}
h1{font-family:var(--serif);font-size:38px;line-height:1.15;margin:8px 0 0}
.lede{max-width:64ch;color:var(--muted)}
h2.minor{font-size:12px;letter-spacing:.11em;text-transform:uppercase;color:var(--muted)}
.facts{display:flex;flex-wrap:wrap;gap:0;border-top:1px solid var(--line);margin:16px 0 0}
.facts>div{flex:1 1 140px;padding:12px 20px 2px 0;border-right:1px solid var(--line);margin-right:20px}
.facts dt{font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}
.facts dd{margin:2px 0 0;font-family:var(--serif);font-size:24px}
.facts dd.bad{color:var(--fail)}.facts dd.good{color:var(--pass)}.facts dd.when{font-size:18px}
.tabs{display:flex;flex-wrap:wrap;gap:0;border-bottom:1px solid var(--line)}
.tabs a{display:inline-flex;align-items:center;gap:8px;padding:10px 16px;text-decoration:none;color:var(--muted);
border-bottom:2px solid transparent;font-size:13px}
.tabs a.is-on{color:var(--accent);border-bottom-color:var(--accent);font-weight:600}
.tab-n{font-family:var(--mono);font-size:11px;color:var(--muted)}
.tab-fail{font-size:11px;font-weight:600;color:var(--fail)}
.tab-panel{display:none;flex-direction:column;gap:36px}
.tab-panel.is-on{display:flex}
.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}
table.matrix,table.difftable{border-collapse:collapse;width:100%;font-size:13px}
.matrix th,.matrix td,.difftable th,.difftable td{padding:10px 14px;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}
.matrix thead th,.difftable th{background:var(--sunk);font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted)}
.mono{font-family:var(--mono);font-size:12px}
.chip,.badge{display:inline-block;font-size:11px;font-weight:600;border-radius:999px;padding:0 10px;line-height:1.7}
.badge{border-radius:5px;letter-spacing:.06em;text-transform:uppercase}
.chip.FAIL,.badge.FAIL{background:var(--fail-soft);color:var(--fail)}
.chip.PASS,.badge.PASS{background:var(--pass-soft);color:var(--pass)}
.scenario{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:20px 22px}
.check-head{display:flex;align-items:center;gap:12px}
.check-head h2{margin:0;font-family:var(--serif);font-size:22px}
.evcell{width:70%}
.evimg{display:block;width:100%;max-height:420px;object-fit:contain;object-position:top;background:var(--sunk);border:1px solid var(--line)}
.no-pic{color:var(--muted);font-size:12px}
.sub{color:var(--muted);font-size:13px}
.foot{color:var(--muted);font-size:13px;border-top:1px solid var(--line);padding-top:16px}
a{color:var(--accent)}
'''
	}
}
