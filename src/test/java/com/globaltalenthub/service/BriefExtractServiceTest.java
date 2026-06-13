package com.globaltalenthub.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BriefExtractServiceTest {

    private final BriefExtractService service = new BriefExtractService();

    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithText(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsPdfText() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "brief.pdf", "application/pdf", pdfWithText("CFO mandate Dubai"));
        assertThat(service.extract(file)).contains("CFO mandate Dubai");
    }

    @Test
    void extractsDocxText() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "brief.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxWithText("Head of Risk, Riyadh"));
        assertThat(service.extract(file)).contains("Head of Risk, Riyadh");
    }

    @Test
    void extractsTxtPassthrough() {
        MockMultipartFile file = new MockMultipartFile("file", "brief.txt", "text/plain",
            "plain brief text".getBytes(StandardCharsets.UTF_8));
        assertThat(service.extract(file)).isEqualTo("plain brief text");
    }

    @Test
    void unsupportedType_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "brief.rtf", "application/rtf", "x".getBytes());
        assertThatThrownBy(() -> service.extract(file))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Unsupported file type")
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
