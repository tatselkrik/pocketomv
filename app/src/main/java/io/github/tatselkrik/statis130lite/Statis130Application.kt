package io.github.tatselkrik.statis130lite

import android.app.Application
import java.io.File

class Statis130Application : Application() {
    internal val sessions by lazy {
        SessionRepository(AtomicSessionStorage(File(noBackupFilesDir, "current-session.json")))
    }
}
