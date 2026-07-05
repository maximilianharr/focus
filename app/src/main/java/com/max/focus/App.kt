package com.max.focus

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    companion object {
        lateinit var dao: FocusDao
        lateinit var prefs: Prefs
    }

    override fun onCreate() {
        super.onCreate()
        dao = Room.databaseBuilder(this, Db::class.java, "focus").build().dao()
        prefs = Prefs(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { dao.items().collect { Engine.items = it } }
        scope.launch { dao.windows().collect { Engine.windows = it } }
        scope.launch { dao.usage().collect { l -> Engine.usage = l.associateBy { "${it.value}|${it.date}" } } }
        scope.launch { prefs.flow.collect { Engine.applyPrefs(it) } }
        scope.launch { dao.pruneUsage(Engine.today()) }
    }
}
