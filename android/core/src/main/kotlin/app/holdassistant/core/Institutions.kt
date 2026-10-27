package app.holdassistant.core

/** What the importer knows about one address-book contact. */
data class ContactInfo(
    val id: String,
    val displayName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val organization: String? = null,
    val jobTitle: String? = null,
    val phones: List<Phone> = emptyList(),
    val starred: Boolean = false,
    val hasBirthday: Boolean = false,
    val hasNickname: Boolean = false,
    val hasRelation: Boolean = false,
) {
    enum class PhoneType { MOBILE, HOME, WORK, MAIN, OTHER }

    data class Phone(val number: String, val type: PhoneType = PhoneType.OTHER)
}

enum class ContactKind { INSTITUTION, PERSONAL, UNCLEAR }

data class ContactVerdict(val kind: ContactKind, val score: Int, val reasons: List<String>)

/**
 * Heuristic sort of address-book contacts into institutions (banks, carriers,
 * insurers, agencies: lines with phone trees) versus people.
 */
object InstitutionClassifier {
    /** Score at or above which a contact is an institution; at or below [PERSONAL_AT], personal. */
    const val INSTITUTION_AT = 3
    const val PERSONAL_AT = 0

    private val TOLL_FREE = setOf("800", "833", "844", "855", "866", "877", "888")

    /** Companies and agencies known for phone trees and long holds. */
    val BRANDS = listOf(
        // Banks, cards, lenders, brokerages
        "chase", "jpmorgan", "bank of america", "boa", "wells fargo", "citi", "citibank", "capital one",
        "us bank", "u.s. bank", "pnc", "truist", "td bank", "regions", "fifth third", "huntington",
        "keybank", "citizens bank", "ally", "discover", "american express", "amex", "synchrony",
        "barclays", "navy federal", "usaa", "pentagon federal", "penfed", "sofi", "chime", "paypal",
        "venmo", "zelle", "fidelity", "vanguard", "charles schwab", "schwab", "e*trade", "etrade",
        "robinhood", "sallie mae", "nelnet", "mohela", "aidvantage", "navient", "rocket mortgage",
        "mr. cooper", "mr cooper", "experian", "equifax", "transunion", "frost bank",
        // Carriers, cable, utilities
        "at&t", "att", "verizon", "t-mobile", "tmobile", "sprint", "comcast", "xfinity", "spectrum",
        "charter", "cox", "optimum", "frontier", "centurylink", "lumen", "dish", "directv", "google fi",
        "mint mobile", "cricket", "boost mobile", "metro by t-mobile", "duke energy", "pg&e",
        "con edison", "coned", "southern california edison", "dominion energy", "xcel", "entergy",
        "reliant", "txu", "cps energy", "atmos", "centerpoint", "oncor", "national grid",
        // Insurance and health
        "state farm", "geico", "progressive", "allstate", "liberty mutual", "nationwide", "farmers",
        "travelers", "aetna", "cigna", "unitedhealthcare", "united healthcare", "humana",
        "blue cross", "bcbs", "anthem", "kaiser", "molina", "cvs", "caremark", "walgreens",
        "express scripts", "optum", "labcorp", "quest diagnostics",
        // Government
        "irs", "social security", "ssa", "medicare", "medicaid", "dmv", "department of motor",
        "unemployment", "twc", "va benefits", "veterans affairs", "uscis", "passport",
        // Travel, retail, tech
        "american airlines", "delta", "united airlines", "southwest", "jetblue", "spirit airlines",
        "alaska airlines", "frontier airlines", "expedia", "booking.com", "hertz", "enterprise",
        "avis", "marriott", "hilton", "amazon", "apple support", "apple care", "applecare",
        "microsoft", "google", "dell", "hp support", "best buy", "geek squad", "walmart", "target",
        "costco", "home depot", "lowe's", "lowes", "ebay", "fedex", "ups", "usps", "dhl", "uber",
        "lyft", "doordash", "instacart", "netflix", "hulu", "adt", "ring", "simplisafe", "turbotax",
        "intuit", "h&r block",
    )

    /** Brands that are also everyday first names, surnames or words: weak on their own. */
    val AMBIGUOUS_BRANDS = setOf(
        "chase", "ally", "cox", "regions", "delta", "target", "ring", "dish", "ups", "frontier",
        "discover", "progressive", "enterprise", "travelers", "farmers", "optimum", "spectrum",
        "charter", "huntington", "citizens bank", "optum", "anthem", "humana", "dell", "google",
        "amazon", "apple support", "uber", "lyft", "hilton", "marriott", "avis", "hertz", "cricket",
        "frontier airlines", "xcel", "lumen", "reliant", "intuit", "ssa", "twc", "boa", "att",
    )

