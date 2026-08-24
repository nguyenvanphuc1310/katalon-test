package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.model.FailureHandling
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

/**
 * Types the AEM PRUDiscovery wizard. Locators are AEM-only (UAT).
 * Screenshot values are the default fixture; Excel preconditions override them.
 */
public class PRUDiscoveryForm {

	static final String AEM = 'https://aem-uat.prudential.com.sg'
	static final String PATH = '/en/services/prudiscovery'

	static final Map STEP = [
		about           : PATH + '/about/',
		aboutyou        : PATH + '/aboutyou/',
		currentfinances : PATH + '/currentfinances/',
		youridealfuture : PATH + '/youridealfuture/',
		savings         : PATH + '/discovery_savings/',
		education       : PATH + '/discovery_education/',
		retirement      : PATH + '/discovery_retirement/',
		protection      : PATH + '/discovery_protection/',
		report          : PATH + '/report/',
	]

	/** Values from the filled AEM screenshots (name=test, male, 30, single, …). */
	static Map screenshotFixture() {
		return [
			name              : 'test',
			gender            : 'male',
			age               : '30',
			marital           : 'single',
			children          : 1,
			dependants        : 0,
			assets            : ['no'],
			insurance         : ['critical_illness', 'life_insurance', 'medical'],
			generalSavings    : '40000',
			educationSavings  : '20000',
			retirementFund    : '10000',
			retirementMonthly : '200',
			lifeCover         : '0',
			mortgageBalance   : '0',
			vehicleLoan       : '0',
			otherCommitments  : '0',
			goals             : ['children', 'retirement', 'emergency'],
			childrenSavings   : '50000',
			childrenYears     : '30',
			retirementAge     : '25',
			retirementPayoutYears : '1',
			yifRetirementMonthly  : '0',
			emergencyFund     : '500000',
			emergencyYears    : '30',
			childAge          : '0',
			childTertiaryAge  : '18',
			childYearsStudy   : '0',
			childUniversity   : 'Singapore',
			childEducationFee : '10200',
			childInflation    : '3',
			childMonthExpense : '0',
			childYearsSupport : '10',
			funeralExpense    : '6000',
		]
	}

	/** Screenshot fixture with Excel precondition overrides applied. */
	static Map fixtureFor(Map pre) {
		Map f = screenshotFixture()
		if (pre.containsKey('children')) f.children = (pre.children as int)
		if (pre.containsKey('dependants')) f.dependants = (pre.dependants as int)
		if (pre.marital) f.marital = pre.marital.toString()
		if (pre.gender) f.gender = pre.gender.toString()
		if (pre.age) f.age = pre.age.toString()
		if (pre.containsKey('hasRetirement')) {
			boolean ret = pre.hasRetirement as boolean
			List goals = new ArrayList((List) f.goals)
			if (ret && !goals.contains('retirement')) goals << 'retirement'
			if (!ret) goals.removeAll { it == 'retirement' }
			if (((f.children as int) <= 0)) goals.removeAll { it == 'children' }
			if ((f.children as int) > 0 && !goals.contains('children')) goals.add(0, 'children')
			f.goals = goals
			if (!ret) {
				f.retirementFund = '0'
				f.retirementMonthly = '0'
			}
		} else if ((f.children as int) <= 0) {
			List goals = new ArrayList((List) f.goals)
			goals.removeAll { it == 'children' }
			f.goals = goals
		}
		if ((f.children as int) <= 0) {
			f.educationSavings = '0'
		}
		if (pre.zeroMoney) {
			['generalSavings', 'educationSavings', 'retirementFund', 'retirementMonthly',
			 'lifeCover', 'mortgageBalance', 'vehicleLoan', 'otherCommitments',
			 'childrenSavings', 'emergencyFund', 'yifRetirementMonthly'].each { f[it] = '0' }
		}
		return f
	}

	/**
	 * Fresh Chrome per case. Do not call getWebDriver() first.
	 * waitForPageLoad is OPTIONAL — AEM often never reaches readyState complete.
	 */
	@Keyword
	static void startCase() {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
		WebUI.openBrowser(AEM + STEP.about)
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		waitReady('about')
	}

