package com.globaltalenthub.service;

import com.globaltalenthub.service.CoordinateFallbackService.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinateFallbackServiceTest {

    private final CoordinateFallbackService service = new CoordinateFallbackService();

    @Test
    void validCoordinates_usedAsExact() {
        Result r = service.apply("Dubai", "UAE", new BigDecimal("25.1"), new BigDecimal("55.2"));
        assertThat(r.locationPrecision()).isEqualTo("exact");
        assertThat(r.latitude()).isEqualByComparingTo("25.1");
    }

    @Test
    void cityCentroid_whenNoCoordinates() {
        Result r = service.apply("Dubai", "UAE", null, null);
        assertThat(r.locationPrecision()).isEqualTo("city");
        assertThat(r.latitude()).isEqualByComparingTo("25.2048");
        assertThat(r.inferredFrom()).isEqualTo("Dubai");
    }

    @Test
    void countryCentroid_whenCityUnknown() {
        Result r = service.apply("Nowheresville", "Saudi Arabia", null, null);
        assertThat(r.locationPrecision()).isEqualTo("country");
        assertThat(r.latitude()).isEqualByComparingTo("23.8859");
    }

    @Test
    void unknown_whenNothingResolves() {
        Result r = service.apply(null, null, null, null);
        assertThat(r.locationPrecision()).isEqualTo("unknown");
        assertThat(r.latitude()).isNull();
        assertThat(r.longitude()).isNull();
    }

    @Test
    void normalizesCasingAndPunctuation() {
        Result r = service.apply("ABU DHABI!!", null, null, null);
        assertThat(r.locationPrecision()).isEqualTo("city");
    }

    @Test
    void outOfRangeCoordinates_fallThroughToCentroid() {
        Result r = service.apply("London", "UK", new BigDecimal("999"), new BigDecimal("999"));
        assertThat(r.locationPrecision()).isEqualTo("city"); // invalid lat/long ignored
    }
}