    private val BRAND_RX = Regex(
        "(?<![a-z0-9])(" + BRANDS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9])",
    )

    private val INSTITUTION_WORDS = Regex(
        "\\b(bank|banking|credit union|federal credit|savings|mortgage|loans?|lending|financial|" +
            "insurance|insurer|assurance|mutual|health ?care|healthcare|health plan|pharmacy|rx|" +
            "hospital|clinic|medical|dental|dentist|orthodont|pediatric|urgent care|labs?|radiology|" +
            "billing|customer (service|care|support)|support|help ?desk|helpline|hotline|service center|" +
            "claims|member services|reservations|airlines?|airways|wireless|mobile|cable|internet|" +
            "telecom|electric|energy|power|utilit(y|ies)|water|gas company|waste|sanitation|" +
            "department|dept|office of|county|city of|state of|court|clerk|police|sheriff|" +
            "university|college|school district|admissions|financial aid|" +
            "inc|llc|corp|corporation|company|group|services|solutions|" +
            "associates|agency|center|centre|store|shop|pharmacy|dealership|motors|auto|" +
            "apartments|property management|leasing|hoa)\\b",
    )

    private val FAMILY_WORDS = Regex(
        "\\b(mom|mommy|mother|mama|dad|daddy|father|papa|grandma|grandpa|granny|nana|nanna|" +
            "aunt|auntie|uncle|cousin|wife|husband|hubby|wifey|babe|baby|honey|bae|bro|sis|" +
            "sister|brother|son|daughter|bff|in-law|stepmom|stepdad|my love)\\b",
    )

    fun classify(c: ContactInfo): ContactVerdict {
        var score = 0
        val reasons = mutableListOf<String>()
        fun add(points: Int, why: String) {
            score += points
            reasons += (if (points > 0) "+$points " else "$points ") + why
        }

        val name = c.displayName.lowercase().trim()
        val org = c.organization?.lowercase()?.trim().orEmpty()
        val hasPersonName = !c.givenName.isNullOrBlank() && !c.familyName.isNullOrBlank()
        // A person who works at Chase is filed under their own name; only judge
        // the company when it *is* the contact's name.
        val orgIsTheName = org.isNotEmpty() && (name == org || name.isEmpty() || !hasPersonName)
        val subject = if (orgIsTheName && org !in name) "$name $org" else name

        val digits = c.phones.map { it.number.filter(Char::isDigit) }
        if (digits.any { isTollFree(it) }) add(3, "toll-free number")
        if (digits.any { it.length in 3..5 && it.length == it.trimStart('0').length }) add(2, "short code number")

        val brand = BRAND_RX.find(subject)?.value
        if (brand != null && brand !in AMBIGUOUS_BRANDS) {
            add(3, "known company: $brand")
        } else {
            if (brand != null) add(1, "maybe company: $brand")
            INSTITUTION_WORDS.find(subject)?.let { add(2, "institution word: ${it.value}") }
        }

        if (orgIsTheName && !hasPersonName) add(1, "company field, no person name")
        if (c.phones.any { it.type == ContactInfo.PhoneType.MAIN || it.type == ContactInfo.PhoneType.WORK } &&
            c.phones.none { it.type == ContactInfo.PhoneType.MOBILE }
        ) add(1, "work/main number only")

        if (c.starred) add(-3, "starred")
        if (c.hasBirthday) add(-3, "has birthday")
        if (c.hasNickname || c.hasRelation) add(-2, "nickname or relation")
        FAMILY_WORDS.find(name)?.let { add(-3, "family word: ${it.value}") }
        if (hasPersonName && !orgIsTheName && score < INSTITUTION_AT) add(-1, "first and last name")
        if (c.phones.any { it.type == ContactInfo.PhoneType.MOBILE }) add(-1, "mobile number")

        val kind = when {
            score >= INSTITUTION_AT -> ContactKind.INSTITUTION
            score <= PERSONAL_AT -> ContactKind.PERSONAL
            else -> ContactKind.UNCLEAR
        }
        return ContactVerdict(kind, score, reasons)
    }

    fun isTollFree(digits: String): Boolean {
        val d = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        return d.length == 10 && d.take(3) in TOLL_FREE
    }
}