	static boolean hasAboutNameField() {
		return truthy(js('return !!document.getElementById("inputName");'))
	}

	/**
	 * OPTIONAL so a hung document.readyState cannot fail the case.
	 * Then poll for a real wizard field instead.
	 */
	static void waitReady(String stepKey) {
		WebUI.waitForPageLoad(8, FailureHandling.OPTIONAL)
		String sel = (stepKey == 'about') ? '#inputName' : '#nextBtn, #circleBtn, #inputName, body'
		waitUntil(8000) { truthy(js('return !!document.querySelector(arguments[0]);', sel)) }
	}

	@Keyword
	static void endSession() {
		try { WebUI.closeBrowser() } catch (Throwable ignore) { }
	}

	@Keyword
	static void ensureBrowser() {
		startCase()
	}

	@Keyword
	static void open(String stepKey) {
		open(stepKey, false)
	}

	@Keyword
	static void open(String stepKey, boolean forceReload) {
		if (!forceReload && onStep(stepKey)) return
		String target = AEM + STEP[stepKey]
		WebUI.navigateToUrl(target, FailureHandling.OPTIONAL)
		waitReady(stepKey)
		if (onStep(stepKey)) return
		WebUI.openBrowser(target)
		try { WebUI.maximizeWindow() } catch (Throwable ignore) { }
		waitReady(stepKey)
	}

	@Keyword
	static void clearStorage() {
		js('''
			try { localStorage.clear(); } catch (e) {}
			try { sessionStorage.clear(); } catch (e) {}
		''')
	}

	@Keyword
	static void fillAbout(Map f) {
		boolean hasName = truthy(js('return !!document.getElementById("inputName");'))
		if (!onStep('about') || !hasName) {
			open('about')
			hasName = truthy(js('return !!document.getElementById("inputName");'))
		}
		if (!hasName) {
			KeywordUtil.logInfo('Name field not on this page — already past About')
			return
		}
		setValue('#inputName', f.name ?: 'test')
		click('#circleBtn')
		waitPath('aboutyou')
	}

	@Keyword
	static void fillAboutYou(Map f) {
		if (!pathContains('aboutyou')) open('aboutyou')
		selectPruToggle(f.gender ?: 'male')
		setValue('#ageInput', moneyOr(f.age, '30'))
		waitSectionVisible('marital')
		selectIconCard('marital', f.marital ?: 'single')
		waitSectionVisible('assets')
		setStepper('children', (f.children ?: 0) as int)
		setStepper('dependants', (f.dependants ?: 0) as int)
		((List) (f.assets ?: ['no'])).each { selectIconCard('assets', it.toString()) }
		waitSectionVisible('insurance')
		((List) (f.insurance ?: ['no'])).each { selectIconCard('insurance', it.toString()) }
		WebUI.delay(1)
		waitNextEnabled(4)
	}

	@Keyword
	static void fillCurrentFinances(Map f) {
		if (!pathContains('currentfinances')) open('currentfinances')
		expandCollapsedSections()
		setValue('#generalSavings', moneyOrZero(f.generalSavings, '40000'))
		setValue('#educationSavings', moneyOrZero(f.educationSavings, (f.children as int) > 0 ? '20000' : '0'))
		click('.cf-section--retirement .cf-section__placeholder, #sectionRetirement .cf-section__name')
		setValue('#retirementFund', moneyOrZero(f.retirementFund, '0'))
		setValue('#retirementMonthly', moneyOrZero(f.retirementMonthly, '0'))
		click('.cf-section--protection .cf-section__placeholder, #sectionProtection .cf-section__name')
		setValue('#lifeCover', moneyOrZero(f.lifeCover, '0'))
		click('.cf-section--commitments .cf-section__placeholder, #sectionCommitments .cf-section__name')
		setValue('#mortgageBalance', moneyOrZero(f.mortgageBalance, '0'))
		setValue('#vehicleLoan', moneyOrZero(f.vehicleLoan, '0'))
		setValue('#otherCommitments', moneyOrZero(f.otherCommitments, '0'))
		waitNextEnabled(2)
	}

