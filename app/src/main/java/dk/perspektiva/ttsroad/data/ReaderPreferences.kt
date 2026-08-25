package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Reader preferences, in their own DataStore alongside the playback one.
 *
 * Issue #32 asks for these to live in **server-side account preferences** so the phone and the web
 * reader agree. The server has no preferences endpoint — there is none in `build.md` and none
 * implemented — so this is the local stand-in. The reader depends on [ReaderPreferenceStore], not
 * on this class, so when the backend grows that endpoint the change is a different implementation
 * of the same three setters, not a change to the reader.
 */
private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_reader",
)

data class ReaderPrefs(
    val fontScale: Float = DefaultReaderFontScale,
    /**
     * Line spacing as a multiple of the font size, matching the server's `reader_line_height`.
     *
     * Stored as the server's own multiplier rather than as a scale on top of a local default: it is
     * the one reader key whose vocabulary the two ends already share exactly, so converting through
     * a fixed point — as [fontScale] has to — would only introduce rounding.
     */
    val lineHeight: Float = DefaultReaderLineHeight,
    val theme: ReaderTheme = DefaultReaderTheme,
    val highlight: HighlightGranularity = DefaultHighlightGranularity,
    val keepScreenOn: Boolean = true,
)

/**
 * How much to scale the reader's text *on top of* the system font scale, which Compose's `sp`
 * already applies. This is the reader-specific adjustment, not a replacement for accessibility
 * settings.
 */
val ReaderFontScales: List<Float> = listOf(0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)

const val DefaultReaderFontScale: Float = 1.0f

/**
 * Line-height steps offered in the reader sheet, spanning the server's declared 1.3–2.4 range.
 *
 * Coarser than the web's stepper, which moves in 0.05: a phone screen shows a few hundred words at
 * a time and the difference between 1.75 and 1.80 is not visible on one, while the difference
 * between tight and airy very much is.
 */
val ReaderLineHeights: List<Float> = listOf(1.3f, 1.5f, 1.75f, 2.0f, 2.4f)

/** The server's declared default for `reader_line_height`. */
const val DefaultReaderLineHeight: Float = 1.75f

const val MinReaderLineHeight: Float = 1.3f
const val MaxReaderLineHeight: Float = 2.4f

/** Reader background and text colours. Mapped to actual colours in the ui layer. */
enum class ReaderTheme(val label: String) {
    /** The app's own AARIS dark. What a fresh install reads with. */
    Console(label = "Console"),

    /** Warm light, for reading in daylight where the dark theme washes out. */
    Paper(label = "Paper"),

    /** Near-black with dimmed text, for reading next to someone who is asleep. */
    Night(label = "Night"),
}

val DefaultReaderTheme: ReaderTheme = ReaderTheme.Console

/**
 * What the reader draws as playback moves.
 *
 * [SentenceAndWord] is the default because a lone moving word is genuinely unreadable at the
 * 1.5x-2x this app is used at — the band is what holds the place, and the word accent only says
 * where inside it. The other modes exist because that is a matter of taste, not of fact.
 */
enum class HighlightGranularity(
    val label: String,
    val showsSentence: Boolean,
    val showsWord: Boolean,
) {
    SentenceAndWord(label = "Sentence + word", showsSentence = true, showsWord = true),
    SentenceOnly(label = "Sentence", showsSentence = true, showsWord = false),
    WordOnly(label = "Word", showsSentence = false, showsWord = true),
    Off(label = "Off", showsSentence = false, showsWord = false),
}

val DefaultHighlightGranularity: HighlightGranularity = HighlightGranularity.SentenceAndWord

/** Clamped rather than snapped, so a value from a build with finer steps survives a downgrade. */
fun sanitizeReaderFontScale(scale: Float): Float =
    if (scale.isNaN()) DefaultReaderFontScale else scale.coerceIn(MinReaderFontScale, MaxReaderFontScale)

/**
 * Clamped to the range the server declares, so a value set by a build with finer steps survives.
 *
 * The server's spec for this key is `on_invalid="default"` rather than "reject" — the reader has to
 * render *something* — so an unreadable value becomes the default rather than being dropped.
 */
fun sanitizeReaderLineHeight(height: Float): Float =
    if (height.isNaN()) {
        DefaultReaderLineHeight
    } else {
        height.coerceIn(MinReaderLineHeight, MaxReaderLineHeight)
    }

