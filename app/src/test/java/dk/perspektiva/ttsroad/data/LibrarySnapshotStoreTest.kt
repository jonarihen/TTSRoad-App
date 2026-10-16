package dk.perspektiva.ttsroad.data

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** #327: the library survives a process with no network, for the account that saved it only. */
class LibrarySnapshotStoreTest {
    private val dir = Files.createTempDirectory("library-snapshot").toFile()
    private val file = File(dir, LibrarySnapshotStore.FileName)
    private val store = LibrarySnapshotStore(file)

    private val library = LibraryResponse(
        followingIds = listOf(3),
        fictions = listOf(FictionSummary(id = 3, title = "Ashes of Aether", author = "Someone")),
        continueListening = listOf(
            ChapterSummary(
                id = 42,
                fictionId = 3,
                title = "Chapter 212",
                audio = AudioInfo(url = "https://server.example.com/audio/42.mp3"),
                playback = PlaybackInfo(positionSeconds = 1_740.0),
            ),
        ),
        serverTime = "2026-10-16T07:00:00Z",
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved library reads back whole for its own account`() {
        store.write("account-a", library, savedAt = 1_000L)

        val stored = store.read("account-a")

        assertEquals(1_000L, stored?.savedAt)
        assertEquals(library, stored?.library)
        // The snake_case fields the wire uses must survive the round trip, not just the defaults.
        assertEquals(42, stored?.library?.continueListening?.single()?.resolvedChapterId)
        assertEquals(1_740.0, stored?.library?.continueListening?.single()?.resolvedPositionSeconds ?: 0.0, 0.0)
    }

    @Test
    fun `another account never sees it`() {
        store.write("account-a", library)

        assertNull(store.read("account-b"))
        assertNull(store.read(null))
    }

    @Test
    fun `signed out writes nothing`() {
        store.write(null, library)

        assertFalse(file.exists())
    }

    @Test
    fun `clear deletes it`() {
        store.write("account-a", library)
        store.clear()

        assertNull(store.read("account-a"))
        assertFalse(file.exists())
    }

    @Test
    fun `a damaged file reads as nothing rather than throwing`() {
        file.writeText("{\"accountKey\":\"account-a\",\"library\":")

        assertNull(store.read("account-a"))
    }

    @Test
    fun `the notice says how old the copy is and why`() {
        val zone = ZoneOffset.UTC
        val savedAt = 1_760_598_720_000L // 2025-10-16 07:12 UTC
        assertEquals(
            "Saved copy from 07:12 — Could not reach the server",
            storedLibraryNotice(savedAt, "Could not reach the server", now = savedAt + 3_600_000L, zone = zone, locale = Locale.UK),
        )
        assertEquals(
            "Saved copy from 16 Oct, 07:12",
            storedLibraryNotice(savedAt, null, now = savedAt + 2 * 86_400_000L, zone = zone, locale = Locale.UK),
        )
    }
}
