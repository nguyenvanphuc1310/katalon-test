package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * Custom form preflight FORM-PF-001 to 003. Inspect only. Never submit.
 */
public class CustomFormPreflightCases {

	static final String SECTION = 'formpreflight'

	static final Map META = [
		'FORM-PF-001': [
			section : SECTION,
			name    : 'PRUDiscovery checklist-derived pre-submit contract',
			pre     : 'Rendered Discovery report with configured lead settings; do not submit',
			expected: 'Same-origin *.leadSubmit.json; URL-encoded POST; CSRF-Token; source discovery-report; recaptcha key if enabled; trusted config masked/boolean; no lead sent',
		],
		'FORM-PF-002': [
			section : SECTION,
			name    : 'PRUAdvisers checklist-derived pre-submit contract',
			pre     : 'Rendered PRUAdviser Get In Touch with configured content fragment; do not submit',
			expected: 'Same-origin /content/api/pacsleadsubmit.pruadviser.json; payload JSON default or form if configured; CSRF; source pruadviser; recaptcha key if required; trusted config masked/boolean; no lead sent',
		],
		'FORM-PF-003': [
			section : SECTION,
			name    : 'Opus checklist-derived pre-submit contract',
			pre     : 'Rendered OPUS contact section with Get In Touch config; do not submit',
			expected: 'Same-origin /content/api/pacsleadsubmit.opus.json; payload form default or JSON if configured; CSRF-Token; source opus; recaptcha key if configured; trusted config masked/boolean; no lead sent',
		],
	]

	@Keyword static void pf001() { run('FORM-PF-001') { Map r, File ev -> assertDiscovery(r, ev) } }
	@Keyword static void pf002() { run('FORM-PF-002') { Map r, File ev -> assertAdviser(r, ev) } }
	@Keyword static void pf003() { run('FORM-PF-003') { Map r, File ev -> assertOpus(r, ev) } }

	private static void assertDiscovery(Map r, File ev) {
		CustomFormPreflightForm.startOn(CustomFormPreflightForm.DISCOVERY)
		if (!aliveOrFail(r, ev)) return
		Map cfg = CustomFormPreflightForm.inspect('discovery')
		r.actual = maskInspect(cfg)
		checkStep(r, ev, '01-endpoint') {
			if (!CustomFormPreflightForm.flag(cfg, 'endpointSameOrigin')) return 'Endpoint is not same-origin: ' + cfg.endpoint
			if (!cfg.endpoint.toString().contains('leadSubmit.json')) return 'Endpoint is not resource-path .leadSubmit.json: ' + cfg.endpoint
			return true
		}
		checkStep(r, ev, '02-urlencoded-post') {
			if (cfg.payloadMode.toString() != 'urlencoded') return 'POST is not URL-encoded: ' + cfg.payloadMode
			return true
		}
		checkStep(r, ev, '03-csrf') {
			if (!CustomFormPreflightForm.flag(cfg, 'csrfScript') && !CustomFormPreflightForm.flag(cfg, 'csrfGranite')) {
				return 'Granite/fallback CSRF is not present'
			}
			if (!CustomFormPreflightForm.flag(cfg, 'csrfHeader')) return 'CSRF-Token header is not in request setup'
			return true
		}
		checkStep(r, ev, '04-source-header') {
			if (cfg.sourceHeader.toString() != 'discovery-report') return 'Source header is not discovery-report: ' + cfg.sourceHeader
			return true
		}
		checkStep(r, ev, '05-recaptcha-key') {
			if (CustomFormPreflightForm.flag(cfg, 'recaptchaConfigured') && !CustomFormPreflightForm.flag(cfg, 'recaptchaPresent')) {
				return 'reCAPTCHA is enabled on the report but no public site key'
			}
			return true
		}
		checkStep(r, ev, '06-trusted-config-masked') {
			return trustedOk(cfg, false)
		}
		checkStep(r, ev, '07-no-submit') {
			if (CustomFormPreflightForm.flag(cfg, 'submitted')) return 'A lead/email request was sent'
			return true
		}
	}

