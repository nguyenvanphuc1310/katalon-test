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

	/**
	 * Join a suite-configured host to a mapping path.
	 *
	 * The per-type CSVs carry paths, not URLs, so the environment is chosen once at the suite
	 * instead of being frozen into 270 rows. Everything downstream still sees one absolute URL:
	 * profileFor() needs the host to pick a site profile, and navigateToUrl() needs a real URL.
	 *
	 * A value that is already absolute passes through untouched — the lbu-homepage and ilp-fund
	 * slices still hold full URLs, and that pass-through is what keeps them working unchanged.
	 */
	@Keyword
	static String absolute(String host, String path) {
		if (!path) return path
		String p = path.toLowerCase()
		if (p.startsWith('http://') || p.startsWith('https://')) return path
		if (!host) return path
		return host.replaceAll('/+$', '') + (path.startsWith('/') ? path : '/' + path)
	}

	@Keyword
	static String csvq(Object s) {
		return '"' + (s == null ? '' : s.toString().replace('"', '""')) + '"'
	}

	/**
	 * One entry per data row of the master mapping: [sc, aem, tpl, group, pagetype].
	 *
	 * The single reader of `Data Files/aem-url-mapping.csv`. It was parsed in two places with two
	 * copies of the same split loop before CaptureGaps needed a third; a mapping that is read three
	 * ways is a mapping that will eventually be read three different ways.
	 *
	 * The split is deliberately naive — the master carries no quoted commas, and the four URLs that
	 * contain spaces are left unencoded on purpose (see project-tracking.md, 2026-08-21). Rows
	 * shorter than 3 columns are dropped; legacy 3-column rows default to group "normal", matching
	 * ReportBuilder.collectPages().
	 */
	@Keyword
	static List mappingRows() {
		List rows = []
		File f = new File(RunConfiguration.getProjectDir() + '/Data Files/aem-url-mapping.csv')
		if (!f.exists()) return rows
		f.readLines('UTF-8').drop(1).each { String line ->
			if (!line?.trim()) return
			def c = line.split(',')
			if (c.length < 3) return
			rows << [
				sc      : c[0].trim(),
				aem     : c[1].trim(),
				tpl     : c[2].trim(),
				group   : c.length > 3 && c[3].trim() ? c[3].trim() : 'normal',
				pagetype: c.length > 4 ? c[4].trim() : '',
			]
		}
		return rows
	}

	/** Lazy index: normalized URL path -> [pagegroup, pagetype], built from the master mapping CSV */
	private static Map pageTypeIndex = null

	private static Map loadPageTypeIndex() {
		if (pageTypeIndex != null) return pageTypeIndex
		Map idx = [:]
		mappingRows().each { Object r ->
			Map row = (Map) r
			// Only a row that names its own page type can classify a URL; a legacy 3-column row
			// would otherwise file every page under the defaulted group with an empty type.
			if (!row.pagetype) return
			List meta = [row.group, row.pagetype]
			idx[normalizeKey((String) row.sc)] = meta
			idx[normalizeKey((String) row.aem)] = meta
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

	/**
	 * The verdict an earlier run recorded for this page and check, or '' when it has never run.
	 *
	 * The read side of recordResult(), and it belongs beside it: two callers now need the same
	 * question answered — ContentTextCheck, to honour a NOT_RUN rather than demand a baseline that
	 * cannot exist, and CaptureGaps, to keep a URL that serves a PDF out of the retry list forever.
	 */
	@Keyword
	static String recordedVerdict(String pageurl, String check) {
		File f = new File(RunConfiguration.getProjectDir() + '/Reports/parity-results/' +
			slugOf(pageurl) + '/' + check + '.txt')
		return f.exists() ? (f.getText('UTF-8').readLines()[0]?.trim() ?: '') : ''
	}

}
