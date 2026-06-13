package com.globaltalenthub.service;

import com.globaltalenthub.service.pipeline.BriefConfig;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reduce a <em>confidential</em> uploaded brief to neutral search criteria so raw
 * confidential text never reaches the classifier prompt. Port of briefSummary.ts.
 *
 * <p>Fails open: on any LLM error returns "" so the caller falls back to
 * query-only classification rather than throwing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefSummaryService {

    private static final String SUMMARY_INSTRUCTION =
        "Summarize the following job description into a short, neutral list of search criteria "
        + "(sector/industry, geography, company size/revenue, seniority). "
        + "Do not include names, employer, or any confidential details. Reply in 2-4 short lines.";

    private final LlmClassifier classifier;

    public String summarize(String pdText) {
        if (pdText == null || pdText.isBlank()) return "";
        String doc = pdText.substring(0, Math.min(pdText.length(), BriefConfig.SUMMARY_INPUT_CHAR_LIMIT));
        String prompt = SUMMARY_INSTRUCTION + "\n\n<<<DOC\n" + doc + "\nDOC>>>";
        try {
            String out = classifier.classify(prompt);
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            log.warn("[BriefSummary] Summary failed, classifying without brief context: {}", e.getMessage());
            return "";
        }
    }
}
