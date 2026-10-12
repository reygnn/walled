package com.github.reygnn.walled

import android.app.Application
import com.github.reygnn.walled.data.DataStoreWallpaperRepository
import com.github.reygnn.walled.data.LayerImageStore
import com.github.reygnn.walled.data.WallpaperRepository
import java.io.File

/** Composition root (manual DI): the long-lived collaborators of the editor. */
class WalledApplication : Application() {
    val repository: WallpaperRepository by lazy { DataStoreWallpaperRepository(this) }
    val imageStore: LayerImageStore by lazy { LayerImageStore(File(filesDir, "layers")) }
}
