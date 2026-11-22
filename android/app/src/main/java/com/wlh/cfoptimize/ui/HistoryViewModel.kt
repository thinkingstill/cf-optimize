package com.wlh.cfoptimize.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wlh.cfoptimize.data.HistoryStore
import com.wlh.cfoptimize.data.IpStat
import com.wlh.cfoptimize.data.RunRecord
import com.wlh.cfoptimize.data.aggregate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HistoryStore.get(app)

    val records: StateFlow<List<RunRecord>> = store.records

    private val _stats = MutableStateFlow<List<IpStat>>(emptyList())
    val stats: StateFlow<List<IpStat>> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.load() }
            store.records.collect { list ->
                _stats.value = withContext(Dispatchers.Default) { aggregate(list) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(id) }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
    }
}
