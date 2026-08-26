package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The defensive wire contract and the phone-sized presentation rules for the voice catalogue. */
class VoiceCatalogueTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(VoicesResponse::class.java)

    @Test
    fun `the mobile voice payload decodes the short name used by fiction updates`() {
        val response = adapter.fromJson(
            """
            {
              "api_version": 1,
              "voices": [
                {"name": "en-US-BrianNeural", "locale": "en-US", "gender": "Male"}
              ]
            }
            """.trimIndent(),
        )

        assertNotNull(response)
        assertEquals("en-US-BrianNeural", response?.voices?.single()?.name)
        assertEquals("en-US", response?.voices?.single()?.locale)
        assertEquals("Male", response?.voices?.single()?.gender)
    }

    @Test
    fun `an older or sparse response still decodes`() {
        val absent = adapter.fromJson("{}")
        val sparse = adapter.fromJson("""{"voices":[{"name":"en-US-AvaNeural"}]}""")

        assertEquals(emptyList<MobileVoice>(), absent?.voices)
        assertNull(sparse?.voices?.single()?.locale)
        assertNull(sparse?.voices?.single()?.gender)
    }

    @Test
    fun `the capability and admin role are both required for the picker`() {
        val advertised = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("voice_catalogue" to true)),
        )

        assertTrue(advertised.voiceCatalogue)
        assertTrue(canPickVoice(advertised, isAdmin = true))
        assertFalse(canPickVoice(advertised, isAdmin = false))
        assertFalse(canPickVoice(ServerCapabilities.Baseline, isAdmin = true))
    }

    @Test
    fun `voices are grouped by locale with the current narrator first`() {
        val groups = voiceGroups(
            voices = listOf(
                MobileVoice("da-DK-ChristelNeural", "da-DK", "Female"),
                MobileVoice("en-US-GuyNeural", "en-US", "Male"),
                MobileVoice("en-GB-SoniaNeural", "en-GB", "Female"),
                MobileVoice("en-GB-RyanNeural", "en-GB", "Male"),
            ),
            current = "en-GB-RyanNeural",
            locale = Locale.forLanguageTag("da-DK"),
        )

        assertEquals(listOf("en-GB", "da-DK", "en-US"), groups.map { it.locale })
        assertEquals(listOf("Ryan", "Sonia"), groups.first().voices.map { it.shortName })
        assertEquals("Male  ·  en-GB-RyanNeural", groups.first().voices.first().detail)
        assertEquals("en-GB", initiallyExpandedVoiceLocale(groups, "en-GB-RyanNeural"))
    }

    @Test
    fun `search matches names locale labels and gender without flattening the groups`() {
        val voices = listOf(
            MobileVoice("en-GB-SoniaNeural", "en-GB", "Female"),
            MobileVoice("de-DE-KatjaNeural", "de-DE", "Female"),
        )

        assertEquals("en-GB-SoniaNeural", voiceGroups(voices, query = "sonia").single().voices.single().name)
        assertEquals("en-GB", voiceGroups(voices, query = "united kingdom", locale = Locale.US).single().locale)
        assertEquals(2, voiceGroups(voices, query = "female").size)
        assertTrue(voiceGroups(voices, query = "brian").isEmpty())
    }

    @Test
    fun `a voice without a locale stays selectable and a voice without a name is dropped`() {
        val groups = voiceGroups(
            listOf(
                MobileVoice(name = "CustomNarrator", locale = null),
                MobileVoice(name = "", locale = "en-US"),
            ),
            locale = Locale.US,
        )

        assertEquals(UnknownVoiceLocale, groups.single().locale)
        assertEquals("Other", groups.single().label)
        assertEquals("CustomNarrator", groups.single().voices.single().name)
    }

    @Test
    fun `short voice names respect locale prefixes longer than language and region`() {
        assertEquals("Brian", shortVoiceName("en-US-BrianNeural", "en-US"))
        assertEquals(
            "Xiaobei",
            shortVoiceName("zh-CN-liaoning-XiaobeiNeural", "zh-CN-liaoning"),
        )
    }

    @Test
    fun `rates are normalised before they can reach a future conversion`() {
        assertEquals("+0%", normaliseVoiceRate("0"))
        assertEquals("+25%", normaliseVoiceRate(" +25% "))
        assertEquals("-10%", normaliseVoiceRate("-10"))
        assertEquals("+0%", normaliseVoiceRate("-0%"))
        assertNull(normaliseVoiceRate("fast"))
        assertNull(normaliseVoiceRate("+1000%"))
        assertNotNull(voiceRateProblem("ten"))
        assertNull(voiceRateProblem(""))
    }

    @Test
    fun `only changed narration fields are merged into the metadata patch`() {
        val fiction = FictionSummary(
            id = 7,
            title = "Ashfall",
            voice = "en-GB-RyanNeural",
            rate = "+0%",
        )
        val narration = fictionNarrationPatch(fiction, "en-US-BrianNeural", "15")
        val combined = withNarration(FictionUpdateRequest(title = "Ashfall: Book One"), narration)

        assertEquals("Ashfall: Book One", combined?.title)
        assertEquals("en-US-BrianNeural", combined?.voice)
        assertEquals("+15%", combined?.rate)
        assertNull(fictionNarrationPatch(fiction, "en-GB-RyanNeural", "+0%"))
    }

    @Test
    fun `the consequence says existing audio is not re-narrated`() {
        val fiction = FictionSummary(
            id = 7,
            voice = "en-GB-RyanNeural",
            rate = "+0%",
            doneChapters = 42,
        )

        val note = voiceChangeConsequence(fiction, "en-US-BrianNeural", "+0%")

        assertTrue(note.orEmpty().contains("42 chapters already converted"))
        assertTrue(note.orEmpty().contains("nothing is re-narrated"))
        assertTrue(note.orEmpty().contains("Re-narrate every chapter"))
        assertNull(voiceChangeConsequence(fiction, fiction.voice, fiction.rate))
    }
}
