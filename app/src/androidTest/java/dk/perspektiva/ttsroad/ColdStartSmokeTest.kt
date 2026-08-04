package dk.perspektiva.ttsroad

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.player.HistorySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gate that has to exist before R8 can be turned back on.
 *
 * 0.7.0 was the first minified release. It crashed on launch because R8 renamed a model Moshi
 * reflects on while the app was starting — a failure that is invisible in `debug`, invisible to
 * `./gradlew test`, and only appears in a shrunk build on a real device.
 *
 * **Run it against the minified build or it proves nothing:**
 *
 * ```
 * ./gradlew connectedAndroidTest -PttsroadTestBuildType=release
 * ```
 *
 * Without that property `testBuildType` is `debug`, which is not minified — the tests still pass,
 * but they are not testing the thing this file exists for. Needs a connected device and the pinned
 * `debug.keystore`, which is why it is a local step rather than a CI job.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartSmokeTest {

    @Test
    fun theAppColdStartsAndReachesResumed() {
        // The whole 0.7.0 failure was here: Application.onCreate -> ServiceLocator.init -> the
        // token store and its DataStore -> Compose -> first screen. If R8 has broken any of it,
        // the process dies before RESUMED and this fails.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun theAppSurvivesARecreate() {
        // A rotate or a theme change tears the activity down and rebuilds it against the same
        // process-wide singletons. Cheap to check, and it exercises the ServiceLocator returning
        // existing instances rather than the first-run path only.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun reflectivelySerialisedModelsKeepTheirFieldNames() {
        // The specific 0.7.0 shape. KotlinJsonAdapterFactory reads the Kotlin metadata at runtime,
        // so an R8 rename does not fail to compile and does not fail in debug — it silently
        // produces the wrong JSON keys, or throws while parsing a login response.
        //
        // Asserting the *wire names* rather than just "it round-trips" is the point: a renamed
        // property still round-trips happily against itself while no longer matching the server.
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val json = moshi.adapter(LoginResponse::class.java).toJson(
            LoginResponse(
                token = "t",
                tokenType = "bearer",
                deviceId = 7,
                expiresAt = "2026-11-02T00:00:00Z",
                user = MobileUser(id = 1, username = "someone"),
            ),
        )

        val parsed = JSONObject(json)
        assertTrue("token_type missing from $json", parsed.has("token_type"))
        assertTrue("device_id missing from $json", parsed.has("device_id"))
        assertTrue("expires_at missing from $json", parsed.has("expires_at"))
        assertEquals("bearer", parsed.getString("token_type"))
        assertEquals(7, parsed.getInt("device_id"))
    }

    @Test
    fun theHistorySnapshotModelOutsideTheDataPackageStillSerialises() {
        // HistorySnapshot lives in `player`, not `data`, so it is not covered by the blanket
        // `-keep class dk.perspektiva.ttsroad.data.**` rule — it has its own line in
        // proguard-rules.pro. This is the assertion that notices if that line is ever dropped:
        // jump-back would then quietly stop restoring across restarts, with nothing else failing.
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(HistorySnapshot::class.java)

        val restored = adapter.fromJson(adapter.toJson(HISTORY_SAMPLE))

        assertNotNull(restored)
        assertEquals(HISTORY_SAMPLE, restored)
    }

    private companion object {
        val HISTORY_SAMPLE = HistorySnapshot(
            timestamp = 1_762_000_000_000L,
            mediaId = "chapter:12",
            fictionId = 7,
            chapterId = 12,
            title = "Chapter 12",
            fictionTitle = "A Fiction",
            positionMs = 42_500L,
        )
    }
}
