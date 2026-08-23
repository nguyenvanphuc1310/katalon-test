import com.kms.katalon.core.annotation.SetUp
import com.kms.katalon.core.annotation.TearDown
import com.kms.katalon.core.annotation.SetupTestCase
import com.kms.katalon.core.annotation.TearDownTestCase

import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import migration.PacsRegressionReportBuilder

@SetUp(skipped = false)
def setUp() {
	// Publish UAT only. Clears the Custom form preflight tab. Leaves Discovery, Custom pages, and Contact Us in place.
	PacsRegressionReportBuilder.startCustomFormPreflightRun()
}

@TearDown(skipped = false)
def tearDown() {
	try { WebUI.closeBrowser() } catch (Throwable ignore) { }
}

@SetupTestCase(skipped = true)
def setupTestCase() {
}

@TearDownTestCase(skipped = true)
def tearDownTestCase() {
}
