package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import com.kms.katalon.core.webui.driver.DriverFactory

import groovy.json.JsonSlurper

import java.time.Duration

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver

import java.net.URL
import java.net.URLDecoder

public class UniversalTagComparer {

    private List auditLines = []

    /**
     * Compare one Live / AEM pair. Returns a result map for StructureCheck / ReportBuilder:
     * status, reasons, scores, and logLines (the same text as KeywordUtil.logInfo).
     */
    @Keyword
    public Map compareLiveAndAem(String liveUrl, String aemUrl) {
        auditLines = []
        Map result = blankResult(liveUrl, aemUrl)
        try {
            if (!liveUrl?.trim() || !aemUrl?.trim()) {
                return finalizeResult(result, false, [
                    "Missing URL — live='${liveUrl}' aem='${aemUrl}'"
                ])
            }

            log("Opening Live and AEM (no tag snapshot on disk)")
            WebActions.ensureOnPage(liveUrl, true)
            WebActions.scrollFullPage()
            List<Map> liveData = extractOnCurrentPage()

            WebActions.ensureOnPage(aemUrl, true)
            WebActions.scrollFullPage()
            List<Map> aemData = extractOnCurrentPage()

            return compareExtracted(liveData, aemData, liveUrl, aemUrl)
        } catch (Exception e) {
            log("UniversalTagComparer error: " + e.getMessage())
            return finalizeResult(result, false, ['Error comparing pages: ' + (e.getMessage() ?: 'unknown error')])
        }
    }

