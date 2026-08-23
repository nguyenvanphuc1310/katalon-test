package migration

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot

public class IlpFundsEvidence {

	static Map captureAem(File evidenceDir, String stem) {
		evidenceDir.mkdirs()
		File dest = new File(evidenceDir, stem + '-aem.png')
		String url = IlpFundsForm.currentUrl()
		try {
			IlpFundsForm.pause(0.3)
			IlpFundsForm.scrollToFunds()
			IlpFundsForm.pause(0.3)
			fullPagePng(dest)
			KeywordUtil.logInfo('ILP Funds shot: ' + dest.name + ' bytes=' + dest.length() + ' <- ' + url)
		} catch (Exception e) {
			KeywordUtil.markWarning('AEM shot failed: ' + (e.message ?: e))
		}
		return [
			aemUrl : url,
			aemShot: rel(dest.exists() && dest.length() > 400 ? dest.absolutePath : ''),
		]
	}

	private static void fullPagePng(File dest) {
		dest.parentFile?.mkdirs()
		try {
			WebUI.takeFullPageScreenshot(dest.absolutePath)
			if (dest.exists() && dest.length() > 8000) return
		} catch (Throwable ignore) { }
		try {
			File tmp = ((TakesScreenshot) DriverFactory.getWebDriver()).getScreenshotAs(OutputType.FILE)
			java.nio.file.Files.copy(tmp.toPath(), dest.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		} catch (Exception e) {
			KeywordUtil.logInfo('Viewport screenshot also failed: ' + (e.message ?: e))
		}
	}

	private static String rel(String abs) {
		if (!abs) return ''
		String root = RunConfiguration.getProjectDir().replace('\\', '/')
		return abs.replace('\\', '/').replace(root + '/', '')
	}
}