	private static void assertAdviser(Map r, File ev) {
		CustomFormPreflightForm.startOn(CustomFormPreflightForm.ADVISER)
		if (!aliveOrFail(r, ev)) return
		Map cfg = CustomFormPreflightForm.inspect('pruadviser')
		r.actual = maskInspect(cfg)
		checkStep(r, ev, '01-endpoint') {
			if (!CustomFormPreflightForm.flag(cfg, 'endpointSameOrigin')) return 'Endpoint is not same-origin: ' + cfg.endpoint
			if (!cfg.endpoint.toString().contains('pacsleadsubmit.pruadviser.json')) {
				return 'Endpoint is not /content/api/pacsleadsubmit.pruadviser.json: ' + cfg.endpoint
			}
			return true
		}
		checkStep(r, ev, '02-payload-mode') {
			String mode = cfg.payloadMode.toString().toLowerCase()
			if (!(mode in ['json', 'form'])) return 'Payload mode is not json or form: ' + cfg.payloadMode
			return true
		}
		checkStep(r, ev, '03-csrf') {
			if (!CustomFormPreflightForm.flag(cfg, 'csrfScript') && !CustomFormPreflightForm.flag(cfg, 'csrfHeader')) {
				return 'CSRF mechanism is not present for the same-origin request'
			}
			return true
		}
		checkStep(r, ev, '04-source-header') {
			if (cfg.sourceHeader.toString() != 'pruadviser') return 'Source header is not pruadviser: ' + cfg.sourceHeader
			return true
		}
		checkStep(r, ev, '05-recaptcha-key') {
			if (CustomFormPreflightForm.flag(cfg, 'recaptchaConfigured') && !CustomFormPreflightForm.flag(cfg, 'recaptchaPresent')) {
				return 'reCAPTCHA is required but public site key/config is missing'
			}
			return true
		}
		checkStep(r, ev, '06-trusted-config-masked') {
			return trustedOk(cfg, true)
		}
		checkStep(r, ev, '07-no-submit') {
			if (CustomFormPreflightForm.flag(cfg, 'submitted')) return 'A lead/email request was sent'
			return true
		}
	}

	private static void assertOpus(Map r, File ev) {
		CustomFormPreflightForm.startOn(CustomFormPreflightForm.OPUS)
		if (!aliveOrFail(r, ev)) return
		Map cfg = CustomFormPreflightForm.inspect('opus')
		r.actual = maskInspect(cfg)
		checkStep(r, ev, '01-endpoint') {
			if (!CustomFormPreflightForm.flag(cfg, 'endpointSameOrigin')) return 'Endpoint is not same-origin: ' + cfg.endpoint
			if (!cfg.endpoint.toString().contains('pacsleadsubmit.opus.json')) {
				return 'Endpoint is not /content/api/pacsleadsubmit.opus.json: ' + cfg.endpoint
			}
			return true
		}
		checkStep(r, ev, '02-payload-mode') {
			String mode = cfg.payloadMode.toString().toLowerCase()
			if (!(mode in ['form', 'json'])) return 'Payload mode is not form or json: ' + cfg.payloadMode
			return true
		}
		checkStep(r, ev, '03-csrf') {
			if (!CustomFormPreflightForm.flag(cfg, 'csrfScript') && !CustomFormPreflightForm.flag(cfg, 'csrfHeader')) {
				return 'CSRF-Token is not obtained through Granite/fallback'
			}
			return true
		}
		checkStep(r, ev, '04-source-header') {
			if (cfg.sourceHeader.toString() != 'opus') return 'Source header is not opus: ' + cfg.sourceHeader
			return true
		}
		checkStep(r, ev, '05-recaptcha-key') {
			if (CustomFormPreflightForm.flag(cfg, 'recaptchaConfigured') && !CustomFormPreflightForm.flag(cfg, 'recaptchaPresent')) {
				return 'reCAPTCHA is configured but public site key is missing'
			}
			return true
		}
		checkStep(r, ev, '06-trusted-config-masked') {
			return trustedOk(cfg, true)
		}
		checkStep(r, ev, '07-no-submit') {
			if (CustomFormPreflightForm.flag(cfg, 'submitted')) return 'A lead/email request was sent'
			return true
		}
	}