    /**
     * Compare tag lists already extracted (from ContentSnapshot .tags.json or a live DOM).
     * No navigation. Image files are still downloaded over HTTP.
     */
    @Keyword
    public Map compareExtracted(List liveData, List aemData, String liveUrl, String aemUrl) {
        if (auditLines == null) auditLines = []
        Map result = blankResult(liveUrl, aemUrl)
        try {
            liveData = liveData ?: []
            aemData = aemData ?: []
            result.liveTags = liveData.size()
            result.aemTags = aemData.size()

            log("==================================================")
            log("UNIVERSAL TAG & IMAGE AUDIT")
            log("Live (Baseline): " + liveUrl)
            log("AEM (Target)   : " + aemUrl)
            log("Comparing extracted tags — Live ${result.liveTags} | AEM ${result.aemTags}")
            log("Text is compared by ContentCompare, not here.")
            log("==================================================")

            log("--------------------------------------------------")
            log("STEP 1: STRUCTURAL TAG COMPARISON")
            log("--------------------------------------------------")

            Map liveTagCounts = countTags(liveData)
            Map aemTagCounts = countTags(aemData)
            result.liveTagCounts = liveTagCounts
            result.aemTagCounts = aemTagCounts
            log("Total Tags Found -> Live: ${result.liveTags} | AEM: ${result.aemTags}")

            Set<String> allTags = new TreeSet<>(liveTagCounts.keySet())
            allTags.addAll(aemTagCounts.keySet())

            int totalDifferences = 0
            boolean criticalTagMismatch = false
            List tagReasons = []

            allTags.each { tag ->
                int liveCount = liveTagCounts.getOrDefault(tag, 0)
                int aemCount = aemTagCounts.getOrDefault(tag, 0)

                if (liveCount == aemCount) {
                    log("<${tag.toUpperCase()}>: Perfect Match (${liveCount})")
                } else if (liveCount > aemCount) {
                    totalDifferences += (liveCount - aemCount)
                    tagReasons << "Missing ${tag.toUpperCase()} on AEM — Sitecore has ${liveCount}, AEM has ${aemCount}"
                    log("<${tag.toUpperCase()}>: MISSING IN AEM (Live has ${liveCount}, AEM only has ${aemCount})")
                    if (["img", "h1", "h2", "p"].contains(tag)) criticalTagMismatch = true
                } else {
                    totalDifferences += (aemCount - liveCount)
                    tagReasons << "Extra ${tag.toUpperCase()} on AEM — Sitecore has ${liveCount}, AEM has ${aemCount}"
                    log("<${tag.toUpperCase()}>: EXTRA IN AEM (Live has ${liveCount}, AEM has ${aemCount})")
                    if (["h1"].contains(tag)) criticalTagMismatch = true
                }
            }

            result.structuralPercent = 100.0
            if ((result.liveTags as int) > 0) {
                result.structuralPercent = Math.max(0.0, 100.0 - ((double) totalDifferences / (result.liveTags as int) * 100))
            }

            log("Structural Match Score: " + String.format("%.2f", result.structuralPercent) + "%")

            if ((result.structuralPercent as double) < 90.0) {
                result.passed = false
                result.reasons << ('Structural match only ' + pct(result.structuralPercent) + '%')
            }
            if (criticalTagMismatch) {
                result.passed = false
            }
            if (!result.passed) {
                result.reasons.addAll(tagReasons)
                log("STEP 1 FAILED: Too many missing tags or critical structural mismatch.")
            } else {
                log("STEP 1 PASSED: Structure is sufficiently matched.")
            }

            log("--------------------------------------------------")
            log("STEP 2: IMAGE COMPARISON")
            log("--------------------------------------------------")

            List<String> liveImages = liveData.findAll { it.tag == 'img' }.collect { formatAbsoluteUrl(it.content, liveUrl) }
            List<String> aemImages = aemData.findAll { it.tag == 'img' }.collect { formatAbsoluteUrl(it.content, aemUrl) }
            result.sitecoreImages = liveImages.size()
            result.aemImages = aemImages.size()

            if (liveImages.isEmpty() && aemImages.isEmpty()) {
                log("IMAGE MATCH: No images found on either page.")
            } else {
                FileImageComparer imageComparer = new FileImageComparer()
                Map pairing = pairImagesByName(liveImages, aemImages)
                List paired = (List) pairing.pairs
                result.imagesChecked = paired.size() + ((List) pairing.unpairedLive).size()
                List imagePairs = []

                paired.eachWithIndex { Map pair, int i ->
                    log("Checking Image Pair ${i + 1} of ${paired.size()} (name match: ${pair.liveKey} ↔ ${pair.aemKey})...")
                    if ((pair.live as String).contains('FAIL_SRCSET') || (pair.aem as String).contains('FAIL_SRCSET')) {
                        String why = "Image ${i + 1} uses a broken dynamic SRC or SRCSET that cannot be downloaded."
                        log("IMAGE FAIL: " + why)
                        result.passed = false
                        result.imagesFailed = (result.imagesFailed as int) + 1
                        imagePairs << [
                            pair    : i + 1,
                            matched : false,
                            how     : 'broken src/srcset',
                            error   : why,
                            liveUrl : pair.live,
                            aemUrl  : pair.aem,
                            liveW   : 0, liveH: 0, aemW: 0, aemH: 0,
                            hashDist: -1,
                        ]
                        return
                    }
                    Map cmp = imageComparer.compareImages(pair.live as String, pair.aem as String)
                    cmp.pair = i + 1
                    imagePairs << cmp
                    if (!(cmp.matched as boolean)) {
                        log("IMAGE FAIL: Image ${i + 1} does not look the same (" + (cmp.how ?: cmp.error ?: 'visual check') + ").")
                        result.passed = false
                        result.imagesFailed = (result.imagesFailed as int) + 1
                    }
                }
                ((List) pairing.unpairedLive).each { Object lu ->
                    int n = imagePairs.size() + 1
                    result.passed = false
                    result.imagesFailed = (result.imagesFailed as int) + 1
                    imagePairs << [
                        pair    : n,
                        matched : false,
                        how     : 'Sitecore has this picture. No AEM file name matched it.',
                        error   : 'unpaired',
                        liveUrl : lu.toString(),
                        aemUrl  : '',
                        liveW   : 0, liveH: 0, aemW: 0, aemH: 0,
                        hashDist: -1,
                    ]
                    log("IMAGE FAIL: Sitecore picture has no name match on AEM: " + lu)
                }
                result.imagePairs = imagePairs
                int matched = imagePairs.count { it.matched as boolean } as int
                int checked = imagePairs.size()
                if (checked > 0) {
                    if ((result.imagesFailed as int) > 0) {
                        result.reasons << (matched + ' of ' + checked + ' pictures look the same. ' +
                            result.imagesFailed + ' do not.')
                    } else {
                        result.reasons << (matched + ' of ' + checked + ' pictures look the same.')
                    }
                }
                if (liveImages.size() != aemImages.size()) {
                    result.reasons << ('Sitecore has ' + liveImages.size() + ' pictures, AEM has ' +
                        aemImages.size() + '. Paired by file name.')
                }
            }

            comparePdfLinks(liveData, aemData, liveUrl, aemUrl, result)

            return finalizeResult(result, result.passed as boolean, (List) result.reasons)
        } catch (Exception e) {
            log("UniversalTagComparer error: " + e.getMessage())
            return finalizeResult(result, false, ['Error comparing pages: ' + (e.getMessage() ?: 'unknown error')])
        }
    }

