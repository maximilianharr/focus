package com.max.focus2

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

const val TYPE_APP = 0
const val TYPE_SITE = 1

@Entity
data class BlockItem(
    @PrimaryKey val value: String, // package name or bare domain
    val type: Int,
    val label: String,
    val allowance: Int, // minutes/day, 0 = fully blocked
)

@Entity
data class CheatWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMin: Int,
    val endMin: Int,
    val days: Int, // bit 0 = Monday .. bit 6 = Sunday
)

@Entity(primaryKeys = ["value", "date"])
data class Usage(
    val value: String,
    val date: String,
    // ponytail: one table, two semantics — apps use foreground seconds,
    // websites use wall-clock from firstSeen (first DNS hit of the day)
    val seconds: Int = 0,
    val firstSeen: Long = 0,
)

@Dao
interface FocusDao {
    @Query("SELECT * FROM BlockItem ORDER BY type, label COLLATE NOCASE") fun items(): Flow<List<BlockItem>>
    @Query("SELECT * FROM CheatWindow ORDER BY id") fun windows(): Flow<List<CheatWindow>>
    @Query("SELECT * FROM Usage") fun usage(): Flow<List<Usage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertItem(i: BlockItem)
    @Query("DELETE FROM BlockItem WHERE value = :v") fun deleteItem(v: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertWindow(w: CheatWindow): Long
    @Query("DELETE FROM CheatWindow WHERE id = :id") fun deleteWindow(id: Long)
    @Query("DELETE FROM BlockItem") fun clearItems()
    @Query("DELETE FROM CheatWindow") fun clearWindows()
    @Query("DELETE FROM Usage WHERE date != :today") fun pruneUsage(today: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertUsage(u: Usage)
    @Query("UPDATE Usage SET seconds = seconds + :s WHERE value = :v AND date = :d")
    fun bumpSeconds(v: String, d: String, s: Int)
    @Query("UPDATE Usage SET firstSeen = :t WHERE value = :v AND date = :d AND firstSeen = 0")
    fun setFirstSeen(v: String, d: String, t: Long)

    @Transaction
    fun addSeconds(v: String, d: String, s: Int) {
        insertUsage(Usage(v, d))
        bumpSeconds(v, d, s)
    }

    @Transaction
    fun markFirstSeen(v: String, d: String, t: Long) {
        insertUsage(Usage(v, d))
        setFirstSeen(v, d, t)
    }
}

@Database(entities = [BlockItem::class, CheatWindow::class, Usage::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): FocusDao
}

private val Context.store by preferencesDataStore("settings")

class Prefs(private val ctx: Context) {
    companion object {
        val COOLDOWN = floatPreferencesKey("cooldownMin")
        val CONFIRM = floatPreferencesKey("confirmMin")
        val EDIT = booleanPreferencesKey("editOn")
        val ARM = longPreferencesKey("armStart")
        val SHUTDOWN = booleanPreferencesKey("shutdown")
    }

    val flow get() = ctx.store.data
    suspend fun edit(fn: (MutablePreferences) -> Unit) = ctx.store.edit { fn(it) }
}

// Known DoH/DoT provider hostnames, blocked at DNS level so browsers'
// "Secure DNS" can't tunnel around the filter.
val DOH_HOSTS = setOf(
    "cloudflare-dns.com", "one.one.one.one", "dns.google", "dns.quad9.net",
    "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net", "dns.adguard.com",
    "dns.adguard-dns.com", "doh.opendns.com", "dns.nextdns.io",
    "doh.cleanbrowsing.org", "doh.dns.sb", "dns.alidns.com", "doh.pub",
)

const val VERDICT_ALLOW = 0
const val VERDICT_BLOCK = 1
const val VERDICT_START = 2 // allow, but caller must record firstSeen

// In-memory snapshot of DB + prefs, kept fresh by flows collected in App.
// Services (accessibility, VPN thread) read it synchronously.
object Engine {
    @Volatile var items: List<BlockItem> = emptyList()
    @Volatile var windows: List<CheatWindow> = emptyList()
    @Volatile var usage: Map<String, Usage> = emptyMap()
    @Volatile var shutdown = false
    @Volatile var editOn = false
    @Volatile var armStart = 0L
    @Volatile var cooldownMin = 0.1f
    @Volatile var confirmMin = 1f

    fun applyPrefs(p: Preferences) {
        shutdown = p[Prefs.SHUTDOWN] ?: false
        editOn = p[Prefs.EDIT] ?: false
        armStart = p[Prefs.ARM] ?: 0L
        cooldownMin = p[Prefs.COOLDOWN] ?: 0.1f
        confirmMin = p[Prefs.CONFIRM] ?: 1f
    }

    fun today(): String = LocalDate.now().toString()

    fun cheatActive(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val nowMin = now.hour * 60 + now.minute
        val todayBit = 1 shl (now.dayOfWeek.value - 1)
        val yesterdayBit = 1 shl ((now.dayOfWeek.value + 5) % 7)
        return windows.any { w ->
            when {
                w.startMin < w.endMin ->
                    w.days and todayBit != 0 && nowMin >= w.startMin && nowMin < w.endMin
                w.startMin > w.endMin -> // overnight window, owned by its start day
                    (w.days and todayBit != 0 && nowMin >= w.startMin) ||
                        (w.days and yesterdayBit != 0 && nowMin < w.endMin)
                else -> false
            }
        }
    }

    fun enforcing() = !shutdown && !cheatActive()

    fun appItem(pkg: String) = items.find { it.type == TYPE_APP && it.value == pkg }

    fun usedSeconds(v: String) = usage["$v|${today()}"]?.seconds ?: 0

    fun appBlockReason(item: BlockItem): String? {
        if (!enforcing()) return null
        if (item.allowance == 0) return "Blocked all day"
        return if (usedSeconds(item.value) >= item.allowance * 60)
            "Daily allowance of ${item.allowance} min used up" else null
    }

    fun siteItem(host: String) =
        items.find { it.type == TYPE_SITE && (host == it.value || host.endsWith("." + it.value)) }

    fun siteVerdict(host: String, now: Long): Int {
        if (!enforcing()) return VERDICT_ALLOW
        if (DOH_HOSTS.any { host == it || host.endsWith(".$it") }) return VERDICT_BLOCK
        val item = siteItem(host) ?: return VERDICT_ALLOW
        if (item.allowance == 0) return VERDICT_BLOCK
        val u = usage["${item.value}|${today()}"]
        if (u == null || u.firstSeen == 0L) return VERDICT_START
        return if (now > u.firstSeen + item.allowance * 60_000L) VERDICT_BLOCK else VERDICT_ALLOW
    }
}

fun normalizeHost(input: String): String = input.trim().lowercase()
    .substringAfter("://").substringBefore("/").substringBefore("?")
    .removePrefix("www.").trim('.')

fun exportJson(prefs: Preferences?): String {
    val o = JSONObject()
    o.put("items", JSONArray(Engine.items.map { i ->
        JSONObject().put("value", i.value).put("type", i.type)
            .put("label", i.label).put("allowance", i.allowance)
    }))
    o.put("windows", JSONArray(Engine.windows.map { w ->
        JSONObject().put("startMin", w.startMin).put("endMin", w.endMin).put("days", w.days)
    }))
    o.put("cooldownMin", (prefs?.get(Prefs.COOLDOWN) ?: 0.1f).toDouble())
    o.put("confirmMin", (prefs?.get(Prefs.CONFIRM) ?: 1f).toDouble())
    return o.toString(2)
}

// Overwrites all current settings. Throws on malformed input.
suspend fun importJson(text: String, dao: FocusDao, prefs: Prefs) {
    val o = JSONObject(text)
    val items = o.getJSONArray("items")
    val windows = o.getJSONArray("windows")
    dao.clearItems()
    dao.clearWindows()
    for (i in 0 until items.length()) {
        val it = items.getJSONObject(i)
        dao.insertItem(BlockItem(it.getString("value"), it.getInt("type"),
            it.getString("label"), it.getInt("allowance")))
    }
    for (i in 0 until windows.length()) {
        val w = windows.getJSONObject(i)
        dao.insertWindow(CheatWindow(0, w.getInt("startMin"), w.getInt("endMin"), w.getInt("days")))
    }
    prefs.edit {
        it[Prefs.COOLDOWN] = o.optDouble("cooldownMin", 0.1).toFloat()
        it[Prefs.CONFIRM] = o.optDouble("confirmMin", 1.0).toFloat()
    }
}
