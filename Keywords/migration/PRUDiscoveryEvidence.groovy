package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot

/**
 * AEM-only evidence. These cases do not open the live Sitecore site.
 */
public class PRUDiscoveryEvidence {

	@Keyword
	static Map captureAem(File evidenceDir, String stem) {
		evidenceDir.mkdirs()
		File dest = new File(evidenceDir, stem + '-aem.png')
		String url = PRUDiscoveryForm.currentUrl()
		try {
			PRUDiscoveryForm.js('window.scrollTo(0, document.documentElement.scrollHeight);')
			PRUDiscoveryForm.pause(0.15)
			PRUDiscoveryForm.js('window.scrollTo(0, 0);')
			fullPagePng(dest)
			KeywordUtil.logInfo('PRUDiscovery AEM shot: ' + dest.name + ' <- ' + url)
		} catch (Exception e) {
			KeywordUtil.markWarning('AEM shot failed: ' + (e.message ?: e))
		}
		return [
			aemUrl : url,
			aemShot: rel(dest.exists() ? dest.absolutePath : ''),
		]
	}

	private static void fullPagePng(File dest) {
		dest.parentFile?.mkdirs()
		try {
			WebUI.takeFullPageScreenshot(dest.absolutePath)
			if (dest.exists() && dest.length() > 400) return
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