	/**
	 * Select / unselect YIF goal tiles, then type the screenshot amounts
	 * into any selected goal that has fields.
	 */
	@Keyword
	static void fillIdealFuture(Map f) {
		if (!pathContains('youridealfuture')) open('youridealfuture')
		List wanted = (List) (f.goals ?: [])
		['wedding', 'children', 'house', 'vehicle', 'business', 'retirement', 'emergency', 'other'].each { String g ->
			boolean on = wanted.contains(g)
			setGoal(g, on)
		}
		if (wanted.contains('children')) {
			setValue('#childrenSavings', f.childrenSavings ?: '50000')
			selectValue('#childrenYears', f.childrenYears ?: '30')
		}
		if (wanted.contains('retirement')) {
			setValue('#retirementAge', moneyOr(f.retirementAge, '25'))
			setValue('#retirementPayoutYears', moneyOr(f.retirementPayoutYears, '1'))
			setValue('#yif-retirementMonthly', moneyOrZero(f.yifRetirementMonthly, '0'))
		}
		if (wanted.contains('emergency')) {
			setValue('#emergencyFund', f.emergencyFund ?: '500000')
			selectValue('#emergencyYears', f.emergencyYears ?: '30')
		}
		pause(0.2)
	}

	/** Fill the Discovery step we are on so Next can enable. */
	@Keyword
	static void fillCurrentDiscoveryStep(Map f) {
		String token = currentToken()
		if (token == 'savings') fillSavingsStep(f)
		else if (token == 'education') fillEducationStep(f)
		else if (token == 'retirement') fillRetirementStep(f)
		else if (token == 'protection') fillProtectionStep(f)
	}

	@Keyword
	static void fillSavingsStep(Map f) {
		setValue('#current_finance_gen_sav', f.generalSavings ?: '40000')
		if ((f.children as int) > 0) {
			setValue('#saving_children_goal', f.childrenSavings ?: '50000')
			selectValue('#children_goal_years', f.childrenYears ?: '30')
		}
		setValue('#emergency_down_payment', f.emergencyFund ?: '500000')
		selectValue('#emergency_goal_years', f.emergencyYears ?: '30')
		waitNextEnabled(2)
	}

	@Keyword
	static void fillEducationStep(Map f) {
		if (!onStep('education')) openDiscoveryTab('education')
		int kids = (f.children as int)
		if (kids < 1) kids = 1
		setValue('#current_finance_gen_sav_children', moneyOrZero(f.educationSavings, '20000'))
		for (int i = 1; i <= kids; i++) {
			fillEducationChild(i, f)
		}
		waitNextEnabled(2)
	}

	/** Child block is display:none until shown. Years of study defaults to empty and blocks Next. */
	static void fillEducationChild(int i, Map f) {
		js('''
			var id = "child" + arguments[0];
			var box = document.getElementById(id);
			if (box) box.style.display = "block";
			var head = document.getElementById("mobile_child_title_" + arguments[0]);
			if (head) head.style.display = "block";
		''', i)
		String p = '#discovery_child' + i + '_'
		selectByLabel(p + 'age', f.childAge ?: '0')
		selectValue(p + 'tertiary_age', f.childTertiaryAge ?: '18')
		selectValue(p + 'year_study', moneyOr(f.childYearsStudy, '0'))
		selectValue(p + 'university', f.childUniversity ?: 'Singapore')
		setValue('#discovery_child' + i + '_educationFee', moneyOrZero(f.childEducationFee, '10200'))
		selectValue(p + 'annual_inflation', f.childInflation ?: '3')
	}

	@Keyword
	static void fillRetirementStep(Map f) {
		if (!onStep('retirement')) openDiscoveryTab('retirement')
		setValue('#ret_current_fund', moneyOrZero(f.retirementFund, '10000'))
		setValue('#ret_monthly_contribution', moneyOrZero(f.retirementMonthly, '0'))
		setValue('#ret_age', moneyOr(f.retirementAge, '25'))
		setValue('#ret_monthly_expense', moneyOrZero(f.yifRetirementMonthly, '0'))
		setValue('#ret_payout_years', moneyOr(f.retirementPayoutYears, '1'))
		waitNextEnabled(2)
	}

