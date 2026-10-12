package com.github.reygnn.walled.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.reygnn.walled.model.SnapMode
import com.github.reygnn.walled.model.SnapSettings
import com.github.reygnn.walled.model.WallpaperLayerState
import com.github.reygnn.walled.model.WallpaperState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What survives process death: the layers and the snap settings. */
data class PersistedEditor(
    val state: WallpaperState = WallpaperState.NONE,
    val snap: SnapSettings = SnapSettings(),
)

/**
 * Persistence for the editor. The app is the only writer, so it is read once at
 * start and then written through on every committed change — no observed flow.
 */
interface WallpaperRepository {
    suspend fun load(): PersistedEditor
    suspend fun saveLayers(state: WallpaperState)
    suspend fun saveSnap(snap: SnapSettings)
}

/** The layer list as JSON — pure, JVM-tested. A corrupt payload reads as no layers. */
object LayerCodec {
    private const val TAG = "LayerCodec"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: WallpaperState): String = json.encodeToString(state.layers)

    fun decode(raw: String?): WallpaperState {
        if (raw.isNullOrBlank()) return WallpaperState.NONE
        return try {
            WallpaperState(json.decodeFromString<List<WallpaperLayerState>>(raw))
        } catch (e: SerializationException) {
            Log.w(TAG, "Stored layers are corrupt; starting empty", e)
            WallpaperState.NONE
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Stored layers are corrupt; starting empty", e)
            WallpaperState.NONE
        }
    }
}

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(name = "editor")

class DataStoreWallpaperRepository(context: Context) : WallpaperRepository {

    private val store = context.applicationContext.editorDataStore

    private object Keys {
        val LAYERS = stringPreferencesKey("layers_json")
        val SNAP_ENABLED = booleanPreferencesKey("snap_enabled")
        val SNAP_MODE = stringPreferencesKey("snap_mode")
        val SNAP_HORIZONTAL = booleanPreferencesKey("snap_horizontal")
        val SNAP_VERTICAL = booleanPreferencesKey("snap_vertical")
    }

    override suspend fun load(): PersistedEditor {
        val prefs = store.data.first()
        val defaults = SnapSettings()
        return PersistedEditor(
            state = LayerCodec.decode(prefs[Keys.LAYERS]),
            snap = SnapSettings(
                enabled = prefs[Keys.SNAP_ENABLED] ?: defaults.enabled,
                mode = SnapMode.entries.firstOrNull { it.name == prefs[Keys.SNAP_MODE] } ?: defaults.mode,
                horizontal = prefs[Keys.SNAP_HORIZONTAL] ?: defaults.horizontal,
                vertical = prefs[Keys.SNAP_VERTICAL] ?: defaults.vertical,
            ),
        )
    }

    override suspend fun saveLayers(state: WallpaperState) {
        val encoded = LayerCodec.encode(state)
        store.edit { it[Keys.LAYERS] = encoded }
    }

    override suspend fun saveSnap(snap: SnapSettings) {
        store.edit {
            it[Keys.SNAP_ENABLED] = snap.enabled
            it[Keys.SNAP_MODE] = snap.mode.name
            it[Keys.SNAP_HORIZONTAL] = snap.horizontal
            it[Keys.SNAP_VERTICAL] = snap.vertical
        }
    }
}
