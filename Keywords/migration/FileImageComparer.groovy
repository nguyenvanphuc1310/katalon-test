package migration

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.imageio.ImageIO
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

public class FileImageComparer {

    // ==============================================================
    // 📄 PDF COMPARER
    // ==============================================================
    /**
     * Download both PDFs and compare: exact MD5, then a 3% size tolerance for
     * AEM DAM metadata. Returns matched/md5/sizes/logLines — the check records
     * the verdict so this method does not markFailed.
     */
    @Keyword
    public Map comparePdfFiles(String livePdfUrl, String aemPdfUrl) {
        List logs = []
        Map out = [matched: false, liveMd5: '', aemMd5: '',
            liveBytes: 0, aemBytes: 0, logLines: logs, error: '']
        Closure log = { String m ->
            KeywordUtil.logInfo(m)
            logs << m
        }
        try {
            log("Downloading Live PDF: " + livePdfUrl)
            log("Downloading AEM PDF : " + aemPdfUrl)

            byte[] livePdfBytes = AuditUtils.downloadBytes(livePdfUrl)
            byte[] aemPdfBytes = AuditUtils.downloadBytes(aemPdfUrl)
            out.liveBytes = livePdfBytes.length
            out.aemBytes = aemPdfBytes.length

            if (livePdfBytes.length == 0 || aemPdfBytes.length == 0) {
                out.error = 'Could not download one of the PDF files'
                log(out.error)
                return out
            }
            if (!isPdfMagic(livePdfBytes)) {
                out.error = 'Live URL did not return a PDF file'
                log(out.error)
                return out
            }
            if (!isPdfMagic(aemPdfBytes)) {
                out.error = 'AEM URL did not return a PDF file'
                log(out.error)
                return out
            }

            out.liveMd5 = generateMD5(livePdfBytes)
            out.aemMd5 = generateMD5(aemPdfBytes)
            log("Live PDF MD5: " + out.liveMd5 + " (" + out.liveBytes + " bytes)")
            log("AEM PDF MD5 : " + out.aemMd5 + " (" + out.aemBytes + " bytes)")

            if (out.liveMd5.equalsIgnoreCase(out.aemMd5)) {
                out.matched = true
                log("PDF MATCH: both files are identical (MD5).")
                return out
            }

            double sizeDiff = Math.abs(livePdfBytes.length - aemPdfBytes.length)
            double diffPercentage = (sizeDiff / livePdfBytes.length) * 100
            if (diffPercentage <= 3.0) {
                out.matched = true
                log("MD5 mismatch, but file sizes are within 3% (" +
                    String.format('%.2f', diffPercentage) + "%). Treating as AEM metadata. PASS.")
                return out
            }

            out.error = 'PDF files differ (MD5 and size)'
            log("PDF MISMATCH: files are significantly different (" +
                String.format('%.2f', diffPercentage) + "% size diff).")
            return out
        } catch (Exception e) {
            out.error = e.getMessage() ?: 'unknown error'
            log("Error comparing PDF files: " + out.error)
            return out
        }
    }

    @Keyword
    public boolean downloadAndComparePdf(String livePdfUrl, String aemPdfUrl) {
        return (comparePdfFiles(livePdfUrl, aemPdfUrl).matched as boolean)
    }

    private boolean isPdfMagic(byte[] bytes) {
        return bytes != null && bytes.length >= 4 &&
            bytes[0] == (byte) 0x25 && bytes[1] == (byte) 0x50 &&
            bytes[2] == (byte) 0x44 && bytes[3] == (byte) 0x46
    }

