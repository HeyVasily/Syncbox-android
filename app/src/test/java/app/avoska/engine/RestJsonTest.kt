package app.avoska.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** JSON samples are trimmed responses of Syncthing v2.1.7 (the project pin), in the shapes dev/e2e-emulator.sh exercises on the emulator. */
class RestJsonTest {
    private val phone = "2MRPVKT-ROAOMLB-MCSDFLB-IBUNWO2-FBKTC7L-CQYW6DB-7RGE6V5-GZIJUAD"
    private val peer = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun configListsDevicesAndFolders() {
        val config = parseConfig(
            json(
                """
                {"version":52,
                 "folders":[{"id":"e2e-media","label":"","path":"/data/user/0/app.avoska/files/folders/e2e-media",
                   "type":"receiveonly","devices":[{"deviceID":"$phone","introducedBy":"","encryptionPassword":""},
                   {"deviceID":"$peer","introducedBy":"","encryptionPassword":""}],"paused":false}],
                 "devices":[{"deviceID":"$phone","name":"localhost","addresses":["dynamic"]},
                   {"deviceID":"$peer","name":"e2e-computer","addresses":["dynamic"]}],
                 "gui":{"enabled":true},"options":{}}
                """,
            ),
        )
        assertEquals(listOf(ConfigDevice(phone, "localhost"), ConfigDevice(peer, "e2e-computer")), config.devices)
        // An empty label falls back to the ID, as in Syncthing's GUI.
        assertEquals(
            listOf(ConfigFolder("e2e-media", "e2e-media", "/data/user/0/app.avoska/files/folders/e2e-media", listOf(phone, peer))),
            config.folders,
        )
    }

    @Test
    fun connectionsKeepOnlyConnectedDevices() {
        val connected = parseConnectedDevices(
            json(
                """
                {"connections":{
                  "$peer":{"at":"2026-11-14T18:00:00Z","connected":true,"paused":false,"address":"10.0.0.2:22001","type":"tcp-client"},
                  "$phone":{"connected":false,"paused":false,"address":"","type":""}},
                 "total":{"inBytesTotal":1}}
                """,
            ),
        )
        assertEquals(setOf(peer), connected)
    }

    @Test
    fun pendingFoldersBecomeOneOfferPerDevice() {
        val offers = parsePendingFolders(
            json(
                """
                {"e2e-media":{"offeredBy":{"$peer":{"time":"2026-11-14T18:00:00Z","label":"E2E Media",
                   "receiveEncrypted":false,"remoteEncrypted":false}}},
                 "nolabel":{"offeredBy":{"$peer":{"time":"2026-11-14T18:00:00Z","label":""}}}}
                """,
            ),
        )
        assertEquals(listOf(FolderOffer("e2e-media", "E2E Media", peer), FolderOffer("nolabel", "nolabel", peer)), offers)
        assertEquals(emptyList<FolderOffer>(), parsePendingFolders(json("{}")))
    }

    @Test
    fun browseTreeIsFlattenedWithSizesAndTimes() {
        val tree = parseTree(
            json(
                """
                [{"name":"Series","modTime":"2026-11-14T17:00:00Z","size":128,"type":"FILE_INFO_TYPE_DIRECTORY",
                  "children":[{"name":"Episode 01.mkv","modTime":"2026-11-14T17:00:01.5Z","size":2000000,"type":"FILE_INFO_TYPE_FILE"},
                    {"name":"Empty","modTime":"2026-11-14T17:00:00Z","size":0,"type":"FILE_INFO_TYPE_DIRECTORY"}]},
                 {"name":"a [b] {c} #d !e.txt","modTime":"2026-11-14T17:00:02Z","size":1024,"type":"FILE_INFO_TYPE_FILE"},
                 {"name":"link","modTime":"2026-11-14T17:00:02Z","size":0,"type":"FILE_INFO_TYPE_SYMLINK"}]
                """,
            ),
        )
        val t = Instant.parse("2026-11-14T17:00:00Z").toEpochMilli()
        assertEquals(
            listOf(
                RemoteEntry("Series", isDirectory = true, sizeBytes = 0, modifiedMillis = t),
                RemoteEntry("Series/Episode 01.mkv", isDirectory = false, sizeBytes = 2_000_000, modifiedMillis = t + 1_500),
                RemoteEntry("Series/Empty", isDirectory = true, sizeBytes = 0, modifiedMillis = t),
                RemoteEntry("a [b] {c} #d !e.txt", isDirectory = false, sizeBytes = 1024, modifiedMillis = t + 2_000),
            ),
            tree,
        )
        assertEquals(emptyList<RemoteEntry>(), parseTree(json("[]")))
    }