    /**
     * In-page links that point at PDF files. Pair by filename (Sitecore subset),
     * then MD5-compare each pair with FileImageComparer.
     */
    private void comparePdfLinks(List liveData, List aemData, String liveUrl, String aemUrl, Map result) {
        List livePdfs = []
        liveData.findAll { it.tag == 'a' }.each { Map a ->
            if (!AuditUtils.isPdfUrl(a.url as String)) return
            livePdfs << [name: pdfFileName(a.url as String),
                url: formatAbsoluteUrl(a.url as String, liveUrl),
                text: (a.content ?: '').toString()]
        }
        List aemPdfs = []
        aemData.findAll { it.tag == 'a' }.each { Map a ->
            if (!AuditUtils.isPdfUrl(a.url as String)) return
            aemPdfs << [name: pdfFileName(a.url as String),
                url: formatAbsoluteUrl(a.url as String, aemUrl),
                text: (a.content ?: '').toString()]
        }

        if (livePdfs.isEmpty() && aemPdfs.isEmpty()) return

        log("--------------------------------------------------")
        log("PDF LINKS ON THE PAGE")
        log("Live: ${livePdfs.size()} | AEM: ${aemPdfs.size()}")

        Map liveByName = [:]
        livePdfs.each { liveByName.get(it.name, []) << it }
        Map aemByName = [:]
        aemPdfs.each { aemByName.get(it.name, []) << it }

        FileImageComparer pdfComparer = new FileImageComparer()
        Set names = new TreeSet<>(liveByName.keySet())
        names.addAll(aemByName.keySet())

        names.each { String name ->
            List liveHits = (List) (liveByName[name] ?: [])
            List aemHits = (List) (aemByName[name] ?: [])
            int paired = Math.min(liveHits.size(), aemHits.size())
            for (int i = 0; i < paired; i++) {
                result.pdfsChecked = (result.pdfsChecked as int) + 1
                log("PDF ${name} — Live: ${liveHits[i].url}")
                log("PDF ${name} — AEM : ${aemHits[i].url}")
                Map pdf = pdfComparer.comparePdfFiles(liveHits[i].url as String, aemHits[i].url as String)
                (pdf.logLines as List)?.each { auditLines << (it as String) }
                if (!(pdf.matched as boolean)) {
                    result.passed = false
                    result.pdfsFailed = (result.pdfsFailed as int) + 1
                    result.reasons << ("PDF file differs: " + name)
                }
            }
            if (liveHits.size() > aemHits.size()) {
                result.passed = false
                int missing = liveHits.size() - aemHits.size()
                result.pdfsFailed = (result.pdfsFailed as int) + missing
                result.reasons << ("PDF missing on AEM: " + name + " (" + missing + ")")
                log("PDF missing on AEM: " + name)
            } else if (aemHits.size() > liveHits.size()) {
                log("Extra PDF on AEM (not a failure): " + name)
            }
        }
    }

