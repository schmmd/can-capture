package com.cancapture.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cancapture.App
import com.cancapture.data.Capture
import com.cancapture.data.CaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CapturesViewModel(
    application: Application,
    private val repo: CaptureRepository
) : AndroidViewModel(application) {

    private val _captures = MutableStateFlow<List<Capture>>(emptyList())
    val captures: StateFlow<List<Capture>> = _captures.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.list() }
            _captures.value = list
        }
    }

    fun delete(capture: Capture) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(capture) }
            refresh()
        }
    }

    fun shareIntent(capture: Capture): Intent = repo.buildShareIntent(capture)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                CapturesViewModel(app, app.container.captureRepository)
            }
        }
    }
}