    @Test
    fun dbFileTellsCurrentOutdatedAndMissingCopies() {
        fun file(name: String, version: String, size: Long, ignored: Boolean = false) =
            """{"name":"$name","size":$size,"deleted":false,"ignored":$ignored,"invalid":false,
                "version":[$version],"blocksHash":"abc"}"""
        val v1 = "\"ABCDEFG:1\""
        val v2 = "\"ABCDEFG:2\""
        assertEquals(LocalCopy.CURRENT, parseLocalCopy(json("""{"global":${file("a", v1, 5)},"local":${file("a", v1, 5)}}""")))
        assertEquals(LocalCopy.OUTDATED, parseLocalCopy(json("""{"global":${file("a", v2, 6)},"local":${file("a", v1, 5)}}""")))
        // Under `*` the local row is an ignored row without content.
        assertEquals(
            LocalCopy.NONE,
            parseLocalCopy(json("""{"global":${file("a", v1, 5)},"local":${file("a", "", 0, ignored = true)}}""")),
        )
        // No local row: Syncthing sends an empty file info.
        assertEquals(LocalCopy.NONE, parseLocalCopy(json("""{"global":${file("a", v1, 5)},"local":${file("", "", 0)}}""")))
    }

    @Test
    fun eventsGiveLastIdAndChangedFolders() {
        val batch = parseEvents(
            json(
                """
                [{"id":7,"type":"DeviceConnected","data":{"id":"$peer"}},
                 {"id":8,"type":"RemoteIndexUpdated","data":{"device":"$peer","folder":"e2e-media","items":5}},
                 {"id":9,"type":"FolderSummary","data":{"folder":"other","summary":{}}}]
                """,
            ),
            since = 6,
        )
        assertEquals(EventBatch(lastId = 9, count = 3, changedFolders = setOf("e2e-media", "other"), configChanged = false), batch)
        assertTrue(parseEvents(json("""[{"id":10,"type":"ConfigSaved","data":{}}]"""), 9).configChanged)
        assertEquals(EventBatch(9, 0, emptySet(), false), parseEvents(json("[]"), 9))
    }

    @Test
    fun acceptedFolderIsReceiveOnlyPausedAndGated() {
        val defaults = json("""{"id":"","label":"","path":"~","type":"sendreceive","devices":[],"paused":false,"minDiskFree":{"value":1,"unit":"%"},"rescanIntervalS":3600}""")
        val folder = newFolderConfig(defaults, FolderOffer("e2e-media", "E2E Media", peer), "/data/folders/e2e-media")
        assertEquals("e2e-media", folder.str("id"))
        assertEquals("E2E Media", folder.str("label"))
        assertEquals("/data/folders/e2e-media", folder.str("path"))
        assertEquals("receiveonly", folder.str("type"))
        assertEquals("true", folder["paused"]!!.jsonPrimitive.content)
        assertEquals(GATE_CLOSED, folder["minDiskFree"])
        assertEquals(peer, folder["devices"]!!.jsonArray.single().jsonObject.str("deviceID"))
        assertEquals("3600", folder["rescanIntervalS"]!!.jsonPrimitive.content) // other defaults kept
    }

    @Test
    fun newDeviceKeepsDefaultsAndSetsId() {
        val device = newDeviceConfig(json("""{"deviceID":"","name":"","addresses":["dynamic"],"compression":"metadata"}"""), peer)
        assertEquals(peer, device.str("deviceID"))
        assertEquals(JsonArray(listOf(JsonPrimitive("dynamic"))), device["addresses"])
    }

    @Test
    fun ignoringAFolderAddsItOnceToTheDevice() {
        val now = Instant.parse("2026-11-14T18:00:00Z")
        val offer = FolderOffer("e2e-media", "E2E Media", peer)
        val device = json("""{"deviceID":"$peer","ignoredFolders":[{"time":"2026-01-01T00:00:00Z","id":"old","label":"Old"}]}""")
        val patch = ignoreFolderPatch(device, offer, now)
        val ignored = patch["ignoredFolders"]!!.jsonArray.map { it.jsonObject.str("id") }
        assertEquals(listOf("old", "e2e-media"), ignored)
        val again = ignoreFolderPatch(JsonObject(device.jsonObject + patch), offer, now)
        assertEquals(listOf("old", "e2e-media"), again["ignoredFolders"]!!.jsonArray.map { it.jsonObject.str("id") })
        assertFalse(ignoreFolderPatch(json("""{"deviceID":"$peer"}"""), offer, now)["ignoredFolders"]!!.jsonArray.isEmpty())
    }

    @Test
    fun folderDevicesPatchKeepsTheDevicesAFolderHas() {
        val third = "AAAAAAA-BBBBBBB-CCCCCCC-DDDDDDD-EEEEEEE-FFFFFFF-GGGGGGG-HHHHHHH"
        val folder = json("""{"id":"e2e-media","devices":[{"deviceID":"$phone","introducedBy":""},{"deviceID":"$peer","introducedBy":""}]}""")
        val patch = folderDevicesPatch(folder, third)
        assertEquals(listOf(phone, peer, third), patch["devices"]!!.jsonArray.map { it.jsonObject.str("deviceID") })
        // The existing entries are kept whole, and a known device is not added twice.
        assertEquals(folder.jsonObject["devices"]!!.jsonArray[1], patch["devices"]!!.jsonArray[1])
        assertEquals(2, folderDevicesPatch(folder, peer)["devices"]!!.jsonArray.size)
    }

    @Test
    fun pausedFoldersAreParsed() {
        val config = parseConfig(json("""{"folders":[{"id":"a","path":"/x/a","paused":true,"devices":[]},{"id":"b","path":"/x/b"}],"devices":[]}"""))
        assertEquals(listOf(true, false), config.folders.map { it.paused })
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
