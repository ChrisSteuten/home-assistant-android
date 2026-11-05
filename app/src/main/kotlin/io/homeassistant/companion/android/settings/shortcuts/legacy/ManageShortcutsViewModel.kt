package io.homeassistant.companion.android.settings.shortcuts.legacy

import android.app.Application
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.timoptr.mdiicons.MdiIcon
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.display.EntitiesForDisplayManager
import io.homeassistant.companion.android.common.data.integration.display.EntityDisplayState
import io.homeassistant.companion.android.common.data.integration.display.EntityDisplayWithContext
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.settings.shortcuts.HaShortcutManager
import io.homeassistant.companion.android.settings.shortcuts.SHORTCUT_EXTRA_PATH
import io.homeassistant.companion.android.settings.shortcuts.SHORTCUT_EXTRA_SERVER
import io.homeassistant.companion.android.widgets.assist.AssistShortcutActivity
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import timber.log.Timber

@RequiresApi(Build.VERSION_CODES.N_MR1)
@HiltViewModel
internal class ManageShortcutsViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val shortcutManager: HaShortcutManager,
    private val entitiesForDisplayManager: EntitiesForDisplayManager,
    application: Application,
) : AndroidViewModel(application) {

    val app = application

    val canPinShortcuts =
        SdkVersion.isAtLeast(Build.VERSION_CODES.O) && ShortcutManagerCompat.isRequestPinShortcutSupported(app)
    var pinnedShortcuts = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        .filter { !it.id.startsWith(AssistShortcutActivity.SHORTCUT_PREFIX) }
        .toMutableList()
        private set
    var dynamicShortcuts = mutableListOf<ShortcutInfoCompat>()
        private set

    var servers by mutableStateOf(emptyList<Server>())
        private set
    var displayEntities = mutableStateMapOf<Int, EntityDisplayState<EntityDisplayWithContext>>()
        private set

    private val loadedDashboards = mutableStateMapOf<Int, List<DashboardOption>>()
    private val loadedDashboardViews = mutableStateMapOf<ServerDashboard, List<DashboardViewOption>>()

    /** Dashboards per server ID, absent while not (successfully) loaded. */
    val dashboards: Map<Int, List<DashboardOption>> = loadedDashboards

    /** Views per dashboard, absent while not (successfully) loaded. */
    val dashboardViews: Map<ServerDashboard, List<DashboardViewOption>> = loadedDashboardViews

    private val dashboardsLoading = mutableSetOf<Int>()
    private val dashboardViewsLoading = mutableSetOf<ServerDashboard>()

    private suspend fun currentServerId() = serverManager.getServer()?.id ?: 0

    data class Shortcut(
        var id: MutableState<String?>,
        var serverId: MutableState<Int>,
        var selectedIcon: MutableState<MdiIcon?>,
        var label: MutableState<String>,
        var desc: MutableState<String>,
        var path: MutableState<String>,
        var type: MutableState<String>,
        var delete: MutableState<Boolean>,
    )

    var shortcuts = mutableStateListOf<Shortcut>().apply {
        repeat(ManageShortcutsSettingsFragment.MAX_SHORTCUTS + 1) {
            add(
                Shortcut(
                    id = mutableStateOf(""),
                    serverId = mutableIntStateOf(0),
                    selectedIcon = mutableStateOf(null),
                    label = mutableStateOf(""),
                    desc = mutableStateOf(""),
                    path = mutableStateOf(""),
                    type = mutableStateOf("lovelace"),
                    delete = mutableStateOf(false),
                ),
            )
        }
    }
        private set

    init {
        viewModelScope.launch {
            val currentServerId = currentServerId()
            shortcuts.forEach { it.serverId.value = currentServerId }

            val servers = serverManager.servers()
            this@ManageShortcutsViewModel.servers = servers
            servers.forEach { server ->
                launch {
                    entitiesForDisplayManager.snapshotInContext(serverId = server.id).collect { state ->
                        displayEntities[server.id] = state
                    }
                }
            }

            updateDynamicShortcuts()
            Timber.d("We have ${dynamicShortcuts.size} dynamic shortcuts")

            if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
                Timber.d("Can we pin shortcuts: ${ShortcutManagerCompat.isRequestPinShortcutSupported(app)}")
                Timber.d("We have ${pinnedShortcuts.size} pinned shortcuts")
            }

            dynamicShortcuts.forEach { item ->
                dynamicSlotIndex(item.id)?.let { index ->
                    Timber.d("setting ${item.id} data")
                    shortcuts[index].setData(item)
                }
            }
        }
    }

    fun createShortcut(
        shortcutId: String,
        serverId: Int,
        shortcutLabel: String,
        shortcutDesc: String,
        shortcutPath: String,
        icon: MdiIcon?,
    ) {
        Timber.d("Attempt to add shortcut $shortcutId")
        val shortcut = shortcutManager.buildShortcutInfo(
            shortcutId = shortcutId,
            serverId = serverId,
            label = shortcutLabel,
            longLabel = shortcutDesc,
            path = shortcutPath,
            icon = icon,
        )

        if (dynamicSlotIndex(shortcutId) != null) {
            ShortcutManagerCompat.addDynamicShortcuts(app, listOf(shortcut))
            updateDynamicShortcuts()
        } else {
            var isNewPinned = true
            for (item in pinnedShortcuts) {
                if (item.id == shortcutId) {
                    isNewPinned = false
                    Timber.d("Updating pinned shortcut: $shortcutId")
                    ShortcutManagerCompat.updateShortcuts(app, listOf(shortcut))
                    Toast.makeText(app, commonR.string.shortcut_updated, Toast.LENGTH_SHORT).show()
                }
            }

            if (isNewPinned) {
                Timber.d("Requesting to pin shortcut: $shortcutId")
                if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
                    ShortcutManagerCompat.requestPinShortcut(app, shortcut, null)
                }
            }
        }
    }

    /**
     * Loads the dashboards of the server [serverId] into [dashboards], unless already loaded or loading, or
     * the server is not (yet) one of the [servers].
     */
    fun loadDashboards(serverId: Int) {
        if (servers.none { it.id == serverId }) return
        if (serverId in dashboards || !dashboardsLoading.add(serverId)) return
        viewModelScope.launch {
            logServerFailure("Failed to load dashboards for serverId=$serverId") {
                serverManager.webSocketRepository(serverId).getPanels()?.let { panels ->
                    loadedDashboards[serverId] = panels.toDashboardOptions()
                }
            }
            dashboardsLoading.remove(serverId)
        }
    }

    /**
     * Loads the views of [dashboard] on the server [serverId] into [dashboardViews], unless already loaded or loading.
     */
    fun loadDashboardViews(serverId: Int, dashboard: DashboardOption) {
        val key = ServerDashboard(serverId = serverId, urlPath = dashboard.urlPath)
        if (key in dashboardViews || !dashboardViewsLoading.add(key)) return
        viewModelScope.launch {
            logServerFailure("Failed to load dashboard views for serverId=$serverId") {
                // A dashboard without stored configuration (e.g. auto-generated) has no views to choose from
                val config = serverManager.webSocketRepository(serverId).getLovelaceConfig(dashboard.configUrlPath)
                loadedDashboardViews[key] = config?.toDashboardViewOptions(dashboard).orEmpty()
            }
            dashboardViewsLoading.remove(key)
        }
    }

    fun deleteShortcut(shortcutId: String) {
        ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(shortcutId))
        updateDynamicShortcuts()
    }

    fun setPinnedShortcutData(shortcutId: String) = viewModelScope.launch {
        for (item in pinnedShortcuts) {
            if (item.id == shortcutId) {
                shortcuts.last().id.value = item.id
                shortcuts.last().setData(item)
            }
        }
    }

    private fun updateDynamicShortcuts() {
        dynamicShortcuts =
            ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).sortedBy {
                it.id
            }.toMutableList()
    }

    /** Returns whether [shortcutId] is reserved for a dynamic shortcut slot and can't be used for a pinned shortcut. */
    fun isReservedShortcutId(shortcutId: String): Boolean = dynamicSlotIndex(shortcutId) != null

    /**
     * Returns the zero-based slot index for a dynamic shortcut ID managed by this screen
     * (exactly "${ManageShortcutsSettingsFragment.SHORTCUT_PREFIX}_<slot>" where slot is 1..MAX_SHORTCUTS),
     * or null for any other ID such as a pinned shortcut.
     */
    private fun dynamicSlotIndex(shortcutId: String): Int? = (1..ManageShortcutsSettingsFragment.MAX_SHORTCUTS)
        .firstOrNull { slot -> "${ManageShortcutsSettingsFragment.SHORTCUT_PREFIX}_$slot" == shortcutId }
        ?.minus(1)

    private suspend fun Shortcut.setData(item: ShortcutInfoCompat) {
        val currentServerId = currentServerId()
        serverId.value = item.intent.extras?.getInt(SHORTCUT_EXTRA_SERVER, currentServerId) ?: currentServerId
        label.value = item.shortLabel.toString()
        desc.value = item.longLabel.toString()
        path.value = item.intent.getStringExtra(SHORTCUT_EXTRA_PATH).orEmpty()
        selectedIcon.value = shortcutManager.resolveIconFromIntent(item.intent)
        if (path.value.startsWith("entityId:")) {
            type.value = "entityId"
        } else {
            type.value = "lovelace"
        }
    }

    fun updatePinnedShortcuts() {
        pinnedShortcuts.clear()
        pinnedShortcuts.addAll(
            ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { !it.id.startsWith(AssistShortcutActivity.SHORTCUT_PREFIX) },
        )
    }
}

/**
 * Runs [block], logging instead of throwing when the server answers with an unexpected payload or
 * doesn't exist anymore, as the dashboard choice is optional and the path can still be typed.
 */
private suspend fun logServerFailure(message: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        // CancellationException is an IllegalStateException, it must not be swallowed below
        throw e
    } catch (e: SerializationException) {
        Timber.e(e, message)
    } catch (e: IllegalStateException) {
        Timber.e(e, message)
    }
}
