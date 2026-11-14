package app.avoska.engine

const val MB: Long = 1_000_000L
const val GB: Long = 1_000_000_000L

/** Demo content matching the design mockups. Names, sizes and IDs are examples. */
object DemoData {
    const val FOLDER_ID = "movies"
    const val FOLDER_LABEL = "Movies"

    /** The folder a newly added demo device offers. */
    const val OFFERED_FOLDER_ID = "photos"
    const val OFFERED_FOLDER_LABEL = "Photos"
    const val NEW_DEVICE_NAME = "Office PC"

    /** Valid Syncthing device IDs (Luhn check characters included). */
    const val THIS_DEVICE_ID = "2MRPVKT-ROAOMLB-MCSDFLB-IBUNWO2-FBKTC7L-CQYW6DB-7RGE6V5-GZIJUAD"

    /** The ID the demo "Scan QR code" action fills in: a device that is not added yet. */
    const val SCANNED_DEVICE_ID = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"

    private const val DAY = 86_400_000L
    private const val NOW = 1_794_960_000_000L // 2026-11-17, fixed so the demo is reproducible

    private fun file(path: String, size: Long, daysAgo: Int, keep: Boolean, local: LocalCopy = LocalCopy.NONE) = DemoFile(
        ref = FileRef(FOLDER_ID, path),
        sizeBytes = size,
        modifiedMillis = NOW - daysAgo * DAY,
        kind = if (path.endsWith(".pdf")) FileKind.DOCUMENT else FileKind.VIDEO,
        keep = keep,
        local = local,
    )

    fun files(): List<DemoFile> {
        val on = LocalCopy.CURRENT
        val episodes = listOf(1_800, 1_700, 1_900, 1_800, 1_700, 1_800, 1_600, 1_900).mapIndexed { i, mb ->
            val number = (i + 1).toString().padStart(2, '0')
            file("Series/Episode $number.mkv", mb * MB, daysAgo = 40 - i, keep = true, local = on)
        }
        val courses = listOf(
            file("Courses/Kotlin basics.mp4", 2_900 * MB, daysAgo = 120, keep = false),
            file("Courses/Compose layouts.mp4", 3_100 * MB, daysAgo = 118, keep = false),
            file("Courses/Workshop recording.mkv", 2_400 * MB, daysAgo = 117, keep = false),
        )
        return episodes + courses + listOf(
            file("Altai trip 2024.mp4", 3_100 * MB, daysAgo = 300, keep = true, local = on),
            file("Family archive 1998.mkv", 5_600 * MB, daysAgo = 3, keep = true),
            file("Conference talk.mp4", 1_200 * MB, daysAgo = 1, keep = true, local = LocalCopy.OUTDATED),
            file("Drone footage raw.mov", 12_800 * MB, daysAgo = 14, keep = false),
            file("Notes.pdf", 2 * MB, daysAgo = 7, keep = true, local = on),
        )
    }

    fun initialState() = DemoState(
        thisDeviceId = THIS_DEVICE_ID,
        files = files().associateBy { it.ref },
        devices = listOf(
            Device("N6YPKZD-N3NQJ6R-IWJQYBW-USK6JHL-OKMGC4S-OH3CBGV-W5A3P3K-OGEJMQN", "Home PC", connected = true),
            Device("L3WA3RA-ZVKBTPX-P3SL4BG-7WU4PDT-A4WHDEF-3VP7HLR-OCEJPG5-4D5FBAC", "Laptop", connected = true),
            Device("AR66B5K-DY7RVSR-25W3PIO-MXZMGAX-2XOIFGH-3FEEPYD-IYUX24M-KPNADAC", "NAS", connected = false),
        ),
        folders = listOf(
            SharedFolder(FOLDER_ID, FOLDER_LABEL, deviceCount = 3),
            SharedFolder("books", "Books", deviceCount = 1),
        ),
        offers = emptyList(),
        connecting = true,
        offline = false,
        active = null,
        suspended = null,
        partial = emptyMap(),
        failure = null,
        freeBytes = 41 * GB,
    )
}
