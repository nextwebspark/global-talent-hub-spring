package com.globaltalenthub.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Approximate currency → USD conversion for remuneration analytics. Port of
 * currencyConversion.ts. Unknown codes default to USD (rate 1), matching Node.
 */
@Service
public class CurrencyConversionService {

    private static final Map<String, Double> USD_RATES = Map.ofEntries(
        Map.entry("USD", 1.0), Map.entry("EUR", 1.08), Map.entry("GBP", 1.27), Map.entry("CHF", 1.13),
        Map.entry("JPY", 0.0067), Map.entry("CNY", 0.14), Map.entry("AED", 0.2723), Map.entry("SAR", 0.2667),
        Map.entry("QAR", 0.2747), Map.entry("KWD", 3.26), Map.entry("BHD", 2.6526), Map.entry("OMR", 2.5974),
        Map.entry("EGP", 0.02), Map.entry("JOD", 1.41), Map.entry("INR", 0.012), Map.entry("SGD", 0.75),
        Map.entry("HKD", 0.128), Map.entry("AUD", 0.65), Map.entry("CAD", 0.74), Map.entry("NZD", 0.61),
        Map.entry("ZAR", 0.055), Map.entry("BRL", 0.20), Map.entry("MXN", 0.058), Map.entry("KRW", 0.00075),
        Map.entry("THB", 0.029), Map.entry("MYR", 0.22), Map.entry("IDR", 0.000064), Map.entry("PHP", 0.018),
        Map.entry("TWD", 0.031), Map.entry("TRY", 0.031), Map.entry("SEK", 0.095), Map.entry("NOK", 0.093),
        Map.entry("DKK", 0.145), Map.entry("PLN", 0.25), Map.entry("CZK", 0.043), Map.entry("HUF", 0.0027),
        Map.entry("RUB", 0.011), Map.entry("NGN", 0.00065), Map.entry("KES", 0.0078), Map.entry("PKR", 0.0036),
        Map.entry("BDT", 0.0091), Map.entry("LKR", 0.0034), Map.entry("VND", 0.000041)
    );

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("$", "USD"), Map.entry("us$", "USD"), Map.entry("dollar", "USD"), Map.entry("dollars", "USD"),
        Map.entry("€", "EUR"), Map.entry("euro", "EUR"), Map.entry("euros", "EUR"),
        Map.entry("£", "GBP"), Map.entry("pound", "GBP"), Map.entry("pounds", "GBP"), Map.entry("sterling", "GBP"),
        Map.entry("¥", "JPY"), Map.entry("yen", "JPY"), Map.entry("rmb", "CNY"), Map.entry("yuan", "CNY"),
        Map.entry("dirham", "AED"), Map.entry("dirhams", "AED"), Map.entry("riyal", "SAR"), Map.entry("riyals", "SAR"),
        Map.entry("saudi riyal", "SAR"), Map.entry("qatari riyal", "QAR"), Map.entry("kuwaiti dinar", "KWD"),
        Map.entry("bahraini dinar", "BHD"), Map.entry("omani rial", "OMR"), Map.entry("egyptian pound", "EGP")
    );

    /** Resolve a free-form currency string to a 3-letter code; defaults to USD. */
    public String normalizeCurrencyCode(String raw) {
        if (raw == null || raw.isBlank()) return "USD";
        String lower = raw.trim().toLowerCase();
        String upper = raw.trim().toUpperCase();
        if (USD_RATES.containsKey(upper)) return upper;
        return ALIASES.getOrDefault(lower, "USD");
    }

    /** Convert an amount in the given (already-normalized or raw) currency to USD. */
    public double convertToUSD(double amount, String currencyCode) {
        String code = USD_RATES.containsKey(currencyCode) ? currencyCode : normalizeCurrencyCode(currencyCode);
        return amount * USD_RATES.getOrDefault(code, 1.0);
    }
}
