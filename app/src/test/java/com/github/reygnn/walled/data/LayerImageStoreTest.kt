package com.github.reygnn.walled.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** [LayerImageStore] against a real temp directory — plain java.io, no Robolectric. */
class LayerImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File by lazy { File(tmp.root, "layers") }
    private val store by lazy { LayerImageStore(dir) }

    @Test
    fun `a copied image can be opened again by its name`() {
        val name = store.copyIn { "pixels".byteInputStream() }
        assertNotNull(name)
        assertEquals("pixels", store.open(name!!)!!.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `a source that cannot be opened or read yields no file`() {
        assertNull(store.copyIn { null })
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }
        assertNull(store.copyIn { failing })
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `delete removes the named files only`() {
        val keep = store.copyIn { "a".byteInputStream() }!!
        val drop = store.copyIn { "b".byteInputStream() }!!
        store.delete(listOf(drop))
        assertNotNull(store.open(keep))
        assertNull(store.open(drop))
    }

    @Test
    fun `names that would escape the directory are never touched`() {
        val outside = tmp.newFile("victim")
        store.delete(listOf("../victim", "", ".."))
        assertTrue(outside.exists())
        assertNull(store.open("../victim"))
    }

    @Test
    fun `orphan gc deletes old unreferenced files and keeps referenced or young ones`() {
        val referenced = store.copyIn { "r".byteInputStream() }!!
        val oldOrphan = store.copyIn { "o".byteInputStream() }!!
        val youngOrphan = store.copyIn { "y".byteInputStream() }!!
        val now = System.currentTimeMillis()
        File(dir, referenced).setLastModified(now - 10 * LayerImageStore.ORPHAN_MIN_AGE_MS)
        File(dir, oldOrphan).setLastModified(now - 10 * LayerImageStore.ORPHAN_MIN_AGE_MS)

        assertEquals(1, store.deleteOrphans(setOf(referenced), nowMs = now))
        assertTrue(File(dir, referenced).exists())
        assertFalse(File(dir, oldOrphan).exists())
        assertTrue(File(dir, youngOrphan).exists())
    }

    @Test
    fun `orphan gc on a missing directory is a no-op`() {
        assertEquals(0, store.deleteOrphans(emptySet()))
    }

    @Test
    fun `length and header read the stored file`() {
        val name = store.copyIn { "PNGDATA".byteInputStream() }!!
        assertEquals(7L, store.length(name))
        assertEquals("PNG", store.header(name, 3)!!.decodeToString())
        assertNull(store.length("gone"))
        assertNull(store.header("gone"))
    }
}
