package com.github.reygnn.walled.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTest {

    private fun ok(raw: String) = (ProjectCodec.decode(raw) as ProjectDecode.Ok).project

    @Test
    fun `a hand-written project reads with x and y as the translate`() {
        val project = ok(
            """
            { "format": "walled-project", "version": 1,
              "frame": { "width": 1080, "height": 2340 },
              "layers": [
                { "file": "ducks.png", "scale": 1.5, "x": 0, "y": 58.4 },
                { "file": "seal.png" }
              ] }
            """,
        )
        assertEquals(ProjectFrame(1080, 2340), project.frame)
        assertEquals(ProjectLayer("ducks.png", 1.5f, 0f, 58.4f), project.layers[0])
        assertEquals(ProjectLayer("seal.png"), project.layers[1])
    }

    @Test
    fun `a project survives a round trip and leaves open values out`() {
        val project = ProjectFile(
            frame = ProjectFrame(1080, 2424),
            layers = listOf(ProjectLayer("a.png", 2f, -3f, 4f), ProjectLayer("b.jpg")),
        )
        val encoded = ProjectCodec.encode(project)
        assertEquals(project, ok(encoded))
        assertTrue(encoded.contains("\"format\": \"walled-project\""))
        assertFalse(encoded.contains("null"))
    }

    @Test
    fun `other JSON, a newer version and paths outside the folder are refused`() {
        fun problem(raw: String) = (ProjectCodec.decode(raw) as ProjectDecode.Invalid).problem
        assertEquals(ProjectProblem.NOT_A_PROJECT, problem("{ nope"))
        assertEquals(ProjectProblem.NOT_A_PROJECT, problem("""{ "format": "kolibri", "layers": [] }"""))
        assertEquals(ProjectProblem.NOT_A_PROJECT, problem("""{ "layers": [ { "scale": 1 } ] }"""))
        assertEquals(ProjectProblem.NEWER_VERSION, problem("""{ "version": 2, "layers": [] }"""))
        assertEquals(ProjectProblem.BAD_FILE_NAME, problem("""{ "layers": [ { "file": "../x.png" } ] }"""))
        assertEquals(ProjectProblem.BAD_FILE_NAME, problem("""{ "layers": [ { "file": "sub/x.png" } ] }"""))
        assertEquals(ProjectProblem.BAD_FILE_NAME, problem("""{ "layers": [ { "file": " " } ] }"""))
    }

    @Test
    fun `format and version may be omitted in a hand-written file`() {
        assertEquals(listOf(ProjectLayer("a.png")), ok("""{ "layers": [ { "file": "a.png" } ] }""").layers)
    }

    @Test
    fun `project names become json file names`() {
        assertEquals("kolibri.json", ProjectCodec.projectFileName(" kolibri "))
        assertEquals("kolibri.json", ProjectCodec.projectFileName("kolibri.json"))
        assertEquals("a_b.json", ProjectCodec.projectFileName("a/b"))
        assertNull(ProjectCodec.projectFileName("  "))
        assertNull(ProjectCodec.projectFileName(".json"))
    }

    @Test
    fun `alternative names go before the extension`() {
        assertEquals("ducks (2).png", ProjectCodec.alternativeName("ducks.png", 2))
        assertEquals("ducks (3)", ProjectCodec.alternativeName("ducks", 3))
        assertEquals(".hidden (2)", ProjectCodec.alternativeName(".hidden", 2))
    }

    @Test
    fun `the image extension comes from the magic bytes`() {
        assertEquals(".png", ProjectCodec.imageExtension(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(".jpg", ProjectCodec.imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(".webp", ProjectCodec.imageExtension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals(".gif", ProjectCodec.imageExtension("GIF89a".toByteArray()))
        assertEquals("", ProjectCodec.imageExtension(byteArrayOf(1, 2)))
    }

    @Test
    fun `values for the same frame are taken as they are`() {
        val layer = ProjectLayer("a.png", 1.5f, 10f, 20f)
        assertEquals(LayerTransform(1.5f, 10f, 20f), ProjectMapping.transformFor(layer, ProjectFrame(1080, 2340), 1080, 2340))
        assertEquals(LayerTransform(1.5f, 10f, 20f), ProjectMapping.transformFor(layer, null, 1080, 2340))
    }

    @Test
    fun `values for another frame scale by width and center vertically`() {
        // 540×1170 → 1080×2424: k = 2, the scaled frame is 2340 tall, 84 px spare → 42 above.
        val t = ProjectMapping.transformFor(ProjectLayer("a.png", 1f, 10f, 20f), ProjectFrame(540, 1170), 1080, 2424)
        assertEquals(LayerTransform(2f, 20f, 82f), t)
    }

    @Test
    fun `a layer with any value missing is placed automatically`() {
        assertNull(ProjectMapping.transformFor(ProjectLayer("a.png", 1f, 0f, null), null, 1080, 2340))
        assertNull(ProjectMapping.transformFor(ProjectLayer("a.png"), null, 1080, 2340))
    }
}
