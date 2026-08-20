package com.at.util

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI
import com.kms.katalon.core.model.FailureHandling
import groovy.json.JsonSlurper
import java.net.URL

public class UniversalTagComparer {

    @Keyword
    public boolean compareLiveAndAem(String liveUrl, String aemUrl) {
        boolean overallPassed = true
        KeywordUtil.logInfo("==================================================")
        KeywordUtil.logInfo("🚀 UNIVERSAL TAG & CONTENT AUDIT")
        KeywordUtil.logInfo("Live (Baseline): " + liveUrl)
        KeywordUtil.logInfo("AEM (Target)   : " + aemUrl)
        KeywordUtil.logInfo("==================================================")

        // -------------------------------------------------------------
        // DATA EXTRACTION
        // -------------------------------------------------------------
        WebUI.navigateToUrl(liveUrl)
        WebUI.waitForPageLoad(10, FailureHandling.OPTIONAL)
        WebUI.takeFullPageScreenshot()
        List<Map> liveData = extractDomElements()

        WebUI.navigateToUrl(aemUrl)
        WebUI.waitForPageLoad(10, FailureHandling.OPTIONAL)
        WebUI.delay(2)
        WebUI.takeFullPageScreenshot()
        List<Map> aemData = extractDomElements()

        // -------------------------------------------------------------
        // STEP 1: COMPARE TAG COUNTS (STRUCTURAL PERCENTAGE)
        // -------------------------------------------------------------
        KeywordUtil.logInfo("--------------------------------------------------")
        KeywordUtil.logInfo("📊 STEP 1: STRUCTURAL TAG COMPARISON")
        KeywordUtil.logInfo("--------------------------------------------------")
        
        Map liveTagCounts = countTags(liveData)
        Map aemTagCounts = countTags(aemData)

        int totalLiveTags = liveData.size()
        int totalAemTags = aemData.size()
        KeywordUtil.logInfo("ℹ️ Total Tags Found -> Live: ${totalLiveTags} | AEM: ${totalAemTags}")

        Set<String> allTags = new TreeSet<>(liveTagCounts.keySet())
        allTags.addAll(aemTagCounts.keySet())

        int totalDifferences = 0
        boolean criticalTagMismatch = false

        allTags.each { tag ->
            int liveCount = liveTagCounts.getOrDefault(tag, 0)
            int aemCount = aemTagCounts.getOrDefault(tag, 0)
            
            if (liveCount == aemCount) {
                KeywordUtil.logInfo("✅ <${tag.toUpperCase()}>: Perfect Match (${liveCount})")
            } else if (liveCount > aemCount) {
                int missing = liveCount - aemCount
                totalDifferences += missing
                KeywordUtil.logInfo("❌ <${tag.toUpperCase()}>: MISSING IN AEM (Live has ${liveCount}, AEM only has ${aemCount})")
                if (["img", "h1", "h2", "p"].contains(tag)) criticalTagMismatch = true
            } else {
                int extra = aemCount - liveCount
                totalDifferences += extra
                KeywordUtil.logInfo("⚠️ <${tag.toUpperCase()}>: EXTRA IN AEM (Live has ${liveCount}, AEM has ${aemCount})")
                if (["h1"].contains(tag)) criticalTagMismatch = true 
            }
        }

        double structuralMatchPercent = 100.0
        if (totalLiveTags > 0) {
            structuralMatchPercent = Math.max(0.0, 100.0 - ((double) totalDifferences / totalLiveTags * 100))
        }

        KeywordUtil.logInfo("📈 Structural Match Score: " + String.format("%.2f", structuralMatchPercent) + "%")

        if (structuralMatchPercent < 90.0 || criticalTagMismatch) {
            KeywordUtil.logInfo("❌ STEP 1 FAILED: Too many missing tags or critical structural mismatch.")
            overallPassed = false
        } else {
            KeywordUtil.logInfo("✅ STEP 1 PASSED: Structure is sufficiently matched.")
        }

        // -------------------------------------------------------------
        // STEP 2: COMPARE CONTENT INSIDE THE TAGS (TEXT & IMAGES)
        // -------------------------------------------------------------
        KeywordUtil.logInfo("--------------------------------------------------")
        KeywordUtil.logInfo("📄 STEP 2: CONTENT COMPARISON")
        KeywordUtil.logInfo("--------------------------------------------------")

        if (liveData.isEmpty()) {
            KeywordUtil.logInfo("❌ STEP 2 SKIPPED: Live page has no readable content (404 Error?)")
            return false
        }

        // --- 2A: TEXT COMPARISON ---
        String liveTextContent = liveData.findAll { it.tag != 'img' }.collect { it.content }.join(" ").replaceAll("\\s+", " ").trim()
        String aemTextContent = aemData.findAll { it.tag != 'img' }.collect { it.content }.join(" ").replaceAll("\\s+", " ").trim()

        double textSimilarity = calculateSimilarity(liveTextContent, aemTextContent)
        KeywordUtil.logInfo("ℹ️ Text Content Similarity Score: " + String.format("%.2f", textSimilarity * 100) + "%")

        if (textSimilarity >= 0.85) {
            KeywordUtil.logInfo("✅ TEXT MATCH: Content survived the migration.")
        } else {
            KeywordUtil.logInfo("❌ TEXT MISMATCH: Content similarity is too low!")
            overallPassed = false
        }

        // --- 2B: IMAGE COMPARISON ---
        List<String> liveImages = liveData.findAll { it.tag == 'img' }.collect { formatAbsoluteUrl(it.content, liveUrl) }
        List<String> aemImages = aemData.findAll { it.tag == 'img' }.collect { formatAbsoluteUrl(it.content, aemUrl) }

        if (liveImages.isEmpty() && aemImages.isEmpty()) {
            KeywordUtil.logInfo("✅ IMAGE MATCH: No images found on either page.")
        } else {
            FileImageComparer imageComparer = new FileImageComparer()
            int minImages = Math.min(liveImages.size(), aemImages.size())
            
            for (int i = 0; i < minImages; i++) {
                KeywordUtil.logInfo("🔎 Checking Image Pair ${i + 1} of ${minImages}...")
                
                // 🔥 NEW: Check if the Javascript flagged a broken srcset
                if (liveImages[i].contains("FAIL_SRCSET") || aemImages[i].contains("FAIL_SRCSET")) {
                    KeywordUtil.logInfo("❌ IMAGE FAIL: Image ${i + 1} uses a broken dynamic SRC or SRCSET that cannot be downloaded.")
                    overallPassed = false
                    continue
                }

                boolean imgMatch = imageComparer.downloadAndCompare(liveImages[i], aemImages[i])
                if (!imgMatch) {
                    KeywordUtil.logInfo("⚠️ IMAGE WARNING: Image ${i + 1} differs visually.")
                }
            }
        }

        return overallPassed
    }

