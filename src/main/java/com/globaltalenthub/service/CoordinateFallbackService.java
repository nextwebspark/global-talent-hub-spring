package com.globaltalenthub.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Graceful degradation for missing coordinates — port of coordinateFallback.ts.
 * <ol>
 *   <li>valid lat/long → use as-is, precision "exact"</li>
 *   <li>else city centroid → precision "city"</li>
 *   <li>else country centroid → precision "country"</li>
 *   <li>else precision "unknown"</li>
 * </ol>
 */
@Service
@Slf4j
public class CoordinateFallbackService {

    public record Result(BigDecimal latitude, BigDecimal longitude, String locationPrecision, String inferredFrom) {}

    private record LatLng(double lat, double lng) {}

    private static final Map<String, LatLng> COUNTRY_CENTROIDS = Map.ofEntries(
        Map.entry("united arab emirates", new LatLng(24.4539, 54.3773)),
        Map.entry("uae", new LatLng(24.4539, 54.3773)),
        Map.entry("saudi arabia", new LatLng(23.8859, 45.0792)),
        Map.entry("ksa", new LatLng(23.8859, 45.0792)),
        Map.entry("qatar", new LatLng(25.3548, 51.1839)),
        Map.entry("kuwait", new LatLng(29.3759, 47.9774)),
        Map.entry("bahrain", new LatLng(26.0667, 50.5577)),
        Map.entry("oman", new LatLng(21.4735, 55.9754)),
        Map.entry("jordan", new LatLng(30.5852, 36.2384)),
        Map.entry("lebanon", new LatLng(33.8547, 35.8623)),
        Map.entry("iraq", new LatLng(33.2232, 43.6793)),
        Map.entry("iran", new LatLng(32.4279, 53.6880)),
        Map.entry("israel", new LatLng(31.0461, 34.8516)),
        Map.entry("palestine", new LatLng(31.9522, 35.2332)),
        Map.entry("syria", new LatLng(34.8021, 38.9968)),
        Map.entry("yemen", new LatLng(15.5527, 48.5164)),
        Map.entry("egypt", new LatLng(26.8206, 30.8025)),
        Map.entry("turkey", new LatLng(38.9637, 35.2433)),
        Map.entry("united states", new LatLng(37.0902, -95.7129)),
        Map.entry("usa", new LatLng(37.0902, -95.7129)),
        Map.entry("united kingdom", new LatLng(55.3781, -3.4360)),
        Map.entry("uk", new LatLng(55.3781, -3.4360)),
        Map.entry("germany", new LatLng(51.1657, 10.4515)),
        Map.entry("france", new LatLng(46.2276, 2.2137)),
        Map.entry("italy", new LatLng(41.8719, 12.5674)),
        Map.entry("spain", new LatLng(40.4637, -3.7492)),
        Map.entry("netherlands", new LatLng(52.1326, 5.2913)),
        Map.entry("switzerland", new LatLng(46.8182, 8.2275)),
        Map.entry("canada", new LatLng(56.1304, -106.3468)),
        Map.entry("australia", new LatLng(-25.2744, 133.7751)),
        Map.entry("japan", new LatLng(36.2048, 138.2529)),
        Map.entry("china", new LatLng(35.8617, 104.1954)),
        Map.entry("india", new LatLng(20.5937, 78.9629)),
        Map.entry("singapore", new LatLng(1.3521, 103.8198)),
        Map.entry("hong kong", new LatLng(22.3193, 114.1694)),
        Map.entry("south korea", new LatLng(35.9078, 127.7669)),
        Map.entry("brazil", new LatLng(-14.2350, -51.9253)),
        Map.entry("mexico", new LatLng(23.6345, -102.5528)),
        Map.entry("russia", new LatLng(61.5240, 105.3188)),
        Map.entry("south africa", new LatLng(-30.5595, 22.9375)),
        Map.entry("nigeria", new LatLng(9.0820, 8.6753)),
        Map.entry("kenya", new LatLng(-0.0236, 37.9062)),
        Map.entry("morocco", new LatLng(31.7917, -7.0926)),
        Map.entry("pakistan", new LatLng(30.3753, 69.3451)),
        Map.entry("indonesia", new LatLng(-0.7893, 113.9213)),
        Map.entry("malaysia", new LatLng(4.2105, 101.9758)),
        Map.entry("thailand", new LatLng(15.8700, 100.9925)),
        Map.entry("vietnam", new LatLng(14.0583, 108.2772)),
        Map.entry("philippines", new LatLng(12.8797, 121.7740)),
        Map.entry("sweden", new LatLng(60.1282, 18.6435)),
        Map.entry("norway", new LatLng(60.4720, 8.4689)),
        Map.entry("denmark", new LatLng(56.2639, 9.5018)),
        Map.entry("finland", new LatLng(61.9241, 25.7482)),
        Map.entry("poland", new LatLng(51.9194, 19.1451)),
        Map.entry("austria", new LatLng(47.5162, 14.5501)),
        Map.entry("belgium", new LatLng(50.5039, 4.4699)),
        Map.entry("ireland", new LatLng(53.1424, -7.6921)),
        Map.entry("portugal", new LatLng(39.3999, -8.2245)),
        Map.entry("greece", new LatLng(39.0742, 21.8243)),
        Map.entry("czech republic", new LatLng(49.8175, 15.4730)),
        Map.entry("new zealand", new LatLng(-40.9006, 174.8860)),
        Map.entry("argentina", new LatLng(-38.4161, -63.6167)),
        Map.entry("chile", new LatLng(-35.6751, -71.5430)),
        Map.entry("colombia", new LatLng(4.5709, -74.2973)),
        Map.entry("peru", new LatLng(-9.1900, -75.0152)),
        Map.entry("middle east", new LatLng(25.0, 45.0)),
        Map.entry("gcc", new LatLng(25.0, 50.0)),
        Map.entry("algeria", new LatLng(28.0339, 1.6596))
    );

