package com.globaltalenthub.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Extract plain text from an uploaded brief/PD — port of extractBriefText.
 * PDFBox for PDF, Apache POI for DOCX, passthrough for TXT.
 */
@Service
@Slf4j
public class BriefExtractService {

    // Zip-bomb guard for OOXML (DOCX): reject archives that decompress beyond 100x,
    // and cap extracted text so a crafted file can't bloat memory unbounded.
    private static final double MIN_INFLATE_RATIO = 0.01;
    private static final int MAX_EXTRACTED_CHARS = 5_000_000;

    static {
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
    }

    public String extract(MultipartFile file) {
        String name = Objects.requireNonNull(file.getOriginalFilename(), "filename required").toLowerCase();
        try {
            if (name.endsWith(".pdf")) return cap(extractPdf(file));
            if (name.endsWith(".docx")) return cap(extractDocx(file));
            if (name.endsWith(".txt")) return cap(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[BriefExtract] extraction failed for {}: {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Failed to extract text from file");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Unsupported file type. Use PDF, DOCX, or TXT.");
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    // Truncate runaway extraction (decompression bloat / pathological docs).
    private static String cap(String text) {
        if (text != null && text.length() > MAX_EXTRACTED_CHARS) {
            return text.substring(0, MAX_EXTRACTED_CHARS);
        }
        return text;
    }
}
