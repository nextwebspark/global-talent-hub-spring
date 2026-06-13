package com.globaltalenthub.taxonomy;

import java.util.*;

/**
 * Controlled vocabulary — port of shared/taxonomy.ts.
 * All LLM output is validated against these sets before any DB query.
 */
public final class Taxonomy {

    private Taxonomy() {}

    public static final Set<String> SECTORS = Set.of(
        "Banking & Financial Services",
        "Insurance",
        "Capital Markets & Asset Management",
        "Real Estate Development",
        "Construction & Engineering",
        "Oil & Gas - Upstream",
        "Oil & Gas - Downstream / Petrochemicals",
        "Power & Utilities",
        "Telecommunications",
        "Technology & Software",
        "Consumer Goods",
        "Retail & E-Commerce",
        "Hospitality, Travel & Tourism",
        "Healthcare & Pharmaceuticals",
        "Logistics, Shipping & Ports",
        "Aviation & Aerospace",
        "Manufacturing & Industrial",
        "Media, Entertainment & Gaming",
        "Education & Training",
        "Professional Services",
        "Government, Public Sector & Non-Profit",
        "Conglomerates / Family Groups / Holdings"
    );

    public static final Set<String> EMPLOYEE_BANDS = Set.of(
        "1-10", "11-50", "51-200", "201-500", "501-1k", "1k-5k", "5k-10k", "10k+"
    );

    public static final Set<String> REVENUE_BANDS = Set.of(
        "<$10M", "$10-50M", "$50-250M", "$250M-1B", "$1-10B", ">$10B"
    );

    private static final Map<String, List<String>> ADJACENCY = Map.ofEntries(
        Map.entry("Banking & Financial Services",       List.of("Capital Markets & Asset Management", "Insurance", "Technology & Software")),
        Map.entry("Insurance",                          List.of("Banking & Financial Services", "Healthcare & Pharmaceuticals", "Capital Markets & Asset Management")),
        Map.entry("Capital Markets & Asset Management", List.of("Banking & Financial Services", "Insurance", "Professional Services")),
        Map.entry("Real Estate Development",            List.of("Construction & Engineering", "Hospitality, Travel & Tourism", "Conglomerates / Family Groups / Holdings")),
        Map.entry("Construction & Engineering",         List.of("Real Estate Development", "Oil & Gas - Downstream / Petrochemicals", "Manufacturing & Industrial")),
        Map.entry("Oil & Gas - Upstream",               List.of("Oil & Gas - Downstream / Petrochemicals", "Power & Utilities", "Manufacturing & Industrial")),
        Map.entry("Oil & Gas - Downstream / Petrochemicals", List.of("Oil & Gas - Upstream", "Manufacturing & Industrial", "Logistics, Shipping & Ports")),
        Map.entry("Power & Utilities",                  List.of("Oil & Gas - Upstream", "Construction & Engineering", "Manufacturing & Industrial")),
        Map.entry("Telecommunications",                 List.of("Technology & Software", "Media, Entertainment & Gaming", "Professional Services")),
        Map.entry("Technology & Software",              List.of("Telecommunications", "Banking & Financial Services", "Media, Entertainment & Gaming")),
        Map.entry("Consumer Goods",                     List.of("Retail & E-Commerce", "Manufacturing & Industrial", "Logistics, Shipping & Ports")),
        Map.entry("Retail & E-Commerce",                List.of("Consumer Goods", "Hospitality, Travel & Tourism", "Logistics, Shipping & Ports")),
        Map.entry("Hospitality, Travel & Tourism",      List.of("Aviation & Aerospace", "Retail & E-Commerce", "Real Estate Development")),
        Map.entry("Healthcare & Pharmaceuticals",       List.of("Insurance", "Manufacturing & Industrial", "Education & Training")),
        Map.entry("Logistics, Shipping & Ports",        List.of("Retail & E-Commerce", "Aviation & Aerospace", "Manufacturing & Industrial")),
        Map.entry("Aviation & Aerospace",               List.of("Logistics, Shipping & Ports", "Hospitality, Travel & Tourism", "Manufacturing & Industrial")),
        Map.entry("Manufacturing & Industrial",         List.of("Construction & Engineering", "Logistics, Shipping & Ports", "Consumer Goods")),
        Map.entry("Media, Entertainment & Gaming",      List.of("Technology & Software", "Telecommunications", "Professional Services")),
        Map.entry("Education & Training",               List.of("Professional Services", "Healthcare & Pharmaceuticals", "Technology & Software")),
        Map.entry("Professional Services",              List.of("Banking & Financial Services", "Capital Markets & Asset Management", "Conglomerates / Family Groups / Holdings")),
        Map.entry("Government, Public Sector & Non-Profit", List.of("Professional Services", "Construction & Engineering", "Healthcare & Pharmaceuticals")),
        Map.entry("Conglomerates / Family Groups / Holdings", List.of("Real Estate Development", "Retail & E-Commerce", "Hospitality, Travel & Tourism"))
    );