    // ==============================================================
    // 🖼️ IMAGE COMPARER
    // Same photo in a different size or zoom must PASS. A different photo must FAIL.
    //   1. MD5 of a JPEG copy — exact same file
    //   2. Both fitted on a 256px square (keep aspect ratio) — large vs small
    //   3. Center 70% of that square — one crop is zoomed in
    //   4. 8x8 average hash — "does a person see the same picture?"
    // ==============================================================
    static final int LOOKS_LIKE_SIZE = 256
    static final double CENTER_CROP = 0.70d
    // AEM often recompresses (webp→jpg) and slightly retints. Slack is per RGB channel 0–255.
    static final int RGB_SLACK = 36
    static final double PIXEL_DIFF_OK = 0.20d
    static final double CENTER_DIFF_OK = 0.24d
    static final int PHASH_MAX_DISTANCE = 16
    static final String FAIL_NOTE = 'Images do not match visually (failed fuzzy pixel comparison).'
    static final String FORMAT_NOTE =
        'Format Mismatch: %s vs %s. Cannot pixel-compare vector graphics to raster images.'

    @Keyword
    public boolean downloadAndCompare(String liveImageUrl, String aemImageUrl) {
        return (compareImages(liveImageUrl, aemImageUrl).matched as boolean)
    }

    /**
     * Same checks as downloadAndCompare. Detailed steps go to KeywordUtil.logInfo only.
     * The report uses `how` — a short phrase on pass, FAIL_NOTE on fail.
     */
    public Map compareImages(String liveImageUrl, String aemImageUrl) {
        List logs = []
        Map out = [
            matched : false,
            how     : '',
            error   : '',
            liveW   : 0,
            liveH   : 0,
            aemW    : 0,
            aemH    : 0,
            liveBytes: 0,
            aemBytes: 0,
            hashDist: -1,
            liveUrl : liveImageUrl ?: '',
            aemUrl  : aemImageUrl ?: '',
            logLines: logs,
        ]
        // Console only. Do not append to logLines — the report UI must not dump these steps.
        Closure info = { String m ->
            KeywordUtil.logInfo(m)
        }
        try {
            String liveKind = formatOf(liveImageUrl, null)
            String aemKind = formatOf(aemImageUrl, null)
            if (isSvgKind(liveKind) != isSvgKind(aemKind)) {
                out.how = String.format(FORMAT_NOTE, liveKind, aemKind)
                out.error = out.how
                logs << out.how
                info(out.how)
                return out
            }
            if (isSvgKind(liveKind) && isSvgKind(aemKind)) {
                out.matched = true
                out.how = 'both vector icons (SVG) — pixel compare skipped'
                info('IMAGE MATCH: both sides are SVG. ImageIO cannot rasterise them; pair stays aligned.')
                return out
            }

            info("Downloading Live Image: " + liveImageUrl)
            info("Downloading AEM Image : " + aemImageUrl)

            byte[] liveBytes = AuditUtils.downloadBytes(liveImageUrl)
            byte[] aemBytes = AuditUtils.downloadBytes(aemImageUrl)
            out.liveBytes = liveBytes?.length ?: 0
            out.aemBytes = aemBytes?.length ?: 0

            liveKind = formatOf(liveImageUrl, liveBytes)
            aemKind = formatOf(aemImageUrl, aemBytes)
            if (isSvgKind(liveKind) != isSvgKind(aemKind)) {
                out.how = String.format(FORMAT_NOTE, liveKind, aemKind)
                out.error = out.how
                logs << out.how
                info(out.how)
                return out
            }
            if (isSvgKind(liveKind) && isSvgKind(aemKind)) {
                out.matched = true
                out.how = 'both vector icons (SVG) — pixel compare skipped'
                info('IMAGE MATCH: both sides are SVG after download. Pair stays aligned.')
                return out
            }

            BufferedImage liveImg = (liveBytes != null && liveBytes.length > 0 && !isSvgKind(liveKind))
                ? ImageIO.read(new ByteArrayInputStream(liveBytes)) : null
            BufferedImage aemImg = (aemBytes != null && aemBytes.length > 0 && !isSvgKind(aemKind))
                ? ImageIO.read(new ByteArrayInputStream(aemBytes)) : null

            if (liveImg == null || aemImg == null) {
                out.how = 'Could not decode one of the images'
                out.error = out.how
                info('Could not decode one of the images. Ensure WebP support is present.')
                return out
            }
            out.liveW = liveImg.getWidth()
            out.liveH = liveImg.getHeight()
            out.aemW = aemImg.getWidth()
            out.aemH = aemImg.getHeight()
            info("Live " + out.liveW + "x" + out.liveH + " | AEM " + out.aemW + "x" + out.aemH)

            String liveMd5 = generateMD5(convertToJpgBytes(liveImg))
            String aemMd5 = generateMD5(convertToJpgBytes(aemImg))
            info("Live Image MD5: " + liveMd5)
            info("AEM Image MD5 : " + aemMd5)
            if (liveMd5.equalsIgnoreCase(aemMd5)) {
                out.matched = true
                out.how = 'identical file'
                info("IMAGE MATCH: identical file (MD5).")
                return out
            }

            info("MD5 differs (size, zoom or compression). Checking whether they look the same...")

            BufferedImage liveFit = fitOnCanvas(liveImg, LOOKS_LIKE_SIZE)
            BufferedImage aemFit = fitOnCanvas(aemImg, LOOKS_LIKE_SIZE)
            if (pixelMatch(liveFit, aemFit, PIXEL_DIFF_OK, RGB_SLACK)) {
                out.matched = true
                out.how = 'same picture, different size'
                info("IMAGE MATCH: same picture after resizing (different file size or scale).")
                return out
            }
            info("Not a match after resizing both to 256px. Trying the center (covers zoom / crop)...")

            BufferedImage liveCenter = centerCrop(liveFit, CENTER_CROP)
            BufferedImage aemCenter = centerCrop(aemFit, CENTER_CROP)
            if (pixelMatch(liveCenter, aemCenter, CENTER_DIFF_OK, RGB_SLACK + 2)) {
                out.matched = true
                out.how = 'same picture, different zoom'
                info("IMAGE MATCH: same picture in the center (one side is more zoomed).")
                return out
            }
            info("Center crop did not match. Trying look-alike hash...")

            int dist = averageHashDistance(liveImg, aemImg)
            out.hashDist = dist
            info("Look-alike hash distance: " + dist + " / 64 (pass if <= " + PHASH_MAX_DISTANCE + ")")
            if (dist <= PHASH_MAX_DISTANCE) {
                out.matched = true
                out.how = 'same picture (look-alike)'
                info("IMAGE MATCH: they look like the same photo (scale/zoom ignored).")
                return out
            }

            out.how = FAIL_NOTE
            out.error = FAIL_NOTE
            logs << FAIL_NOTE
            info("IMAGE FAIL: " + FAIL_NOTE + " look-alike hash " + dist + "/64.")
            return out
        } catch (Exception e) {
            out.how = FAIL_NOTE
            out.error = FAIL_NOTE
            logs << FAIL_NOTE
            info("Error comparing downloaded files: " + (e.getMessage() ?: 'unknown error'))
            return out
        }
    }

