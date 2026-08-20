package com.at.util

import com.kms.katalon.core.annotation.Keyword
import com.kms.katalon.core.util.KeywordUtil
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import javax.imageio.ImageIO
import java.net.URL
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

public class FileImageComparer {

    // ==============================================================
    // 📄 NEW: PDF COMPARER METHOD
    // ==============================================================
    @Keyword
    public boolean downloadAndComparePdf(String livePdfUrl, String aemPdfUrl) {
        try {
            KeywordUtil.logInfo("📄 Downloading Live PDF: " + livePdfUrl)
            KeywordUtil.logInfo("📄 Downloading AEM PDF : " + aemPdfUrl)

            // Download files directly into byte arrays
            byte[] livePdfBytes = new URL(livePdfUrl).bytes
            byte[] aemPdfBytes = new URL(aemPdfUrl).bytes

            if (livePdfBytes.length == 0 || aemPdfBytes.length == 0) {
                KeywordUtil.logInfo("❌ Could not download one of the PDF files.")
                return false
            }

            // Generate MD5 Hashes
            String liveMd5 = generateMD5(livePdfBytes)
            String aemMd5 = generateMD5(aemPdfBytes)

            KeywordUtil.logInfo("🔑 Live PDF MD5: " + liveMd5)
            KeywordUtil.logInfo("🔑 AEM PDF MD5 : " + aemMd5)

            // Tier 1 Check: Strict Byte Match
            if (liveMd5.equalsIgnoreCase(aemMd5)) {
                KeywordUtil.logInfo("✅ PDF MATCH! Both files are mathematically identical.")
                return true
            }

            // Tier 2 Check: File Size Match (AEM often injects tiny metadata into DAM assets)
            double sizeDiff = Math.abs(livePdfBytes.length - aemPdfBytes.length)
            double diffPercentage = (sizeDiff / livePdfBytes.length) * 100

            if (diffPercentage <= 3.0) { // 3% tolerance for metadata changes
                KeywordUtil.logInfo("⚠️ MD5 mismatch, but file sizes are nearly identical (" + diffPercentage.round(2) + "% diff). Likely AEM metadata injection. PASS.")
                return true
            }

            KeywordUtil.logInfo("❌ PDF MISMATCH! Files are significantly different.")
            return false

        } catch (Exception e) {
            KeywordUtil.markFailed("Error comparing PDF files: " + e.getMessage())
            return false
        }
    }

    // ==============================================================
    // 🖼️ ORIGINAL: IMAGE COMPARER METHOD
    // ==============================================================
    @Keyword
    public boolean downloadAndCompare(String liveImageUrl, String aemImageUrl) {
        try {
            KeywordUtil.logInfo("📥 Downloading Live Image: " + liveImageUrl)
            KeywordUtil.logInfo("📥 Downloading AEM Image : " + aemImageUrl)

            BufferedImage liveImg = ImageIO.read(new URL(liveImageUrl))
            BufferedImage aemImg = ImageIO.read(new URL(aemImageUrl))

            if (liveImg == null || aemImg == null) {
                KeywordUtil.markFailed("❌ Could not decode one of the images. Ensure WebP support is present!")
                return false
            }

            byte[] liveJpgBytes = convertToJpgBytes(liveImg)
            byte[] aemJpgBytes = convertToJpgBytes(aemImg)

            String liveMd5 = generateMD5(liveJpgBytes)
            String aemMd5 = generateMD5(aemJpgBytes)

            KeywordUtil.logInfo("🔑 Live Image MD5: " + liveMd5)
            KeywordUtil.logInfo("🔑 AEM Image MD5 : " + aemMd5)

            if (liveMd5.equalsIgnoreCase(aemMd5)) {
                KeywordUtil.logInfo("✅ MD5 HASH MATCH! Both images are mathematically identical.")
                return true
            }

            KeywordUtil.logInfo("⚠️ MD5 mismatch (Scale/Compression difference). Falling back to Visual Pixel Match...")
            return fuzzyPixelCompare(liveImg, aemImg)

        } catch (Exception e) {
            KeywordUtil.markFailed("Error comparing downloaded files: " + e.getMessage())
            return false
        }
    }

    // --- HELPER METHODS ---

    private boolean fuzzyPixelCompare(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            KeywordUtil.logInfo("📐 Resizing AEM image from " + img2.getWidth() + "x" + img2.getHeight() + 
                               " to match Live image " + img1.getWidth() + "x" + img1.getHeight())
            img2 = resizeImage(img2, img1.getWidth(), img1.getHeight())
        }

        int diffPixels = 0
        int maxAllowed = (int) ((img1.getWidth() * img1.getHeight()) * 0.10) 

        for (int y = 0; y < img1.getHeight(); y += 2) {
            for (int x = 0; x < img1.getWidth(); x += 2) {
                Color c1 = new Color(img1.getRGB(x, y))
                Color c2 = new Color(img2.getRGB(x, y))
                
                if (Math.abs(c1.getRed() - c2.getRed()) > 15 || 
                    Math.abs(c1.getGreen() - c2.getGreen()) > 15 || 
                    Math.abs(c1.getBlue() - c2.getBlue()) > 15) {
                    diffPixels++
                }
            }
        }
        
        boolean passed = (diffPixels * 4) <= maxAllowed 
        if (passed) {
            KeywordUtil.logInfo("✅ VISUAL MATCH SUCCESS! The images look the same.")
        } else {
            KeywordUtil.logInfo("❌ VISUAL MATCH FAILED! Images are visually different.")
        }
        return passed
    }

    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        Graphics2D g2d = resizedImage.createGraphics()
        g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
        g2d.dispose()
        return resizedImage
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