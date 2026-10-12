package com.github.reygnn.walled.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A project file: a human-editable JSON next to its images in a project folder.
 *
 * ```json
 * { "format": "walled-project", "version": 1,
 *   "frame": { "width": 1080, "height": 2340 },
 *   "layers": [ { "file": "ducks.png", "scale": 1.53, "x": 0.0, "y": 58.4 } ] }
 * ```
 *
 * Layers are bottom-most first. [ProjectLayer.file] is a plain file name in the
 * same folder. A layer without `scale`/`x`/`y` is placed automatically (fills
 * the screen). [frame] is the screen the values were made for; on another
 * screen they are scaled proportionally ([ProjectMapping.transformFor]).
 */
@Serializable
data class ProjectFile(
    val format: String = ProjectCodec.FORMAT,
    val version: Int = ProjectCodec.VERSION,
    val frame: ProjectFrame? = null,
    val layers: List<ProjectLayer> = emptyList(),
)

@Serializable
data class ProjectFrame(val width: Int, val height: Int)

@Serializable
data class ProjectLayer(
    val file: String,
    val scale: Float? = null,
    @SerialName("x") val translateX: Float? = null,
    @SerialName("y") val translateY: Float? = null,
)

/** Why a project file could not be read. */
enum class ProjectProblem { NOT_A_PROJECT, NEWER_VERSION, BAD_FILE_NAME }

sealed interface ProjectDecode {
    data class Ok(val project: ProjectFile) : ProjectDecode
    data class Invalid(val problem: ProjectProblem) : ProjectDecode
}

/** Project JSON in and out — pure, JVM-tested. */
object ProjectCodec {
    const val FORMAT = "walled-project"
    const val VERSION = 1
    const val EXTENSION = ".json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(project: ProjectFile): String = json.encodeToString(project) + "\n"

    fun decode(raw: String): ProjectDecode {
        val project = try {
            json.decodeFromString<ProjectFile>(raw)
        } catch (_: SerializationException) {
            return ProjectDecode.Invalid(ProjectProblem.NOT_A_PROJECT)
        } catch (_: IllegalArgumentException) {
            return ProjectDecode.Invalid(ProjectProblem.NOT_A_PROJECT)
        }
        if (project.format != FORMAT) return ProjectDecode.Invalid(ProjectProblem.NOT_A_PROJECT)
        if (project.version > VERSION) return ProjectDecode.Invalid(ProjectProblem.NEWER_VERSION)
        if (project.layers.any { !isPlainFileName(it.file) }) return ProjectDecode.Invalid(ProjectProblem.BAD_FILE_NAME)
        return ProjectDecode.Ok(project)
    }

    /** A name directly inside the folder: no path separator, not `.`/`..`, not blank. */
    fun isPlainFileName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".."

    /** A user-typed project name as a file name: trimmed, separators replaced, `.json` appended. */
    fun projectFileName(input: String): String? {
        val base = sanitizeFileName(input.trim()).removeSuffix(EXTENSION).trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        return base + EXTENSION
    }

    /** Replaces characters that are not allowed or awkward in a file name. */
    fun sanitizeFileName(name: String): String = name.map { c -> if (c in "/\\:*?\"<>|" || c.isISOControl()) '_' else c }.joinToString("")

    /**
     * The extension for an image from its first bytes (PNG, JPEG, WebP, GIF), or
     * `""` when unknown — for layers that have no source name to save under.
     */
    fun imageExtension(header: ByteArray): String {
        fun at(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> ".png"
            at(0) == 0xFF && at(1) == 0xD8 -> ".jpg"
            header.size >= 12 && header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                header.copyOfRange(8, 12).decodeToString() == "WEBP" -> ".webp"
            header.size >= 3 && header.copyOfRange(0, 3).decodeToString() == "GIF" -> ".gif"
            else -> ""
        }
    }

    /**
     * The `n`-th alternative of [name] when a different file already uses it:
     * `ducks.png` → `ducks (2).png`.
     */
    fun alternativeName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
}

/** Project values ↔ layer transforms on this device's frame — pure, JVM-tested. */
object ProjectMapping {

    /**
     * The transform for [layer] on a [frameW]×[frameH] frame, or `null` when the
     * layer leaves it open (any of scale/x/y missing → placed automatically).
     *
     * A project made for another [projectFrame] is scaled by the width ratio and
     * centered vertically, so the composition keeps its proportions: same width,
     * and a taller or shorter screen adds or trims equally above and below.
     */
    fun transformFor(layer: ProjectLayer, projectFrame: ProjectFrame?, frameW: Int, frameH: Int): LayerTransform? {
        val scale = layer.scale ?: return null
        val x = layer.translateX ?: return null
        val y = layer.translateY ?: return null
        if (projectFrame == null || projectFrame.width <= 0 || projectFrame.height <= 0 || frameW <= 0 || frameH <= 0 ||
            (projectFrame.width == frameW && projectFrame.height == frameH)
        ) {
            return LayerTransform(scale, x, y)
        }
        val k = frameW.toFloat() / projectFrame.width
        val offsetY = (frameH - projectFrame.height * k) / 2f
        return LayerTransform(scale * k, x * k, y * k + offsetY)
    }

    fun toProjectLayer(file: String, transform: LayerTransform?): ProjectLayer =
        ProjectLayer(file, transform?.scale, transform?.translateX, transform?.translateY)
}
