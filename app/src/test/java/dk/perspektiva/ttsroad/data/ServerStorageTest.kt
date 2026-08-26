package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arranging the storage payload for the card that sits beside the phone's own cache figures (#124).
 *
 * The volume bar is the only number derived here rather than rendered server-side, and it has to be
 * — "931 GB" and "233 GB" are strings, and a share of a disk is a division. Everything else is a
 * label the server already wrote, which is why most of what is worth asserting is about the bar and
 * about which rows are shown.
 */
class ServerStorageTest {

    private fun response(
        volumeTotal: Long = 1_000_000_000_000L,
        volumeFree: Long = 250_000_000_000L,
        ffmpeg: Boolean = true,
        perFiction: List<FictionStorageRow> = emptyList(),
    ) = ServerStorageResponse(
        volumeTotalBytes = volumeTotal,
        volumeTotalLabel = "931 GB",
        volumeFreeBytes = volumeFree,
        volumeFreeLabel = "233 GB",
        ffmpegAvailable = ffmpeg,
        perFiction = perFiction,
    )

    private fun row(id: Int, audio: Long, excluded: Long = 0L) = FictionStorageRow(
        id = id,
        title = "Fiction $id",
        slug = "fiction-$id",
        audioBytes = audio,
        audioLabel = "$audio B",
        excludedBytes = excluded,
        excludedLabel = "$excluded B",
    )

    @Test
    fun `the bar is the used share of the volume`() {
        assertEquals(0.75f, volumeUsedFraction(response()), 0.001f)
    }

    /**
     * A server reporting a zero-byte volume is reporting something this client cannot draw. Empty
     * is the safer of the two lies: an accidental full bar reads as "the disk is about to fill",
     * which is the one message on this card someone would act on.
     */
    @Test
    fun `a volume the server could not measure reads as empty, not as full`() {
        assertEquals(0f, volumeUsedFraction(response(volumeTotal = 0L, volumeFree = 0L)), 0.001f)
    }

    @Test
    fun `free space larger than the volume cannot push the bar out of range`() {
        val impossible = response(volumeTotal = 100L, volumeFree = 500L)

        assertEquals(0f, volumeUsedFraction(impossible), 0.001f)
    }

    @Test
    fun `the server's ordering is kept — largest first is what the table is for`() {
        val overview = serverStorageOverview(
            response(perFiction = listOf(row(1, 900L), row(2, 500L), row(3, 100L))),
        )

        assertEquals(listOf(1, 2, 3), overview!!.rows.map { it.id })
    }

    @Test
    fun `fictions holding no audio are counted, not listed`() {
        val overview = serverStorageOverview(
            response(perFiction = listOf(row(1, 900L), row(2, 0L), row(3, 0L))),
        )

        assertEquals(listOf(1), overview!!.rows.map { it.id })
        assertEquals(2, overview.emptyFictions)
    }

    @Test
    fun `nothing to show yet is null rather than an empty overview`() {
        // "Not loaded" and "a server with no fictions" are different states and the card says
        // different things about them.
        assertNull(serverStorageOverview(null))
    }

    /**
     * `audiobook_export` says the route exists; `ffmpeg_available` says the machine behind it has
     * the tool. Without this line a missing encoder first shows up as a failure after someone has
     * already chosen a fiction and asked for an export.
     */
    @Test
    fun `a server without ffmpeg explains itself where exports are counted`() {
        assertNotNull(serverStorageEncoderNote(response(ffmpeg = false)))
        assertNull(serverStorageEncoderNote(response(ffmpeg = true)))
        assertNull(serverStorageEncoderNote(null))
    }

    @Test
    fun `disk usage needs the capability and an admin account`() {
        val supported = ServerCapabilities(storage = true)

        assertTrue(canReadServerStorage(supported, isAdmin = true))
        assertFalse(canReadServerStorage(supported, isAdmin = false))
        assertFalse(canReadServerStorage(ServerCapabilities(storage = false), isAdmin = true))
    }

    /**
     * The two read-outs are advertised separately on purpose: a deployment may reasonably expose one
     * and not the other, and nothing about seeing the log implies seeing the volume's free space.
     */
    @Test
    fun `logs and storage are gated independently`() {
        val logsOnly = ServerCapabilities(logs = true)

        assertTrue(canReadServerLogs(logsOnly, isAdmin = true))
        assertFalse(canReadServerStorage(logsOnly, isAdmin = true))
    }
}
