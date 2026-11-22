package com.wlh.cfoptimize.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cf_settings")

data class ScanConfig(
    val domain: String = "",
    val sample: Int = 1000,
    val repeat: Int = 3,
    val concurrency: Int = 100,
    val timeoutMs: Int = 4000
) {
    companion object {
        const val SAMPLE_MIN = 50
        const val SAMPLE_MAX = 5956
        const val REPEAT_MIN = 1
        const val REPEAT_MAX = 5
        const val CONCURRENCY_MIN = 20
        const val CONCURRENCY_MAX = 300
        const val TIMEOUT_MIN = 2000
        const val TIMEOUT_MAX = 8000

        /** 实测有效率约 34%，用于参数面板的预期提示。 */
        const val EXPECTED_VALID_RATIO = 0.34
    }
}

class Settings(private val context: Context) {

    private object Keys {
        val DOMAIN = stringPreferencesKey("domain")
        val SAMPLE = intPreferencesKey("sample")
        val REPEAT = intPreferencesKey("repeat")
        val CONCURRENCY = intPreferencesKey("concurrency")
        val TIMEOUT = intPreferencesKey("timeout")
        val PENDING = booleanPreferencesKey("pending_scan")
    }

    val config: Flow<ScanConfig> = context.dataStore.data.map { p ->
        val defaults = ScanConfig()
        ScanConfig(
            domain = p[Keys.DOMAIN] ?: defaults.domain,
            sample = p[Keys.SAMPLE] ?: defaults.sample,
            repeat = p[Keys.REPEAT] ?: defaults.repeat,
            concurrency = p[Keys.CONCURRENCY] ?: defaults.concurrency,
            timeoutMs = p[Keys.TIMEOUT] ?: defaults.timeoutMs
        )
    }

    suspend fun save(config: ScanConfig) {
        context.dataStore.edit { p ->
            p[Keys.DOMAIN] = config.domain
            p[Keys.SAMPLE] = config.sample
            p[Keys.REPEAT] = config.repeat
            p[Keys.CONCURRENCY] = config.concurrency
            p[Keys.TIMEOUT] = config.timeoutMs
        }
    }

    suspend fun setPending(value: Boolean) {
        context.dataStore.edit { it[Keys.PENDING] = value }
    }

    suspend fun pending(): Boolean = context.dataStore.data.first()[Keys.PENDING] ?: false
}
