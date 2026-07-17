package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NavigationStateCache {
    data class NavigationState(
        val distance: String,
        val street: String,
        val baseStructure: String,
        val action: String,
        val icon: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _latestState = MutableStateFlow<NavigationState?>(null)
    val latestState: StateFlow<NavigationState?> = _latestState.asStateFlow()

    fun update(state: NavigationState) {
        _latestState.value = state
    }

    fun clear() {
        _latestState.value = null
    }
}
