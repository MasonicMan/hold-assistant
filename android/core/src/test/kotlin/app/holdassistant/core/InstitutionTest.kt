package app.holdassistant.core

import app.holdassistant.core.ContactInfo.Phone
import app.holdassistant.core.ContactInfo.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Test

class InstitutionTest {
    private fun kind(c: ContactInfo) = InstitutionClassifier.classify(c).kind

    private fun contact(
        name: String, given: String? = null, family: String? = null, org: String? = null,
        number: String = "5125550100", type: PhoneType = PhoneType.OTHER,
        starred: Boolean = false, birthday: Boolean = false,
    ) = ContactInfo("1", name, given, family, org, null, listOf(Phone(number, type)), starred, birthday)

    @Test fun bankWithTollFree() = assertEquals(ContactKind.INSTITUTION, kind(contact("Wells Fargo", number = "1-800-869-3557")))

    @Test fun knownBrandAlone() = assertEquals(ContactKind.INSTITUTION, kind(contact("Verizon")))

    @Test fun chaseBankParsedAsPersonName() =
        assertEquals(ContactKind.INSTITUTION, kind(contact("Chase Bank", given = "Chase", family = "Bank")))

    @Test fun friendNamedChase() =
        assertEquals(ContactKind.PERSONAL, kind(contact("Chase Miller", "Chase", "Miller", type = PhoneType.MOBILE)))

    @Test fun personWhoWorksAtABank() = assertEquals(
        ContactKind.PERSONAL,
        kind(contact("Dana Lopez", "Dana", "Lopez", org = "Chase Bank", type = PhoneType.MOBILE)),
    )

    @Test fun companyFieldOnly() = assertEquals(
        ContactKind.INSTITUTION,
        kind(contact("Austin Energy", org = "Austin Energy", type = PhoneType.MAIN)),
    )

    @Test fun genericInstitutionWords() {
        assertEquals(ContactKind.INSTITUTION, kind(contact("Travis County Tax Office", type = PhoneType.WORK)))
        assertEquals(ContactKind.INSTITUTION, kind(contact("Dr Patel Dental Clinic", number = "18885550100")))
    }

    @Test fun familyAndStarred() {
        assertEquals(ContactKind.PERSONAL, kind(contact("Mom", type = PhoneType.MOBILE)))
        assertEquals(ContactKind.PERSONAL, kind(contact("Jordan", starred = true)))
    }

    @Test fun carrierShortCode() = assertEquals(ContactKind.INSTITUTION, kind(contact("T-Mobile Support", number = "611")))

    @Test fun singleFirstNameIsUnclearOrPersonal() {
        val k = kind(contact("Ally", given = "Ally", type = PhoneType.MOBILE))
        assertEquals(true, k == ContactKind.PERSONAL || k == ContactKind.UNCLEAR)
    }

    @Test fun tollFreeDetection() {
        assertEquals(true, InstitutionClassifier.isTollFree("18005551212"))
        assertEquals(true, InstitutionClassifier.isTollFree("8335551212"))
        assertEquals(false, InstitutionClassifier.isTollFree("5125551212"))
    }
}
