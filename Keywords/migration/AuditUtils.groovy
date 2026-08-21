package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


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

	/** Browser UA so CDN/WAF does not reject the Java default client. */
	static final String BROWSER_UA =
		'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

	/** True when the URL path itself ends in .pdf, ignoring query/hash. */
	@Keyword
	static boolean isPdfUrl(String url) {
		if (!url?.trim()) return false
		String path = url.trim().split('\\?')[0].split('#')[0].toLowerCase()
		return path.endsWith('.pdf')
	}

	@Keyword
	static boolean isPdfContentType(String ct) {
		String s = (ct ?: '').toLowerCase()
		return s.contains('application/pdf') || s.contains('application/x-pdf')
	}

	/**
	 * Detect a PDF that is mapped as a pretty URL (no .pdf suffix).
	 * Example: /opus-gift-redemption-guide -> 301 -> .../Opus-Gifts-Redemption-Guide.pdf
	 * Uses Content-Type, Content-Disposition, the URL after redirects, then %PDF magic.
	 * kind = pdf | html | document | unknown  (unknown = probe failed, use the browser).
	 */
	@Keyword
	static Map probeDocument(String url) {
		Map out = [kind: 'unknown', url: url, finalUrl: url, contentType: '', detectedBy: '', error: '']
		if (!url?.trim()) return out
		if (isPdfUrl(url)) {
			out.kind = 'pdf'
			out.contentType = 'application/pdf'
			out.detectedBy = 'url'
			return out
		}
		HttpURLConnection conn = null
		try {
			conn = openFollowing(url)
			int code = conn.getResponseCode()
			String ct = conn.getContentType() ?: ''
			String disp = conn.getHeaderField('Content-Disposition') ?: ''
			String finalUrl = conn.getURL() ? conn.getURL().toString() : url
			out.contentType = ct
			out.finalUrl = finalUrl
			if (code >= 400) {
				out.detectedBy = 'http ' + code
				out.error = 'HTTP ' + code
				return out
			}
			if (isPdfUrl(finalUrl)) {
				out.kind = 'pdf'
				out.detectedBy = 'redirect'
				return out
			}
			if (isPdfContentType(ct)) {
				out.kind = 'pdf'
				out.detectedBy = 'contentType'
				return out
			}
			if (disp.toLowerCase().contains('.pdf')) {
				out.kind = 'pdf'
				out.detectedBy = 'disposition'
				return out
			}
			if (ct.toLowerCase().contains('html')) {
				out.kind = 'html'
				out.detectedBy = 'contentType'
				return out
			}
			InputStream ins = conn.getInputStream()
			byte[] head = new byte[5]
			int n = ins.read(head)
			ins.close()
			if (n >= 4 && head[0] == (byte) 0x25 && head[1] == (byte) 0x50 &&
				head[2] == (byte) 0x44 && head[3] == (byte) 0x46) {
				out.kind = 'pdf'
				out.detectedBy = 'magic'
				return out
			}
			if (ct && !ct.toLowerCase().contains('html')) {
				out.kind = 'document'
				out.detectedBy = 'contentType'
			}
			return out
		} catch (Exception e) {
			out.error = e.getMessage() ?: 'probe failed'
			return out
		} finally {
			try { conn?.disconnect() } catch (Exception ignore) { }
		}
	}

	/** Download the final resource after redirects (used for PDF MD5). */
	@Keyword
	static byte[] downloadBytes(String url) {
		HttpURLConnection conn = openFollowing(url)
		try {
			InputStream ins = conn.getInputStream()
			try {
				return ins.bytes
			} finally {
				ins.close()
			}
		} finally {
			try { conn.disconnect() } catch (Exception ignore) { }
		}
	}

	private static HttpURLConnection openFollowing(String url) {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
		conn.setInstanceFollowRedirects(true)
		conn.setConnectTimeout(15000)
		conn.setReadTimeout(30000)
		conn.setRequestProperty('User-Agent', BROWSER_UA)
		conn.setRequestProperty('Accept', '*/*')
		return conn
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

	/** Live sitecore URL -> AEM URL, from the master mapping CSV */
	private static Map liveToAem = null

	/** Lazy index: normalized URL path -> [pagegroup, pagetype], built from the master mapping CSV */
	private static Map pageTypeIndex = null

	/** AEM URL for a live Sitecore URL when Katalon did not bind pageurl. */
	@Keyword
	static String aemUrlForLive(String liveUrl) {
		if (!liveUrl?.trim()) return ''
		if (liveToAem == null) {
			Map idx = [:]
			File f = new File(RunConfiguration.getProjectDir() + '/Data Files/aem-url-mapping.csv')
			if (f.exists()) {
				f.readLines('UTF-8').drop(1).each { line ->
					if (!line?.trim()) return
					def c = line.split(',', 3)
					if (c.length >= 2) idx[normalizeKey(c[0].trim())] = c[1].trim()
				}
			}
			liveToAem = idx
		}
		return liveToAem[normalizeKey(liveUrl)] ?: ''
	}

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
