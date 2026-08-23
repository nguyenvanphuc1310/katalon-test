package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import groovy.json.JsonSlurper

/**
 * Custom form preflight only. Inspect config. Never submit.
 * FORM-PF-001 Discovery report, FORM-PF-002 PRUAdviser, FORM-PF-003 Opus.
 */
public class CustomFormPreflightForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String DISCOVERY = AEM + '/en/services/prudiscovery/report/'
	static final String ADVISER = AEM + '/en/pruadviser/nicoleue/'
	static final String OPUS = AEM + '/en/priority-programme/opus/'
	static final String HELPERS_FILE = 'Include/config/custom-form-preflight-helpers.js'

	static String helpersCache = ''
	static long helpersMtime = 0

	static String helpersJs() {
		File f = new File(RunConfiguration.getProjectDir() + '/' + HELPERS_FILE)
		long m = f.lastModified()
		if (helpersCache && m == helpersMtime) return helpersCache
		helpersCache = f.getText('UTF-8')
		helpersMtime = m
		return helpersCache
	}

	static void startOn(String url) {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		WebUI.openBrowser(url)
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		WebUI.waitForPageLoad(15, FailureHandling.OPTIONAL)
		dismissCookies()
		waitUntil(12000) { pageAlive() }
		pause(0.8)
	}

	static void dismissCookies() {
		js('var el=document.getElementById("onetrust-accept-btn-handler")||document.getElementById("truste-consent-button"); if(el) el.click();')
		pause(0.3)
	}

	static String currentUrl() {
		try { return WebUI.getUrl() ?: '' } catch (Throwable e) { return '' }
	}

	static String bodyText() {
		return (js('return document.body ? document.body.innerText : "";') ?: '').toString()
	}

	static boolean pageAlive() {
		String t = bodyText().toLowerCase()
		return t && !(t.contains('404') && t.contains('not found')) && !t.contains('500 internal')
	}

	static boolean hasForm() {
		return truthy(fjs('return pfHasForm();'))
	}

	static Map inspect(String kind) {
		fjs('return pfStartInspect(arguments[0]);', kind)
		waitUntil(12000) { truthy(js('return window.__pfReady===true;')) }
		return asMap(js('return JSON.stringify(window.__pfInspect||{});'))
	}

	static void scrollToForm() {
		fjs('return pfScrollForm();')
	}

	static void pause(Number sec) {
		try { WebUI.delay(sec as float) } catch (Throwable ignore) { }
	}

	static boolean waitUntil(int timeoutMs, Closure cond) {
		long end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			try { if (cond.call()) return true } catch (Throwable ignore) { }
			pause(0.25)
		}
		try { return cond.call() } catch (Throwable e) { return false }
	}

	static boolean flag(Map m, String k) {
		if (m == null || m[k] == null) return false
		Object v = m[k]
		if (v instanceof Boolean) return (Boolean) v
		String s = v.toString().trim().toLowerCase()
		return s == 'true' || s == '1' || s == 'yes'
	}

	static boolean truthy(Object v) {
		if (v instanceof Boolean) return (Boolean) v
		if (v == null) return false
		String s = v.toString().trim().toLowerCase()
		return s == 'true' || s == '1' || s == 'yes'
	}

	static Object js(String body) {
		return WebUI.executeJavaScript(body, null)
	}

	static Object fjs(String body) {
		return WebUI.executeJavaScript(helpersJs() + '\n' + body, null)
	}

	static Object fjs(String body, Object a1) {
		return WebUI.executeJavaScript(helpersJs() + '\n' + body, [a1])
	}

	static Map fprobe(String body) {
		return asMap(fjs(body))
	}

	static Map fprobe(String body, Object a1) {
		return asMap(fjs(body, a1))
	}

	static Map asMap(Object raw) {
		if (raw instanceof Map) return (Map) raw
		String s = (raw ?: '').toString().trim()
		if (!s) return [:]
		try {
			Object p = new JsonSlurper().parseText(s)
			return p instanceof Map ? (Map) p : [:]
		} catch (Throwable e) {
			return [:]
		}
	}
}