	@Keyword
	static void fillProtectionStep(Map f) {
		setValue('#current_finance_life_cover', moneyOrZero(f.lifeCover, '0'))
		setValue('#discovery_funeral_expense', moneyOrZero(f.funeralExpense, '6000'))
		setValue('#current_finance_mortgage_balance', moneyOrZero(f.mortgageBalance, '0'))
		setValue('#current_finance_vehical_loan_balance', moneyOrZero(f.vehicleLoan, '0'))
		setValue('#current_finance_other_outstandings', moneyOrZero(f.otherCommitments, '0'))
		if ((f.marital ?: '').toString() == 'married') {
			setValue('#discovery_spouse_month_expense', moneyOrZero(f.spouseMonthExpense, '0'))
			setValue('#discovery_spouse_year_support', moneyOr(f.spouseYearsSupport, '0'))
		}
		if ((f.children as int) > 0) {
			setValue('#discovery_child1_month_expense', moneyOrZero(f.childMonthExpense, '0'))
			setValue('#discovery_child1_year_support', moneyOr(f.childYearsSupport, '10'))
		}
		waitNextEnabled(2)
	}

	/** Turn a YIF tile on or off. Uncheck uses the tile click or Delete goal. */
	@Keyword
	static void setGoal(String goal, boolean on) {
		boolean selected = truthy(js('''
			var el = document.querySelector('.yif-card[data-goal="' + arguments[0] + '"]');
			if (!el) return false;
			return el.classList.contains('is-selected') || el.classList.contains('selected')
				|| el.classList.contains('yif-card--selected') || el.getAttribute('aria-pressed') === 'true';
		''', goal))
		if (on == selected) return
		if (on) {
			click('.yif-card[data-goal="' + goal + '"]')
			pause(0.25)
			return
		}
		Object deleted = js('''
			var btn = document.querySelector('.yif-delete-btn[data-goal="' + arguments[0] + '"]');
			if (btn) { btn.click(); return true; }
			return false;
		''', goal)
		if (!truthy(deleted)) click('.yif-card[data-goal="' + goal + '"]')
		pause(0.25)
	}

	@Keyword
	static boolean clickNext() {
		Object clicked = js('''
			var btn = document.getElementById('nextBtn') || document.getElementById('circleBtn');
			if (!btn) return false;
			if (btn.disabled || (btn.getAttribute('aria-disabled') || '').toLowerCase() === 'true') return false;
			btn.click();
			return true;
		''')
		if (truthy(clicked)) {
			String before = currentToken()
			waitUntil(1800) { currentToken() != before }
			return true
		}
		return false
	}

	@Keyword
	static boolean isNextEnabled() {
		return truthy(js('''
			var btn = document.getElementById('nextBtn') || document.getElementById('circleBtn');
			if (!btn) return false;
			var aria = (btn.getAttribute('aria-disabled') || '').toLowerCase();
			return !btn.disabled && aria !== 'true' && !btn.classList.contains('disabled');
		'''))
	}

	@Keyword
	static boolean hasNextButton() {
		return truthy(js('''
			return !!(document.getElementById('nextBtn') || document.getElementById('circleBtn'));
		'''))
	}

	@Keyword
	static boolean waitNextEnabled(int seconds) {
		return waitUntil(Math.max(1, seconds) * 1000) { isNextEnabled() }
	}

	/**
	 * Always click the Discovery tab (Savings / Education / Retirement / Protection)
	 * and wait until that page is the active one before filling it.
	 */
	@Keyword
	static boolean openDiscoveryTab(String tab) {
		if (onStep(tab) || tabIsActive(tab)) return true
		clickTab(tab)
		if (waitUntil(2000) { onStep(tab) || tabIsActive(tab) }) return true
		open(tab)
		return waitUntil(4000) { onStep(tab) }
	}

	@Keyword
	static boolean isGoalDisabled(String goal) {
		return truthy(js('''
			var el = document.querySelector('.yif-card[data-goal="' + arguments[0] + '"]');
			if (!el) return false;
			return el.classList.contains('is-disabled') || el.classList.contains('disabled')
				|| el.getAttribute('aria-disabled') === 'true' || el.classList.contains('yif-card--disabled');
		''', goal))
	}