    // --- JAVASCRIPT DOM EXTRACTOR ---
    private List<Map> extractDomElements() {
        String jsScript = """
            var container = document.querySelector('main') || document.querySelector('[role="main"]') || document.body;
            var selectors = 'h1, h2, h3, h4, h5, h6, p, li, a, img';
            var elements = container.querySelectorAll(selectors);
            
            var result = [];
            
            elements.forEach(function(el) {
                var tagName = el.tagName.toLowerCase();
                
                if (tagName === 'img') {
                    var src = el.getAttribute('src');
                    var srcset = el.getAttribute('srcset');
                    var validUrl = "";
                    
                    // 🔥 NEW: Smart SRC vs SRCSET handling
                    if (src && !src.includes('%7B') && !src.includes('{')) {
                        // Regular valid src
                        validUrl = src;
                    } else if (srcset) {
                        // Extract the very first valid URL from the srcset string
                        validUrl = srcset.split(',')[0].trim().split(' ')[0];
                    } else {
                        // No valid src and no srcset found
                        validUrl = "FAIL_SRCSET";
                    }

                    if (validUrl && el.offsetWidth > 30) { 
                        result.push({ tag: 'img', content: validUrl.trim() });
                    }
                } 
                else if (tagName === 'a') {
                    var text = el.innerText.trim();
                    var href = el.getAttribute('href');
                    if (text.length > 0 || href) {
                        result.push({ tag: 'a', content: text, url: href ? href.trim() : '' });
                    }
                } 
                else {
                    var text = el.innerText.trim();
                    if (text.length > 0) {
                        result.push({ tag: tagName, content: text });
                    }
                }
            });
            
            return JSON.stringify(result);
        """
        try {
            String jsonStr = WebUI.executeJavaScript(jsScript, null)
            return new JsonSlurper().parseText(jsonStr) as List<Map>
        } catch (Exception e) {
            KeywordUtil.logInfo("⚠️ Error extracting elements: " + e.getMessage())
            return []
        }
    }

    // --- HELPER: COUNT TAG OCCURRENCES ---
    private Map<String, Integer> countTags(List<Map> elements) {
        Map<String, Integer> counts = [:]
        elements.each { item ->
            String tag = item.tag
            counts[tag] = counts.getOrDefault(tag, 0) + 1
        }
        return counts
    }

    // --- HELPER: FORMAT ABSOLUTE URLS FOR DOWNLOAD ---
    private String formatAbsoluteUrl(String src, String baseUrl) {
        if (src == null || src.contains("FAIL_SRCSET")) return "FAIL_SRCSET"
        if (src.startsWith("http")) return src
        if (src.startsWith("//")) return "https:" + src
        
        try {
            URL url = new URL(baseUrl)
            String domain = url.getProtocol() + "://" + url.getHost()
            return src.startsWith("/") ? domain + src : domain + "/" + src
        } catch (Exception e) {
            return src
        }
    }

    // --- HELPER: LEVENSHTEIN TEXT SIMILARITY ---
    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0
        int longerLength = Math.max(s1.length(), s2.length())
        if (longerLength == 0) return 1.0
        
        int[] costs = new int[s2.length() + 1]
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) costs[j] = j
                else if (j > 0) {
                    int newValue = costs[j - 1]
                    if (s1.charAt(i - 1) != s2.charAt(j - 1))
                        newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1
                    costs[j - 1] = lastValue
                    lastValue = newValue
                }
            }
            if (i > 0) costs[s2.length()] = lastValue
        }
        return (longerLength - costs[s2.length()]) / (double) longerLength
    }
}