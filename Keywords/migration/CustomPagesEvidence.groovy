package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI

import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot

/**
 * Full-page AEM shots for Custom pages. Always restore a desktop window first so
 * the PNG is not a 390px strip, and wait so the component has painted.
 */
public class CustomPagesEvidence {

	@Keyword
	static Map captureAem(File evidenceDir, String stem) {
		evidenceDir.mkdirs()
		File dest = new File(evidenceDir, stem + '-aem.png')
		String url = CustomPagesForm.currentUrl()
		try {
			CustomPagesForm.pause(0.8)
			CustomPagesForm.js('''
				var el = document.querySelector("h1, main, [class*=quiz], [class*=chat], [class*=accordion]");
				if (el) el.scrollIntoView({block:"center"});
				else window.scrollTo(0, 0);
			''')
			CustomPagesForm.pause(0.4)
			fullPagePng(dest)
			if (!dest.exists() || dest.length() < 8000) {
				CustomPagesForm.pause(1.0)
				fullPagePng(dest)
			}
			KeywordUtil.logInfo('Custom pages AEM shot: ' + dest.name + ' bytes=' + dest.length() + ' <- ' + url)
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