	@Keyword
	static String currentUrl() {
		try { return WebUI.getUrl() ?: '' } catch (Throwable e) { return '' }
	}

	@Keyword
	static String currentToken() {
		String u = currentUrl().toLowerCase()
		if (u.contains('discovery_education')) return 'education'
		if (u.contains('discovery_retirement')) return 'retirement'
		if (u.contains('discovery_protection')) return 'protection'
		if (u.contains('discovery_savings')) return 'savings'
		if (u.contains('/report')) {
			int h = u.indexOf('#')
			return h >= 0 ? 'report' + u.substring(h) : 'report'
		}
		if (u.contains('youridealfuture')) return 'youridealfuture'
		if (u.contains('currentfinances')) return 'currentfinances'
		if (u.contains('aboutyou')) return 'aboutyou'
		if (u.contains('/about')) return 'about'
		return u
	}

	static boolean pathContains(String token) {
		return currentUrl().toLowerCase().contains(STEP[token].toLowerCase().replaceAll('/+$', ''))
	}

	static boolean waitPath(String token) {
		return waitUntil(6000) { pathContains(token) }
	}

	static boolean onStep(String stepKey) {
		String t = currentToken()
		if (stepKey == 'report') return t.startsWith('report')
		return t == stepKey
	}

	static void pause(Number seconds) {
		long ms = Math.max(0L, (long) (seconds.doubleValue() * 1000))
		if (ms > 0) Thread.sleep(ms)
	}