    public static final Set<String> SUB_TAGS;

    /** Per-sector sub-tag lists (insertion-ordered) — used to build the classifier prompt vocabulary block. */
    public static final Map<String, List<String>> SUB_TAGS_BY_SECTOR;

    static {
        Map<String, List<String>> bySecor = new LinkedHashMap<>();
        bySecor.put("Banking & Financial Services", List.of("retail-banking","corporate-banking","investment-banking","private-banking","islamic-banking","sme-lending","consumer-credit","mortgage-financing","buy-now-pay-later","fintech-lending","fintech-payments","fintech-neobank","trade-finance","microfinance","credit-cards"));
        bySecor.put("Insurance", List.of("life-insurance","health-insurance","general-insurance","reinsurance","takaful","insurance-brokerage","marine-insurance","motor-insurance","property-casualty-insurance","insurtech","employee-benefits","claims-management"));
        bySecor.put("Capital Markets & Asset Management", List.of("asset-management","wealth-management","private-equity","venture-capital","hedge-fund","sovereign-wealth-fund","family-office","real-estate-fund","sukuk-issuance","investment-advisory","brokerage-equities","brokerage-fx","custody-services","fund-administration"));
        bySecor.put("Real Estate Development", List.of("residential-development","luxury-residential","commercial-real-estate","mixed-use-development","master-planned-communities","retail-mall-development","hospitality-real-estate","industrial-real-estate","real-estate-brokerage","facilities-management","property-management","real-estate-investment","reits","proptech"));
        bySecor.put("Construction & Engineering", List.of("general-contracting","epc-contractor","mep-contracting","civil-infrastructure","infrastructure-development","roads-bridges","marine-construction","interior-fitout","structural-engineering","architecture-design","project-management-construction","facade-engineering","modular-construction","building-materials-construction"));
        bySecor.put("Oil & Gas - Upstream", List.of("exploration-production","offshore-drilling","onshore-drilling","reservoir-engineering","seismic-services","well-services","oilfield-services","subsea-engineering","lng-upstream","shale-tight-oil","production-optimization","decommissioning-upstream"));
        bySecor.put("Oil & Gas - Downstream / Petrochemicals", List.of("refining","petrochemicals","polymers-plastics","fertilizers","lng-downstream","fuel-retail","lubricants-blending","gas-distribution","industrial-gases","downstream-trading","specialty-chemicals","pipeline-operations"));
        bySecor.put("Power & Utilities", List.of("power-generation-thermal","renewable-solar","renewable-wind","renewable-hydro","nuclear-power","power-transmission","power-distribution","water-desalination","water-treatment","waste-management","district-cooling","energy-trading","grid-tech"));
        bySecor.put("Telecommunications", List.of("mobile-network-operator","fixed-line-broadband","fiber-rollout","5g-infrastructure","tower-infrastructure","data-centers","submarine-cable","mvno","satellite-comms","iot-connectivity","telecom-managed-services","telecom-distribution"));
        bySecor.put("Technology & Software", List.of("enterprise-saas","cloud-infrastructure","cybersecurity","data-analytics","ai-ml-platform","ecommerce-platform","fintech-software","healthtech-software","edtech-software","devtools","it-services","it-services-systems-integration","hardware-iot","ar-vr","blockchain-crypto","digital-platforms"));
        bySecor.put("Consumer Goods", List.of("fmcg-manufacturing","food-beverage-brand","beauty-cosmetics","personal-care","household-products","consumer-products","home-furnishings","jewelry-watches"));
        bySecor.put("Retail & E-Commerce", List.of("fashion-apparel","luxury-retail","grocery-supermarket","consumer-electronics","electronics-retail","automotive-retail","ecommerce-retailer","department-store","specialty-retail","mobile-devices","omnichannel-retail"));
        bySecor.put("Hospitality, Travel & Tourism", List.of("hotels-luxury","hotels-midscale","resorts","serviced-apartments","restaurants-fb","qsr-fast-casual","cafes-coffee","food-service","destination-management","tour-operator","travel-agency-otas","cruise-yachting","events-conferences","theme-parks-attractions"));
        bySecor.put("Healthcare & Pharmaceuticals", List.of("hospitals-multispecialty","specialty-clinics","primary-care","diagnostic-imaging","laboratory-services","pharmaceutical-manufacturing","pharma-distribution","medical-devices","biotech-research","telehealth","digital-health","pharmacy-retail","home-healthcare"));
        bySecor.put("Logistics, Shipping & Ports", List.of("freight-forwarding","3pl-warehousing","last-mile-delivery","shipping-lines","port-operations","cold-chain-logistics","express-courier","supply-chain-management","customs-brokerage","intermodal-rail","trucking-road-freight","logistics-distribution","logistics-tech"));
        bySecor.put("Aviation & Aerospace", List.of("passenger-airline","cargo-airline","low-cost-carrier","airport-operations","ground-handling","aviation-services","mro-aircraft-maintenance","aircraft-leasing","aerospace-manufacturing","defense-aviation","satellite-services","drone-uav","business-aviation"));
        bySecor.put("Manufacturing & Industrial", List.of("steel-metals","aluminum-smelting","cement-building-materials","glass-ceramics","industrial-equipment","automotive-components","food-processing","textiles-apparel-manufacturing","packaging","heavy-machinery","electrical-equipment","industrial-automation","contract-manufacturing"));
        bySecor.put("Media, Entertainment & Gaming", List.of("broadcasting-tv","radio","print-publishing","digital-publishing","advertising-agency","media-buying","pr-communications","film-production","music-recording","gaming-esports","streaming-platforms","influencer-creator-economy"));
        bySecor.put("Education & Training", List.of("k12-schools","higher-education-university","vocational-training","edtech-platforms","corporate-training","language-training","professional-certifications","test-prep","tutoring-services","early-childhood-education","online-courses-mooc","executive-education"));
        bySecor.put("Professional Services", List.of("management-consulting","strategy-consulting","audit-assurance","tax-advisory","legal-services","executive-search","staffing-recruitment","real-estate-advisory","marketing-research","engineering-consulting","actuarial-services","outsourcing-bpo","facilities-management-services"));
        bySecor.put("Government, Public Sector & Non-Profit", List.of("government-entity","regulatory-body","ngo","international-organisation","public-sector-agency","defense-public"));
        bySecor.put("Conglomerates / Family Groups / Holdings", List.of("family-business-group","family-office-operating","diversified-holding","investment-holding","trading-group","industrial-conglomerate","ruling-family-holding","sovereign-holding","regional-distributor-group","multi-sector-investment-group"));

        Set<String> tags = new HashSet<>();
        bySecor.values().forEach(tags::addAll);
        SUB_TAGS = Collections.unmodifiableSet(tags);
        SUB_TAGS_BY_SECTOR = Collections.unmodifiableMap(bySecor);
    }

    /**
     * Adjacent sectors for a set of primary sectors, deduped, excluding the primaries.
     */
    public static List<String> adjacentSectorsFor(List<String> primarySectors) {
        Set<String> primarySet = new HashSet<>(primarySectors);
        Set<String> out = new LinkedHashSet<>();
        for (String sector : primarySectors) {
            for (String adj : ADJACENCY.getOrDefault(sector, List.of())) {
                if (!primarySet.contains(adj)) {
                    out.add(adj);
                }
            }
        }
        return List.copyOf(out);
    }
}
