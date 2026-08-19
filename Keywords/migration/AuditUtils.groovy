package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration


/**
 * Pure utilities (no browser required): URL handling, CSV read/write and the
 * baseline/report path conventions every check writes through.
 *
 * This is where the on-disk contract lives: slugOf() names every file, baselinePath()
 * and reportDir() place them, recordResult() is the single way a check hands its verdict
 * to the report. Change one of these and every check plus ReportBuilder moves with it.
 */
public class AuditUtils {

	/** Slug used as baseline/report file name, derived from the page URL */
	@Keyword
	static String slugOf(String url) {
		String s = url.replaceAll('https?://[^/]+', '').replaceAll('[^A-Za-z0-9]+', '_').replaceAll('^_+|_+$', '')
		return s ? s : 'home'
	}

	/** Stable identity key: strip domain + query string (ts, qlt, wid, hash... change between publishes) */
	@Keyword
	static String normalizeKey(String url) {
		String p = url.replaceAll('^https?://[^/]+', '').split('\\?')[0]
		try { p = URLDecoder.decode(p, 'UTF-8') } catch (Exception ignore) { }
		return p
	}

	@Keyword
	static String csvq(Object s) {
		return '"' + (s == null ? '' : s.toString().replace('"', '""')) + '"'
	}

	/** Lazy index: normalized URL path -> [pagegroup, pagetype], built from the master mapping CSV */
	private static Map pageTypeIndex = null

	private static Map loadPageTypeIndex() {
		if (pageTypeIndex != null) return pageTypeIndex
		Map idx = [:]
		File f = new File(RunConfiguration.getProjectDir() + '/Data Files/aem-url-mapping.csv')
		if (f.exists()) {
			f.readLines('UTF-8').drop(1).each { line ->
				def c = line.split(',')
				if (c.length >= 5) {
					List meta = [c[3].trim(), c[4].trim()]
					idx[normalizeKey(c[0].trim())] = meta
					idx[normalizeKey(c[1].trim())] = meta
				}
			}
		}
		pageTypeIndex = idx
		return idx
	}

	/**
	 * Baseline file path for a check kind + page (creates the directory if missing).
	 * Layout: Data Files/baselines/<pagegroup>-pages/<pagetype>/<kind>/<slug><ext>
	 * The page group/type is resolved from the master mapping CSV; unmapped URLs
	 * fall back to baselines/unmapped/unknown so a missing row is visible, not fatal.
	 */
	@Keyword
	static String baselinePath(String kind, String pageurl, String ext) {
		List meta = loadPageTypeIndex()[normalizeKey(pageurl)]
		String group = meta ? meta[0] + '-pages' : 'unmapped'
		String pagetype = meta ? meta[1] : 'unknown'
		String dir = RunConfiguration.getProjectDir() + '/Data Files/baselines/' + group + '/' + pagetype + '/' + kind
		new File(dir).mkdirs()
		return dir + '/' + slugOf(pageurl) + ext
	}

	/** Evidence report directory for a check kind + page (creates the directory if missing) */
	@Keyword
	static String reportDir(String kind, String pageurl) {
		String dir = RunConfiguration.getProjectDir() + '/Reports/' + kind + '/' + slugOf(pageurl)
		new File(dir).mkdirs()
		return dir
	}

	/** Record a check verdict for the HTML parity report (first line verdict, rest detail) */
	@Keyword
	static void recordResult(String pageurl, String check, String verdict, String detail) {
		String dir = RunConfiguration.getProjectDir() + '/Reports/parity-results/' + slugOf(pageurl)
		new File(dir).mkdirs()
		new File(dir + '/' + check + '.txt').setText(verdict + '\n' + (detail ?: ''), 'UTF-8')
	}

}