	private static String maskInspect(Map cfg) {
		return (cfg ?: [:]).toString().replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, '[email]')
	}

	private static Object trustedOk(Map cfg, boolean requireLeadHttps) {
		if (requireLeadHttps && CustomFormPreflightForm.flag(cfg, 'trustedLeadPresent') && !CustomFormPreflightForm.flag(cfg, 'trustedLeadHttps')) {
			return 'Lead endpoint is not trusted HTTPS Prudential'
		}
		String blob = cfg.toString()
		if (blob =~ /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/) {
			return 'Email/template/recipient leaked in the inspect payload'
		}
		return true
	}

	private static boolean aliveOrFail(Map r, File ev) {
		boolean ok = CustomFormPreflightForm.pageAlive() && CustomFormPreflightForm.hasForm()
		checkStep(r, ev, '00-page-load') {
			return ok ? true : 'Custom form page did not load'
		}
		return ok
	}

	private static void run(String id, Closure body) {
		Map meta = META[id]
		Map result = [
			testId       : id,
			name         : meta.name,
			section      : meta.section,
			preconditions: meta.pre,
			expected     : meta.expected,
			actual       : '',
			status       : 'FAIL',
			detail       : 'Case exited without proving the expected result',
			steps        : [],
		]
		File ev = new File(PacsRegressionReportBuilder.evidenceDir(meta.section.toString()), id)
		ev.mkdirs()
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		try {
			body.call(result, ev)
		} catch (Throwable e) {
			checkStep(result, ev, '99-uncaught') { return (e.message ?: e.toString()) }
		} finally {
			CustomFormPreflightForm.pause(0.6)
			try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		}
		finishCase(result)
		PacsRegressionReportBuilder.record(result)
		if (result.status == 'FAIL') {
			KeywordUtil.markFailed(id + ' FAIL: ' + (result.detail ?: result.expected))
		} else {
			KeywordUtil.logInfo(id + ' PASS: ' + (result.detail ?: result.expected))
		}
	}

	private static boolean checkStep(Map r, File ev, String name, Closure verify) {
		KeywordUtil.logInfo('STEP ' + name)
		boolean ok = false
		String detail = name
		try {
			Object out = verify.call()
			if (out == null || out == Boolean.TRUE) {
				ok = true
				detail = name + ' passed'
			} else if (out == Boolean.FALSE) {
				ok = false
				detail = name + ' failed'
			} else {
				String s = out.toString().trim()
				if (!s || s.equalsIgnoreCase('true')) {
					ok = true
					detail = name + ' passed'
				} else {
					ok = false
					detail = s
				}
			}
		} catch (Throwable e) {
			ok = false
			detail = e.message ?: e.toString()
		}
		((List) r.steps) << CustomFormPreflightEvidence.captureAem(ev, name) + [
			name  : name,
			status: ok ? 'PASS' : 'FAIL',
			detail: detail,
		]
		if (ok) {
			KeywordUtil.logInfo(name + ' PASS')
		} else {
			r.status = 'FAIL'
			String line = name + ': ' + detail
			String prev = (r.detail ?: '').toString()
			r.detail = (!prev || prev.startsWith('Case exited')) ? line : (prev + ' | ' + line)
			if (!r.actual) r.actual = detail
			KeywordUtil.logInfo(name + ' FAIL: ' + detail)
		}
		return ok
	}

	private static void finishCase(Map r) {
		List steps = (List) (r.steps ?: [])
		List fails = steps.findAll { ((Map) it).status == 'FAIL' }
		if (fails) {
			r.status = 'FAIL'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = fails.collect { Map s -> (s.name ?: '') + ': ' + (s.detail ?: '') }.join(' | ')
			}
			return
		}
		if (steps && steps.every { ((Map) it).status == 'PASS' }) {
			r.status = 'PASS'
			if (!r.detail || r.detail.toString().startsWith('Case exited')) {
				r.detail = steps.collect { Map s -> s.detail ?: s.name }.join('; ')
			}
		}
	}
}
