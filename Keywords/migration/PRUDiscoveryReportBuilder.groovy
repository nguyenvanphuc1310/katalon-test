package migration

import com.kms.katalon.core.annotation.Keyword

/**
 * @deprecated Use {@link PacsRegressionReportBuilder}. Kept so existing Katalon
 * Custom Keyword calls still compile until Keywords are refreshed.
 */
public class PRUDiscoveryReportBuilder {

	@Keyword
	static File runDir() {
		return PacsRegressionReportBuilder.runDir()
	}

	@Keyword
	static void startRun() {
		PacsRegressionReportBuilder.startRun('prudiscovery')
	}

	@Keyword
	static void record(Map scenario) {
		if (!scenario.section) scenario.section = 'prudiscovery'
		PacsRegressionReportBuilder.record(scenario)
	}

	@Keyword
	static String build() {
		return PacsRegressionReportBuilder.build()
	}
}
