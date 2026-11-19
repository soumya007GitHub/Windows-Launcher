package dev.metro.launcher.actioncenter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "notifications + actions" settings, as on Windows Phone 8.1: which four quick actions sit in the
 * Action Center's first row.
 */
class ActionCenterConfig private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _quickActions = MutableStateFlow(loadQuickActions())
    val quickActions: StateFlow<List<QuickAction>> = _quickActions.asStateFlow()

    /** Puts [action] in [slot] (0..3); if it was already in another slot, the two swap. */
    fun setQuickAction(slot: Int, action: QuickAction) {
        val list = _quickActions.value.toMutableList()
        val existing = list.indexOf(action)
        if (existing >= 0) list[existing] = list[slot]
        list[slot] = action
        _quickActions.value = list
        prefs.edit().putString(KEY_QUICK, list.joinToString(",") { it.name }).apply()
    }

    private fun loadQuickActions(): List<QuickAction> {
        val saved = prefs.getString(KEY_QUICK, null)
            ?.split(',')
            ?.mapNotNull { name -> QuickAction.entries.firstOrNull { it.name == name } }
            ?.distinct()
            .orEmpty()
        return (saved + DEFAULT).distinct().take(SLOTS)
    }

    companion object {
        const val SLOTS = 4
        private const val PREFS = "action_center"
        private const val KEY_QUICK = "quick_actions"
        private val DEFAULT = listOf(QuickAction.Wifi, QuickAction.Bluetooth, QuickAction.Airplane, QuickAction.Rotation)

        @Volatile
        private var instance: ActionCenterConfig? = null

        fun get(context: Context): ActionCenterConfig =
            instance ?: synchronized(this) { instance ?: ActionCenterConfig(context).also { instance = it } }
    }
}