/** "1.75x" — the label on the reader sheet's spacing chips. */
fun formatReaderLineHeight(height: Float): String = "${(Math.round(height * 100) / 100.0)}x"

/** Tolerate a name written by a different build rather than failing to read preferences at all. */
fun readerThemeOf(storedName: String?): ReaderTheme =
    ReaderTheme.entries.firstOrNull { it.name == storedName } ?: DefaultReaderTheme

fun highlightGranularityOf(storedName: String?): HighlightGranularity =
    HighlightGranularity.entries.firstOrNull { it.name == storedName } ?: DefaultHighlightGranularity

/** "120%" — the label on the Settings row and in the picker. */
fun formatReaderFontScale(scale: Float): String = "${(scale * 100).roundToInt()}%"

private const val MinReaderFontScale = 0.8f
private const val MaxReaderFontScale = 2.4f

/**
 * Where reader preferences are kept.
 *
 * Deliberately narrow: three setters and a stream. That is the whole surface a server-backed
 * implementation would have to satisfy once the backend has somewhere to put them.
 */
interface ReaderPreferenceStore {
    val prefs: Flow<ReaderPrefs>
    suspend fun current(): ReaderPrefs
    suspend fun setFontScale(scale: Float)
    suspend fun setLineHeight(height: Float)
    suspend fun setTheme(theme: ReaderTheme)
    suspend fun setHighlight(granularity: HighlightGranularity)
}

/** The local implementation: a DataStore on this phone, matching [PlaybackPreferences]. */
class LocalReaderPreferences(private val context: Context) : ReaderPreferenceStore {
    private object Keys {
        val FontScale = floatPreferencesKey("reader_font_scale")
        val LineHeight = floatPreferencesKey("reader_line_height")
        val Theme = stringPreferencesKey("reader_theme")
        val Highlight = stringPreferencesKey("reader_highlight")
        val KeepScreenOn = booleanPreferencesKey("reader_keep_screen_on")
    }

    override val prefs: Flow<ReaderPrefs> = context.readerDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            ReaderPrefs(
                fontScale = sanitizeReaderFontScale(stored[Keys.FontScale] ?: DefaultReaderFontScale),
                lineHeight = sanitizeReaderLineHeight(
                    stored[Keys.LineHeight] ?: DefaultReaderLineHeight,
                ),
                theme = readerThemeOf(stored[Keys.Theme]),
                highlight = highlightGranularityOf(stored[Keys.Highlight]),
                keepScreenOn = stored[Keys.KeepScreenOn] ?: true,
            )
        }

    override suspend fun current(): ReaderPrefs = prefs.first()

    override suspend fun setFontScale(scale: Float) {
        context.readerDataStore.edit { it[Keys.FontScale] = sanitizeReaderFontScale(scale) }
    }

    override suspend fun setLineHeight(height: Float) {
        context.readerDataStore.edit { it[Keys.LineHeight] = sanitizeReaderLineHeight(height) }
    }

    override suspend fun setTheme(theme: ReaderTheme) {
        context.readerDataStore.edit { it[Keys.Theme] = theme.name }
    }

    override suspend fun setHighlight(granularity: HighlightGranularity) {
        context.readerDataStore.edit { it[Keys.Highlight] = granularity.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.readerDataStore.edit { it[Keys.KeepScreenOn] = enabled }
    }
}

/**
 * A [ReaderPreferenceStore] held in memory.
 *
 * Exists so the seam above is more than an assertion in a comment: it is the shape a server-backed
 * store would take, minus the network, and it is what Compose previews and tests read from.
 */
class InMemoryReaderPreferences(initial: ReaderPrefs = ReaderPrefs()) : ReaderPreferenceStore {
    private val state = MutableStateFlow(initial)

    override val prefs: Flow<ReaderPrefs> = state.asStateFlow()

    override suspend fun current(): ReaderPrefs = state.value

    override suspend fun setFontScale(scale: Float) {
        state.value = state.value.copy(fontScale = sanitizeReaderFontScale(scale))
    }

    override suspend fun setLineHeight(height: Float) {
        state.value = state.value.copy(lineHeight = sanitizeReaderLineHeight(height))
    }

    override suspend fun setTheme(theme: ReaderTheme) {
        state.value = state.value.copy(theme = theme)
    }

    override suspend fun setHighlight(granularity: HighlightGranularity) {
        state.value = state.value.copy(highlight = granularity)
    }
}