    /** Groovy `/` on ints yields BigDecimal; Graphics2D.drawImage requires int. */
    private static int asInt(Object n) {
        if (n == null) return 0
        return (int) Math.round(((Number) n).doubleValue())
    }

    static boolean isSvgKind(String kind) {
        return (kind ?: '').equalsIgnoreCase('SVG')
    }

    /** Label for the report: SVG, WebP, JPG, PNG, GIF, or Raster. */
    static String formatOf(String url, byte[] bytes) {
        String path = urlPath(url)
        if (path.endsWith('.svg') || isSvgBytes(bytes)) return 'SVG'
        if (path.endsWith('.webp') || isWebpBytes(bytes)) return 'WebP'
        if (path.endsWith('.png')) return 'PNG'
        if (path.endsWith('.jpg') || path.endsWith('.jpeg')) return 'JPG'
        if (path.endsWith('.gif')) return 'GIF'
        return 'Raster'
    }

    private static String urlPath(String url) {
        return (url ?: '').split('\\?')[0].split('#')[0].toLowerCase()
    }

    private static boolean isSvgBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false
        int n = Math.min(bytes.length, 256)
        String head = new String(bytes, 0, n, 'UTF-8').trim().toLowerCase()
        return head.contains('<svg')
    }

    private static boolean isWebpBytes(byte[] bytes) {
        return bytes != null && bytes.length >= 12 &&
            bytes[0] == (byte) 0x52 && bytes[1] == (byte) 0x49 &&
            bytes[2] == (byte) 0x46 && bytes[3] == (byte) 0x46 &&
            bytes[8] == (byte) 0x57 && bytes[9] == (byte) 0x45 &&
            bytes[10] == (byte) 0x42 && bytes[11] == (byte) 0x50
    }

    /** Fit inside a square without stretching. Empty bands stay white. */
    private BufferedImage fitOnCanvas(BufferedImage src, int size) {
        double scale = Math.min(size / (double) src.getWidth(), size / (double) src.getHeight())
        int w = Math.max(1, asInt(Math.round(src.getWidth() * scale)))
        int h = Math.max(1, asInt(Math.round(src.getHeight() * scale)))
        int x = asInt((size - w) / 2)
        int y = asInt((size - h) / 2)
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        Graphics2D g = canvas.createGraphics()
        g.setColor(Color.WHITE)
        g.fillRect(0, 0, size, size)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, x, y, w, h, null)
        g.dispose()
        return canvas
    }

    private BufferedImage centerCrop(BufferedImage src, double fraction) {
        int w = Math.max(1, asInt(Math.round(src.getWidth() * fraction)))
        int h = Math.max(1, asInt(Math.round(src.getHeight() * fraction)))
        int x = Math.max(0, asInt((src.getWidth() - w) / 2))
        int y = Math.max(0, asInt((src.getHeight() - h) / 2))
        return src.getSubimage(x, y, w, h)
    }

    private boolean pixelMatch(BufferedImage a, BufferedImage b, double maxDiffRatio, int rgbSlack) {
        int w = Math.min(a.getWidth(), b.getWidth())
        int h = Math.min(a.getHeight(), b.getHeight())
        if (w < 1 || h < 1) return false
        int diff = 0, samples = 0
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                samples++
                Color c1 = new Color(a.getRGB(x, y))
                Color c2 = new Color(b.getRGB(x, y))
                if (Math.abs(c1.getRed() - c2.getRed()) > rgbSlack ||
                    Math.abs(c1.getGreen() - c2.getGreen()) > rgbSlack ||
                    Math.abs(c1.getBlue() - c2.getBlue()) > rgbSlack) {
                    diff++
                }
            }
        }
        return samples > 0 && (diff / (double) samples) <= maxDiffRatio
    }

    /** Average hash: 8x8 grayscale, one bit per pixel vs the mean. Hamming distance 0–64. */
    private int averageHashDistance(BufferedImage a, BufferedImage b) {
        return Long.bitCount(averageHash(a) ^ averageHash(b))
    }

    private long averageHash(BufferedImage src) {
        BufferedImage small = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY)
        Graphics2D g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, 8, 8, null)
        g.dispose()
        int[] px = new int[64]
        int sum = 0
        for (int i = 0; i < 64; i++) {
            px[i] = new Color(small.getRGB(i % 8, i / 8)).getRed()
            sum += px[i]
        }
        int avg = sum.intdiv(64)
        long bits = 0L
        for (int i = 0; i < 64; i++) {
            if (px[i] >= avg) bits |= (1L << i)
        }
        return bits
    }

    private BufferedImage readImage(String url) {
        byte[] bytes = AuditUtils.downloadBytes(url)
        if (bytes == null || bytes.length == 0) return null
        return ImageIO.read(new ByteArrayInputStream(bytes))
    }

    private byte[] convertToJpgBytes(BufferedImage originalImage) {
        BufferedImage newJpg = new BufferedImage(
            originalImage.getWidth(), 
            originalImage.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        )
        newJpg.createGraphics().drawImage(originalImage, 0, 0, Color.WHITE, null)
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ImageIO.write(newJpg, "jpg", baos)
        return baos.toByteArray()
    }

    private String generateMD5(byte[] inputBytes) {
        MessageDigest md = MessageDigest.getInstance("MD5")
        byte[] digest = md.digest(inputBytes)
        
        StringBuilder sb = new StringBuilder()
        for (byte b : digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}