    private static final Map<String, LatLng> CITY_CENTROIDS = Map.ofEntries(
        Map.entry("dubai", new LatLng(25.2048, 55.2708)),
        Map.entry("abu dhabi", new LatLng(24.4539, 54.3773)),
        Map.entry("sharjah", new LatLng(25.3573, 55.4033)),
        Map.entry("riyadh", new LatLng(24.7136, 46.6753)),
        Map.entry("jeddah", new LatLng(21.5433, 39.1728)),
        Map.entry("dammam", new LatLng(26.4207, 50.0888)),
        Map.entry("doha", new LatLng(25.2854, 51.5310)),
        Map.entry("kuwait city", new LatLng(29.3759, 47.9774)),
        Map.entry("manama", new LatLng(26.2285, 50.5860)),
        Map.entry("muscat", new LatLng(23.5880, 58.3829)),
        Map.entry("amman", new LatLng(31.9454, 35.9284)),
        Map.entry("beirut", new LatLng(33.8938, 35.5018)),
        Map.entry("cairo", new LatLng(30.0444, 31.2357)),
        Map.entry("istanbul", new LatLng(41.0082, 28.9784)),
        Map.entry("tehran", new LatLng(35.6892, 51.3890)),
        Map.entry("tel aviv", new LatLng(32.0853, 34.7818)),
        Map.entry("jerusalem", new LatLng(31.7683, 35.2137)),
        Map.entry("baghdad", new LatLng(33.3152, 44.3661)),
        Map.entry("new york", new LatLng(40.7128, -74.0060)),
        Map.entry("new york city", new LatLng(40.7128, -74.0060)),
        Map.entry("los angeles", new LatLng(34.0522, -118.2437)),
        Map.entry("san francisco", new LatLng(37.7749, -122.4194)),
        Map.entry("chicago", new LatLng(41.8781, -87.6298)),
        Map.entry("london", new LatLng(51.5074, -0.1278)),
        Map.entry("paris", new LatLng(48.8566, 2.3522)),
        Map.entry("berlin", new LatLng(52.5200, 13.4050)),
        Map.entry("frankfurt", new LatLng(50.1109, 8.6821)),
        Map.entry("munich", new LatLng(48.1351, 11.5820)),
        Map.entry("amsterdam", new LatLng(52.3676, 4.9041)),
        Map.entry("zurich", new LatLng(47.3769, 8.5417)),
        Map.entry("geneva", new LatLng(46.2044, 6.1432)),
        Map.entry("tokyo", new LatLng(35.6762, 139.6503)),
        Map.entry("beijing", new LatLng(39.9042, 116.4074)),
        Map.entry("shanghai", new LatLng(31.2304, 121.4737)),
        Map.entry("hong kong", new LatLng(22.3193, 114.1694)),
        Map.entry("singapore", new LatLng(1.3521, 103.8198)),
        Map.entry("sydney", new LatLng(-33.8688, 151.2093)),
        Map.entry("melbourne", new LatLng(-37.8136, 144.9631)),
        Map.entry("mumbai", new LatLng(19.0760, 72.8777)),
        Map.entry("delhi", new LatLng(28.7041, 77.1025)),
        Map.entry("new delhi", new LatLng(28.6139, 77.2090)),
        Map.entry("bangalore", new LatLng(12.9716, 77.5946)),
        Map.entry("toronto", new LatLng(43.6532, -79.3832)),
        Map.entry("vancouver", new LatLng(49.2827, -123.1207)),
        Map.entry("sao paulo", new LatLng(-23.5505, -46.6333)),
        Map.entry("mexico city", new LatLng(19.4326, -99.1332)),
        Map.entry("moscow", new LatLng(55.7558, 37.6173)),
        Map.entry("johannesburg", new LatLng(-26.2041, 28.0473)),
        Map.entry("cape town", new LatLng(-33.9249, 18.4241)),
        Map.entry("lagos", new LatLng(6.5244, 3.3792)),
        Map.entry("nairobi", new LatLng(-1.2921, 36.8219)),
        Map.entry("casablanca", new LatLng(33.5731, -7.5898)),
        Map.entry("kuala lumpur", new LatLng(3.1390, 101.6869)),
        Map.entry("jakarta", new LatLng(-6.2088, 106.8456)),
        Map.entry("bangkok", new LatLng(13.7563, 100.5018)),
        Map.entry("seoul", new LatLng(37.5665, 126.9780)),
        Map.entry("stockholm", new LatLng(59.3293, 18.0686)),
        Map.entry("oslo", new LatLng(59.9139, 10.7522)),
        Map.entry("copenhagen", new LatLng(55.6761, 12.5683)),
        Map.entry("helsinki", new LatLng(60.1699, 24.9384)),
        Map.entry("dublin", new LatLng(53.3498, -6.2603)),
        Map.entry("vienna", new LatLng(48.2082, 16.3738)),
        Map.entry("brussels", new LatLng(50.8503, 4.3517)),
        Map.entry("milan", new LatLng(45.4642, 9.1900)),
        Map.entry("rome", new LatLng(41.9028, 12.4964)),
        Map.entry("madrid", new LatLng(40.4168, -3.7038)),
        Map.entry("barcelona", new LatLng(41.3851, 2.1734)),
        Map.entry("lisbon", new LatLng(38.7223, -9.1393)),
        Map.entry("athens", new LatLng(37.9838, 23.7275)),
        Map.entry("prague", new LatLng(50.0755, 14.4378)),
        Map.entry("warsaw", new LatLng(52.2297, 21.0122))
    );

    private static String normalize(String location) {
        return location.toLowerCase().trim()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", " ");
    }

    public Result apply(String city, String country, BigDecimal latitude, BigDecimal longitude) {
        // Case 1: valid coordinates provided.
        if (latitude != null && longitude != null) {
            double lat = latitude.doubleValue();
            double lng = longitude.doubleValue();
            if (lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) {
                return new Result(latitude, longitude, "exact", null);
            }
        }
        // Case 2: city centroid.
        if (city != null && !city.isBlank()) {
            LatLng c = CITY_CENTROIDS.get(normalize(city));
            if (c != null) {
                return new Result(BigDecimal.valueOf(c.lat()), BigDecimal.valueOf(c.lng()), "city", city);
            }
        }
        // Case 3: country centroid.
        if (country != null && !country.isBlank()) {
            LatLng c = COUNTRY_CENTROIDS.get(normalize(country));
            if (c != null) {
                return new Result(BigDecimal.valueOf(c.lat()), BigDecimal.valueOf(c.lng()), "country", country);
            }
        }
        // Case 4: nothing.
        return new Result(null, null, "unknown", null);
    }
}