    private String pdfFileName(String href) {
        String path = (href ?: '').split('\\?')[0].split('#')[0]
        String name = path.contains('/') ? path.substring(path.lastIndexOf('/') + 1) : path
        try { name = URLDecoder.decode(name, 'UTF-8') } catch (Exception ignore) { }
        return name.toLowerCase()
    }

    /** JS extract of semantic tags. Caller must already be on the page. */
    @Keyword
    public List extractOnCurrentPage() {
        return extractDomElements()
    }

    private Map blankResult(String liveUrl, String aemUrl) {
        return [
            liveUrl           : liveUrl,
            aemUrl            : aemUrl,
            status            : 'PASS',
            passed            : true,
            reasons           : [],
            structuralPercent : 0.0,
            liveTags          : 0,
            aemTags           : 0,
            imagesChecked     : 0,
            imagesFailed      : 0,
            sitecoreImages    : 0,
            aemImages         : 0,
            imagePairs        : [],
            liveTagCounts     : [:],
            aemTagCounts      : [:],
            pdfsChecked       : 0,
            pdfsFailed        : 0,
            error             : '',
            logLines          : [],
        ]
    }

    private void log(String msg) {
        KeywordUtil.logInfo(msg)
        auditLines << msg
    }

    private Map finalizeResult(Map result, boolean passed, List reasons) {
        result.passed = passed
        result.status = passed ? 'PASS' : 'FAIL'
        result.logLines = new ArrayList(auditLines)
        List notes = (reasons ?: []).collect { it?.toString()?.trim() }.findAll { it }.unique()
        if (passed && notes.isEmpty()) {
            if ((result.structuralPercent as double) >= 99.995) {
                notes << 'Tag counts matched perfectly.'
            } else {
                notes << ('Tag counts matched (structure ' + pct(result.structuralPercent) + '%).')
            }
        }
        result.reasons = notes
        return result
    }

    private String pct(Object n) {
        return String.format('%.0f', (n == null ? 0.0 : n as double))
    }

