package com.globaltalenthub.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CurrencyConversionServiceTest {

    private final CurrencyConversionService service = new CurrencyConversionService();

    @Test
    void normalizeCurrencyCode_resolvesCodesAliasesAndSymbols() {
        assertThat(service.normalizeCurrencyCode("AED")).isEqualTo("AED");
        assertThat(service.normalizeCurrencyCode("aed")).isEqualTo("AED");
        assertThat(service.normalizeCurrencyCode("dirhams")).isEqualTo("AED");
        assertThat(service.normalizeCurrencyCode("$")).isEqualTo("USD");
        assertThat(service.normalizeCurrencyCode("saudi riyal")).isEqualTo("SAR");
    }

    @Test
    void normalizeCurrencyCode_unknownDefaultsToUSD() {
        assertThat(service.normalizeCurrencyCode("ZZZ")).isEqualTo("USD");
        assertThat(service.normalizeCurrencyCode(null)).isEqualTo("USD");
        assertThat(service.normalizeCurrencyCode("")).isEqualTo("USD");
    }

    @Test
    void convertToUSD_appliesRate() {
        assertThat(service.convertToUSD(100, "USD")).isEqualTo(100);
        assertThat(service.convertToUSD(100, "AED")).isCloseTo(27.23, within(0.001));
        // raw alias is normalized before lookup
        assertThat(service.convertToUSD(100, "dirhams")).isCloseTo(27.23, within(0.001));
    }

    @Test
    void convertToUSD_unknownCurrency_treatedAsUSD() {
        assertThat(service.convertToUSD(100, "ZZZ")).isEqualTo(100);
    }
}