	static boolean waitUntil(int timeoutMs, Closure cond) {
		long end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			try {
				if (cond.call()) return true
			} catch (Throwable ignore) { }
			Thread.sleep(150)
		}
		try {
			return cond.call()
		} catch (Throwable ignore) {
			return false
		}
	}

	static void setStepper(String field, int target) {
		for (int i = 0; i < 12; i++) {
			int now = 0
			try {
				now = (js('''
					var el = document.querySelector('.pru-stepper[data-field="' + arguments[0] + '"] .pru-stepper__val');
					return el ? parseInt((el.textContent || "0").replace(/\\D/g, ""), 10) || 0 : 0;
				''', field) as String).toInteger()
			} catch (Exception ignore) { now = 0 }
			if (now == target) return
			click('.pru-stepper[data-field="' + field + '"] [data-action="' + (now < target ? 'inc' : 'dec') + '"]')
			Thread.sleep(300)
		}
	}

	static void setValue(String selector, Object value) {
		if (value == null) return
		js('''
			var el = document.querySelector(arguments[0]);
			if (!el) return;
			el.scrollIntoView({ block: "center", inline: "nearest" });
			el.removeAttribute("readonly");
			el.disabled = false;
			el.focus();
			el.value = "";
			el.value = String(arguments[1]);
			el.dispatchEvent(new Event("input", { bubbles: true }));
			try {
				el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: String(arguments[1]) }));
			} catch (e) {}
			el.dispatchEvent(new Event("keyup", { bubbles: true }));
			el.dispatchEvent(new Event("change", { bubbles: true }));
			el.dispatchEvent(new Event("blur", { bubbles: true }));
		''', selector, value.toString())
	}

	/** 0 and "0" are valid. Empty/null falls back — never skip a required money field. */
	static String moneyOrZero(Object value, String fallback) {
		if (value == null) return fallback
		String s = value.toString().trim()
		if (s.isEmpty()) return '0'
		return s
	}

	static String moneyOr(Object value, String fallback) {
		if (value == null) return fallback
		String s = value.toString().trim()
		return s.isEmpty() ? fallback : s
	}

	static void expandCollapsedSections() {
		js('''
			document.querySelectorAll(".cf-section:not(.is-visible) .cf-section__placeholder, .cf-section:not(.is-visible) .cf-section__name, .pru-section .pru-section__placeholder").forEach(function (el) {
				try { el.click(); } catch (e) {}
			});
			document.querySelectorAll(".cf-section").forEach(function (s) { s.classList.add("is-visible"); });
		''')
		pause(0.2)
	}

	static void clickTab(String tab) {
		js('''
			var name = String(arguments[0]);
			var el = document.querySelector('.ds-tab[data-tab="' + name + '"]')
				|| document.querySelector('.ds-tab-mobile[data-tab="' + name + '"]');
			if (!el) return;
			el.scrollIntoView({ block: "center" });
			el.click();
		''', tab)
	}

	static boolean tabIsActive(String tab) {
		return truthy(js('''
			var name = String(arguments[0]);
			var el = document.querySelector('.ds-tab[data-tab="' + name + '"]')
				|| document.querySelector('.ds-tab-mobile[data-tab="' + name + '"]');
			return !!(el && el.classList.contains("is-active"));
		''', tab))
	}

	static void selectValue(String selector, Object value) {
		if (value == null) return
		js('''
			var el = document.querySelector(arguments[0]);
			if (!el) return;
			el.value = String(arguments[1]);
			el.dispatchEvent(new Event("change", { bubbles: true }));
			el.dispatchEvent(new Event("input", { bubbles: true }));
		''', selector, value.toString())
	}

	/** Age has two options with value 0 ("Not born yet" and "0") — pick by visible label. */
	static void selectByLabel(String selector, Object label) {
		if (label == null) return
		js('''
			var el = document.querySelector(arguments[0]);
			if (!el) return;
			var want = String(arguments[1]).trim();
			for (var i = 0; i < el.options.length; i++) {
				if ((el.options[i].textContent || "").trim() === want) {
					el.selectedIndex = i;
					el.dispatchEvent(new Event("change", { bubbles: true }));
					el.dispatchEvent(new Event("input", { bubbles: true }));
					return;
				}
			}
			el.value = want;
			el.dispatchEvent(new Event("change", { bubbles: true }));
		''', selector, label.toString())
	}

	static void click(String selector) {
		js('''
			var el = document.querySelector(arguments[0]);
			if (!el) return;
			try { el.scrollIntoView({ block: "center", inline: "nearest" }); } catch (e) {}
			el.click();
		''', selector)
	}

	/** Gender is a toggle. A second click deselects it and hides marital/assets. */
	static boolean selectPruToggle(String value) {
		boolean ok = truthy(js('''
			var el = document.querySelector('.pru-toggle[data-value="' + arguments[0] + '"]');
			if (!el) return false;
			try { el.scrollIntoView({ block: "center", inline: "nearest" }); } catch (e) {}
			if (!el.classList.contains("is-selected")) el.click();
			return el.classList.contains("is-selected");
		''', value))
		if (!ok) KeywordUtil.logInfo('About You gender not selected: ' + value)
		return ok
	}

	/** Icon cards stay closed until the previous required field is set. Do not re-click a selected card. */
	static boolean selectIconCard(String group, String value) {
		boolean ok = truthy(js('''
			var el = document.querySelector('[data-group="' + arguments[0] + '"][data-value="' + arguments[1] + '"]');
			if (!el) return false;
			try { el.scrollIntoView({ block: "center", inline: "nearest" }); } catch (e) {}
			if (!el.classList.contains("is-selected")) {
				var hit = el.querySelector("img, .pru-icon-wrap") || el;
				hit.click();
				if (!el.classList.contains("is-selected")) el.click();
			}
			return el.classList.contains("is-selected");
		''', group, value))
		if (!ok) KeywordUtil.logInfo('About You card not selected: ' + group + '=' + value)
		return ok
	}

	static boolean waitSectionVisible(String kind) {
		return waitUntil(4000) {
			truthy(js('return !!document.querySelector(".pru-section--' + kind + '.is-visible");'))
		}
	}

	static void setStorage(String key, String value) {
		js('try { localStorage.setItem(arguments[0], arguments[1]); } catch (e) {}', key, value)
	}

	static Object js(String script, Object... args) {
		List argList = (args && args.length > 0) ? Arrays.asList(args) : null
		return WebUI.executeJavaScript(script, argList)
	}

	static boolean truthy(Object v) {
		if (v == null) return false
		if (v instanceof Boolean) return (Boolean) v
		String s = v.toString().trim().toLowerCase()
		return s in ['1', 'true', 'yes', 'y']
	}

	static String url(String stepKey) {
		return AEM + STEP[stepKey]
	}
}