    // --- JAVASCRIPT DOM EXTRACTOR ---
    /**
     * Product Deck (pili/piib) has few tags. That is not why Chrome freezes.
     * The old script used innerText + getComputedStyle + offsetWidth on every
     * node. Those force layout. On a deck that never reaches document.complete
     * (Evergage/GTM), the script hangs and the 8s Katalon timeout looks like a
     * freeze. Lifestage pages have the opposite problem: innerText skips
     * display:none / aria-hidden tab panels, so Investments never appears.
     *
     * One script for both: textContent (hidden tabs + no layout), no style
     * compute, keep imgs inside tab panels even when hidden.
     */
    private List<Map> extractDomElements() {
        String jsScript = '''
            var container = document.querySelector('main') || document.querySelector('[role="main"]') || document.body;
            var nodes = container.querySelectorAll('h1, h2, h3, h4, h5, h6, p, li, a, img');
            var result = [];
            function txt(el) {
                return (el.textContent || '').replace(/\\s+/g, ' ').trim();
            }
            function imgUrl(el) {
                var src = el.getAttribute('src') || '';
                var srcset = el.getAttribute('srcset') || '';
                if (src && src.indexOf('%7B') < 0 && src.indexOf('{') < 0) return src.trim();
                if (srcset) return srcset.split(',')[0].trim().split(' ')[0];
                return 'FAIL_SRCSET';
            }
            for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                var tag = el.tagName.toLowerCase();
                if (tag === 'img') {
                    var inPanel = el.closest && el.closest('[role=tabpanel], [data-lifestage-tab], [data-content], .card-list-tabs__content');
                    if (!inPanel && el.closest && el.closest('[aria-hidden=true]')) continue;
                    var validUrl = imgUrl(el);
                    if (validUrl === 'FAIL_SRCSET') {
                        result.push({ tag: 'img', content: 'FAIL_SRCSET' });
                        continue;
                    }
                    var low = validUrl.toLowerCase();
                    var attrW = parseInt(el.getAttribute('width') || '0', 10) || 0;
                    var attrH = parseInt(el.getAttribute('height') || '0', 10) || 0;
                    var size = Math.max(attrW, attrH);
                    if (inPanel || low.indexOf('.svg') !== -1 || size === 0 || size >= 16) {
                        result.push({ tag: 'img', content: validUrl });
                    }
                    continue;
                }
                if (tag === 'a') {
                    var text = txt(el);
                    var href = el.getAttribute('href');
                    if (text || href) result.push({ tag: 'a', content: text, url: href ? href.trim() : '' });
                    continue;
                }
                var t = txt(el);
                if (t) result.push({ tag: tag, content: t });
            }
            return JSON.stringify(result);
        '''
        WebDriver driver = DriverFactory.getWebDriver()
        def timeouts = driver.manage().timeouts()
        Duration previous = null
        try {
            try { previous = timeouts.getScriptTimeout() } catch (Exception ignore) { }
            timeouts.scriptTimeout(Duration.ofSeconds(20))
            Object raw = ((JavascriptExecutor) driver).executeScript(jsScript)
            String jsonStr = (raw instanceof String) ? (String) raw : groovy.json.JsonOutput.toJson(raw)
            List parsed = new JsonSlurper().parseText(jsonStr) as List<Map>
            log('Extracted ' + parsed.size() + ' semantic tag(s) (textContent, no layout measure)')
            return parsed
        } catch (Exception e) {
            log("Error extracting elements: " + e.getMessage())
            return []
        } finally {
            if (previous != null) {
                try { timeouts.scriptTimeout(previous) } catch (Exception ignore) { }
            }
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

    /**
     * Pair Sitecore images to AEM by file-name tokens, not list index.
     * Index pairing shifted every later photo after one extra SVG / hidden-tab image.
     */
    private Map pairImagesByName(List liveImages, List aemImages) {
        List live = (liveImages ?: []) as List
        List aem = (aemImages ?: []) as List
        Set used = [] as Set
        List pairs = []
        List unpairedLive = []
        live.each { Object lu ->
            String liveUrl = lu?.toString() ?: ''
            String liveKey = imageKey(liveUrl)
            int best = -1
            int bestScore = 0
            aem.eachWithIndex { Object au, int i ->
                if (used.contains(i)) return
                int s = imageKeyScore(liveKey, imageKey(au?.toString() ?: ''))
                if (s > bestScore) { bestScore = s; best = i }
            }
            if (best >= 0 && bestScore >= 1) {
                used << best
                pairs << [live: liveUrl, aem: aem[best].toString(),
                    liveKey: liveKey, aemKey: imageKey(aem[best].toString()), score: bestScore]
            } else {
                unpairedLive << liveUrl
            }
        }
        return [pairs: pairs, unpairedLive: unpairedLive]
    }

    private String imageKey(String url) {
        String path = (url ?: '').split('\\?')[0].split('#')[0]
        String name = path.contains('/') ? path.substring(path.lastIndexOf('/') + 1) : path
        try { name = URLDecoder.decode(name, 'UTF-8') } catch (Exception ignore) { }
        name = name.toLowerCase().replaceAll('\\.[a-z0-9]+$', '')
        name = name.replaceAll('[-_]+', ' ')
        name = name.replaceAll('\\b\\d+x\\d+\\b', ' ')
        name = name.replaceAll('\\b(mb|desktop|mobile|wid|qlt|img|image|icon|line|light|users)\\b', ' ')
        return name.replaceAll('\\s+', ' ').trim()
    }

    private int imageKeyScore(String a, String b) {
        if (!a || !b) return 0
        if (a == b) return 10
        if (a.contains(b) || b.contains(a)) return 6
        Set ta = (a.split(' ') as List).findAll { it.length() > 2 } as Set
        Set tb = (b.split(' ') as List).findAll { it.length() > 2 } as Set
        if (!ta || !tb) return 0
        return ta.intersect(tb).size()
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

}