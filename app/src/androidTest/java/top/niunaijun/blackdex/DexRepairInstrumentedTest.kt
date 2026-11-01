package top.niunaijun.blackdex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.utils.DexUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.zip.Adler32
import java.util.zip.ZipFile

/**
 * Exercises [DexUtils.fixDex]: header repair must be correct and idempotent, must leave the
 * payload alone, and must never damage input that is not a dex.
 */
@RunWith(AndroidJUnit4::class)
class DexRepairInstrumentedTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(ctx.cacheDir, "dex-repair-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun repairsSizeChecksumAndSignatureOfMinimalDex() {
        val valid = buildMinimalDex()
        assertHeaderConsistent(valid) // sanity-check the test fixture itself

        val broken = corruptHeader(valid)
        val file = write("min.dex", broken)

        DexUtils.fixDex(file)

        val fixed = file.readBytes()
        assertEquals(valid.size, fixed.size)
        assertHeaderConsistent(fixed)
        assertPayloadUnchanged(broken, fixed)
        assertArrayEquals(valid, fixed)
        assertNoStrayFiles("min.dex")
    }

    @Test
    fun repairsRealDexFromTestApk() {
        val real = loadRealDex()
        assumeTrue("no classes.dex found in test APK", real != null)
        real!!
        assertHeaderConsistent(real)

        val broken = corruptHeader(real)
        val file = write("real.dex", broken)

        DexUtils.fixDex(file)

        val fixed = file.readBytes()
        assertHeaderConsistent(fixed)
        assertPayloadUnchanged(broken, fixed)
        assertArrayEquals(real, fixed)
        assertNoStrayFiles("real.dex")
    }

    @Test
    fun isIdempotent() {
        val file = write("idem.dex", corruptHeader(buildMinimalDex()))

        DexUtils.fixDex(file)
        val once = file.readBytes()
        val mtime = file.lastModified()
        DexUtils.fixDex(file)
        val twice = file.readBytes()

        assertArrayEquals(once, twice)
        assertHeaderConsistent(twice)
        // Already-correct input is not rewritten at all.
        assertEquals(mtime, file.lastModified())
        assertNoStrayFiles("idem.dex")
    }

    @Test
    fun truncatedDexIsLeftUntouched() {
        val truncated = buildMinimalDex().copyOf(0x40) // shorter than the 0x70 header
        assertUntouched("truncated.dex", truncated)
    }

    @Test
    fun garbageIsLeftUntouched() {
        val garbage = ByteArray(4096).also { Random(42).nextBytes(it) }
        assertUntouched("garbage.dex", garbage)
    }

    @Test
    fun badMagicIsLeftUntouched() {
        val notDex = corruptHeader(buildMinimalDex()).also {
            it[0] = 'z'.code.toByte()
        }
        assertUntouched("badmagic.dex", notDex)
        File(dir, "badmagic.dex").delete()

        val badVersion = corruptHeader(buildMinimalDex()).also {
            it[7] = 'x'.code.toByte() // missing NUL terminator
        }
        assertUntouched("badversion.dex", badVersion)
    }

    @Test
    fun emptyFileIsLeftUntouched() {
        assertUntouched("empty.dex", ByteArray(0))
    }

    @Test
    fun missingFileAndNullAreNoOps() {
        val missing = File(dir, "missing.dex")
        DexUtils.fixDex(missing)
        assertFalse(missing.exists())
        DexUtils.fixDex(null)
        assertNoStrayFiles()
    }

    // ---------------------------------------------------------------- helpers

    private fun assertUntouched(name: String, content: ByteArray) {
        val file = write(name, content)
        DexUtils.fixDex(file)
        assertTrue("$name must still exist", file.exists())
        assertArrayEquals("$name must be byte-for-byte unchanged", content, file.readBytes())
        assertNoStrayFiles(name)
    }

    private fun write(name: String, content: ByteArray): File =
        File(dir, name).also { it.writeBytes(content) }

    /** Only the given files may exist in the test dir (no _fix.dex / .bak / .new leftovers). */
    private fun assertNoStrayFiles(vararg expected: String) {
        val present = dir.list()?.sorted() ?: emptyList()
        assertEquals(expected.sorted(), present)
    }

    /** Zero out checksum/signature and write a bogus file_size, as a damaged dump would have. */
    private fun corruptHeader(dex: ByteArray): ByteArray {
        val b = dex.copyOf()
        for (i in 8 until 32) b[i] = 0
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20, 0x7fff_0000)
        return b
    }

    /** Everything except checksum (8..12), signature (12..32) and file_size (32..36). */
    private fun assertPayloadUnchanged(before: ByteArray, after: ByteArray) {
        assertEquals(before.size, after.size)
        assertArrayEquals(before.copyOfRange(0, 8), after.copyOfRange(0, 8))
        assertArrayEquals(
            before.copyOfRange(36, before.size),
            after.copyOfRange(36, after.size)
        )
    }

    /** Independently verifies file_size, adler32 checksum and SHA-1 signature. */
    private fun assertHeaderConsistent(b: ByteArray) {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("file_size", b.size, bb.getInt(0x20))

        val sha1 = MessageDigest.getInstance("SHA-1").apply { update(b, 32, b.size - 32) }.digest()
        assertArrayEquals("signature", sha1, b.copyOfRange(12, 32))

        val adler = Adler32().apply { update(b, 12, b.size - 12) }.value.toInt()
        assertEquals("checksum", adler, bb.getInt(8))
    }

    /** classes.dex of the instrumentation APK: a real, toolchain-produced dex. */
    private fun loadRealDex(): ByteArray? {
        val apk = InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("classes.dex") ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    /**
     * A structurally valid, empty dex (v035): header + map_list containing the header item and
     * the map list itself. Checksum, signature and file_size are computed correctly.
     */
    private fun buildMinimalDex(): ByteArray {
        val headerSize = 0x70
        val mapOff = headerSize
        val mapSize = 4 + 2 * 12
        val total = headerSize + mapSize
        val b = ByteArray(total)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        "dex\n035\u0000".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        bb.putInt(0x20, total)          // file_size
        bb.putInt(0x24, headerSize)     // header_size
        bb.putInt(0x28, 0x12345678)     // endian_tag
        bb.putInt(0x34, mapOff)         // map_off
        bb.putInt(0x68, mapSize)        // data_size
        bb.putInt(0x6c, mapOff)         // data_off
        // map_list
        bb.putInt(mapOff, 2)
        bb.putShort(mapOff + 4, 0x0000)        // TYPE_HEADER_ITEM
        bb.putInt(mapOff + 8, 1)
        bb.putInt(mapOff + 12, 0)
        bb.putShort(mapOff + 16, 0x1000)       // TYPE_MAP_LIST
        bb.putInt(mapOff + 20, 1)
        bb.putInt(mapOff + 24, mapOff)
        // signature, then checksum (checksum covers the signature)
        MessageDigest.getInstance("SHA-1").apply { update(b, 32, total - 32) }.digest()
            .copyInto(b, 12)
        bb.putInt(8, Adler32().apply { update(b, 12, total - 12) }.value.toInt())
        return b
    }
}
