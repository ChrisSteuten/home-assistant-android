package io.homeassistant.companion.android.settings.shortcuts.legacy

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.common.data.integration.display.EntitiesForDisplayManager
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.WebSocketRepository
import io.homeassistant.companion.android.common.data.websocket.impl.entities.LovelaceConfigResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.LovelaceViewResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.PanelResponse
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.settings.shortcuts.HaShortcutManager
import io.homeassistant.companion.android.settings.shortcuts.SHORTCUT_EXTRA_PATH
import io.homeassistant.companion.android.settings.shortcuts.SHORTCUT_EXTRA_SERVER
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit4Rule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [Build.VERSION_CODES.TIRAMISU])
@OptIn(ExperimentalCoroutinesApi::class)
class ManageShortcutsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherJUnit4Rule()

    private lateinit var application: Application

    private val serverManager: ServerManager = mockk()
    private val shortcutManager: HaShortcutManager = mockk()
    private val entitiesForDisplayManager: EntitiesForDisplayManager = mockk()
    private val webSocketRepository: WebSocketRepository = mockk()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()

        val dynamicIds = ShortcutManagerCompat.getShortcuts(
            application,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
        ).map { it.id }
        if (dynamicIds.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(application, dynamicIds)
        }

        coEvery { serverManager.getServer(any<Int>()) } returns fakeServer(0)
        coEvery { serverManager.servers() } returns listOf(fakeServer(0))
        every { entitiesForDisplayManager.snapshotInContext(serverId = any()) } returns emptyFlow()
        coEvery { shortcutManager.resolveIconFromIntent(any()) } returns null
        coEvery { serverManager.webSocketRepository(0) } returns webSocketRepository
    }

    private fun fakeServer(id: Int) = Server(
        id = id,
        _name = "Server $id",
        connection = ServerConnectionInfo(externalUrl = "https://example.com"),
        session = ServerSessionInfo(),
        user = ServerUserInfo(),
    )

    private fun fakeShortcutInfo(id: String, path: String = "path_$id") = ShortcutInfoCompat.Builder(application, id)
        .setShortLabel("Label $id")
        .setLongLabel("Description $id")
        .setIntent(
            Intent(Intent.ACTION_VIEW)
                .putExtra(SHORTCUT_EXTRA_SERVER, 0)
                .putExtra(SHORTCUT_EXTRA_PATH, path),
        )
        .build()

    private fun createViewModel() = ManageShortcutsViewModel(
        serverManager = serverManager,
        shortcutManager = shortcutManager,
        entitiesForDisplayManager = entitiesForDisplayManager,
        application = application,
    )

    @Test
    fun `Given more dynamic shortcuts than slots when creating the view model then extra shortcuts are ignored`() = runTest {
        val slotShortcuts = (1..5).map { fakeShortcutInfo("shortcut_$it") }
        val strayShortcuts = listOf(
            fakeShortcutInfo("shortcutStray1"),
            fakeShortcutInfo("shortcutStray2"),
            fakeShortcutInfo("1"),
            fakeShortcutInfo("shortcut_05"),
        )
        ShortcutManagerCompat.addDynamicShortcuts(application, slotShortcuts + strayShortcuts)

        val viewModel = createViewModel()
        advanceUntilIdle()

        (1..5).forEach { index ->
            assertEquals("path_shortcut_$index", viewModel.shortcuts[index - 1].path.value)
        }
        assertEquals("", viewModel.shortcuts.last().path.value)
    }

    @Test
    fun `Given a single dynamic shortcut when creating the view model then it fills the slot matching its id`() = runTest {
        ShortcutManagerCompat.addDynamicShortcuts(application, listOf(fakeShortcutInfo("shortcut_3")))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("path_shortcut_3", viewModel.shortcuts[2].path.value)
        assertEquals("", viewModel.shortcuts[0].path.value)
    }

    @Test
    fun `Given a pinned id that is not exactly a slot id when creating the shortcut then it is not added as dynamic`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        listOf("shortcut for lights", "1", "shortcut_05").forEach { pinnedId ->
            every {
                shortcutManager.buildShortcutInfo(any(), any(), any(), any(), any(), any())
            } returns fakeShortcutInfo(pinnedId)

            viewModel.createShortcut(pinnedId, 0, "Label", "Description", "path", null)
        }

        assertTrue(
            ShortcutManagerCompat.getShortcuts(application, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).isEmpty(),
        )
    }

    @Test
    fun `Given shortcut ids when checking if reserved then only exact slot ids are reserved`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        (1..5).forEach { slot ->
            assertTrue(viewModel.isReservedShortcutId("shortcut_$slot"))
        }
        listOf("", "1", "shortcutA", "shortcut_0", "shortcut_6", "shortcut_05", "shortcut_1x").forEach { id ->
            assertFalse(viewModel.isReservedShortcutId(id))
        }
    }

    @Test
    fun `Given a slot id when creating the shortcut then it is added as dynamic`() = runTest {
        every {
            shortcutManager.buildShortcutInfo(any(), any(), any(), any(), any(), any())
        } returns fakeShortcutInfo("shortcut_2")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createShortcut("shortcut_2", 0, "Label", "Description", "path", null)

        assertEquals(
            listOf("shortcut_2"),
            ShortcutManagerCompat.getShortcuts(application, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).map { it.id },
        )
    }

    @Test
    fun `Given a known server when loading dashboards twice then dashboards are requested once and exposed`() = runTest {
        coEvery { webSocketRepository.getPanels() } returns listOf(
            PanelResponse(componentName = "lovelace", urlPath = "dashboard-rooms", title = "Rooms"),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDashboards(0)
        advanceUntilIdle()
        viewModel.loadDashboards(0)
        advanceUntilIdle()

        assertEquals(
            listOf(DashboardOption(urlPath = "dashboard-rooms", title = "Rooms", rawPath = "/dashboard-rooms")),
            viewModel.dashboards[0],
        )
        coVerify(exactly = 1) { webSocketRepository.getPanels() }
    }

    @Test
    fun `Given an unknown server when loading dashboards then nothing is requested`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDashboards(42)
        advanceUntilIdle()

        assertNull(viewModel.dashboards[42])
        coVerify(exactly = 0) { serverManager.webSocketRepository(42) }
    }

    @Test
    fun `Given no response when loading dashboards then dashboards stay absent and can be loaded again`() = runTest {
        coEvery { webSocketRepository.getPanels() } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDashboards(0)
        advanceUntilIdle()
        viewModel.loadDashboards(0)
        advanceUntilIdle()

        assertNull(viewModel.dashboards[0])
        coVerify(exactly = 2) { webSocketRepository.getPanels() }
    }

    @Test
    fun `Given the default dashboard when loading its views then config is requested without url path`() = runTest {
        val overview = DashboardOption(urlPath = "lovelace", title = null, rawPath = "/lovelace")
        coEvery { webSocketRepository.getLovelaceConfig(null) } returns LovelaceConfigResponse(
            views = listOf(LovelaceViewResponse(title = "Home", path = "home")),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDashboardViews(0, overview)
        advanceUntilIdle()

        assertEquals(
            listOf(DashboardViewOption(title = "Home", number = 1, rawPath = "/lovelace/home")),
            viewModel.dashboardViews[ServerDashboard(serverId = 0, urlPath = "lovelace")],
        )
    }

    @Test
    fun `Given a dashboard without stored config when loading its views then it has no views`() = runTest {
        val rooms = DashboardOption(urlPath = "dashboard-rooms", title = "Rooms", rawPath = "/dashboard-rooms")
        coEvery { webSocketRepository.getLovelaceConfig("dashboard-rooms") } returns null
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadDashboardViews(0, rooms)
        advanceUntilIdle()

        assertEquals(emptyList<DashboardViewOption>(), viewModel.dashboardViews[ServerDashboard(serverId = 0, urlPath = "dashboard-rooms")])
    }
}
