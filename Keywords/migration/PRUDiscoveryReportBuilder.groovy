package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Custom PRUDiscovery HTML report: one row per Excel scenario (DS-002..DS-015).
 * AEM UAT only — one evidence column. Live Sitecore is not part of this run.
 */
public class PRUDiscoveryReportBuilder {

	static final String RUN_FOLDER = 'Reports/PRUDiscovery/latest'
	static final String OUT_FOLDER = 'Reports/prudiscovery-report'

	@Keyword
	static File runDir() {
		File d = new File(RunConfiguration.getProjectDir() + '/' + RUN_FOLDER)
		d.mkdirs()
		return d
	}

	@Keyword
	static void startRun() {
		File d = runDir()
		d.listFiles()?.each { File f ->
			if (f.directory) f.deleteDir() else f.delete()
		}
		d.mkdirs()
		writeJson([generatedAt: now(), scenarios: []])
		KeywordUtil.logInfo('PRUDiscovery run folder reset: ' + d.absolutePath)
	}

	@Keyword
	static void record(Map scenario) {
		Map data = readJson()
		List rows = (data.scenarios instanceof List) ? new ArrayList((List) data.scenarios) : []
		rows.removeAll { ((Map) it).testId == scenario.testId }
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
		KeywordUtil.logInfo('PRUDiscovery report -> ' + html.absolutePath)
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
		int pass = rows.count { ((Map) it).status == 'PASS' } as int
		int fail = rows.count { ((Map) it).status == 'FAIL' } as int
		StringBuilder h = new StringBuilder()
		h << '<!doctype html><html lang="en"><head><meta charset="utf-8">'
		h << '<meta name="viewport" content="width=device-width, initial-scale=1">'
		h << '<title>PRUDiscovery — AEM UAT</title>'
		h << '<style>' << css() << '</style></head><body><main>'
		h << '<header class="cover"><p class="eyebrow">PACS AEM regression</p>'
		h << '<h1>PRUDiscovery journey</h1>'
		h << '<p class="lede">AEM UAT only. Each row is DS-001–DS-015 from the 2026-08-15 pack. '
		h << 'Inputs follow the Preconditions column; evidence is one AEM full-page shot per step.</p>'
		h << '<dl class="facts"><div><dt>Scenarios</dt><dd>' << rows.size() << '</dd></div>'
		h << '<div><dt>Passed</dt><dd class="good">' << pass << '</dd></div>'
		h << '<div><dt>Failed</dt><dd class="' << (fail ? 'bad' : '') << '">' << fail << '</dd></div>'
		h << '<div><dt>Generated</dt><dd class="when">' << esc(data.generatedAt) << '</dd></div></dl></header>'

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

		rows.each { Map r ->
			h << '<article class="scenario" id="' << escAttr(r.testId) << '">'
			h << '<div class="check-head"><span class="badge ' << esc(r.status) << '">' << esc(r.status) << '</span>'
			h << '<h2>' << esc(r.testId) << ' — ' << esc(r.name) << '</h2></div>'
			if (r.detail) h << '<p class="sub">' << esc(r.detail) << '</p>'
			h << '<table class="difftable"><tr><th>Step</th><th>Status</th><th>aemsite</th></tr>'
			((List) (r.steps ?: [])).each { Map s ->
				h << '<tr><td><div class="mono">' << esc(s.name) << '</div>'
				h << '<div class="sub"><a href="' << escAttr(s.aemUrl) << '">' << esc(s.aemUrl) << '</a></div></td>'
				h << '<td><span class="chip ' << esc(s.status ?: '') << '">' << esc(s.status ?: '') << '</span></td>'
				h << '<td class="evcell">' << img(s.aemShot, 'AEM') << '</td></tr>'
			}
			h << '</table></article>'
		}

		h << '<footer class="foot"><p class="sub">AEM host aem-uat.prudential.com.sg · generated '
		h << esc(data.generatedAt) << '</p></footer></main></body></html>'
		return h.toString()
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
