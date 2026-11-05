package io.homeassistant.companion.android.settings.shortcuts.legacy

import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.common.data.websocket.impl.entities.LovelaceConfigResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.LovelaceViewResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.PanelResponse
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DashboardOptionsTest {

    private val overview = DashboardOption(urlPath = "lovelace", title = null, rawPath = "/lovelace")
    private val rooms = DashboardOption(urlPath = "dashboard-rooms", title = "Rooms", rawPath = "/dashboard-rooms")

    @Test
    fun `Given panels when mapping to dashboard options then only dashboards are kept with the default one first`() {
        val panels = listOf(
            PanelResponse(componentName = "lovelace", urlPath = "dashboard-rooms", title = "Rooms"),
            PanelResponse(componentName = "energy", urlPath = "energy", title = "energy"),
            PanelResponse(componentName = "lovelace", urlPath = "map", title = "Map"),
            PanelResponse(componentName = "lovelace", urlPath = "dashboard-alarm", title = "alarm"),
            PanelResponse(componentName = "lovelace", urlPath = "lovelace"),
        )

        assertEquals(
            listOf(
                overview,
                DashboardOption(urlPath = "dashboard-alarm", title = "alarm", rawPath = "/dashboard-alarm"),
                DashboardOption(urlPath = "map", title = "Map", rawPath = "/map"),
                rooms,
            ),
            panels.toDashboardOptions(),
        )
    }

    @Test
    fun `Given dashboards when getting the config url path then it is null only for the default dashboard`() {
        assertNull(overview.configUrlPath)
        assertEquals("dashboard-rooms", rooms.configUrlPath)
    }

    @Test
    fun `Given views when mapping to view options then a view is addressed by its path or else by its index`() {
        val config = LovelaceConfigResponse(
            views = listOf(
                LovelaceViewResponse(title = "Kitchen", path = "kitchen"),
                LovelaceViewResponse(title = "Bath"),
                LovelaceViewResponse(path = "living room"),
                LovelaceViewResponse(path = ""),
            ),
        )

        assertEquals(
            listOf(
                DashboardViewOption(title = "Kitchen", number = 1, rawPath = "/dashboard-rooms/kitchen"),
                DashboardViewOption(title = "Bath", number = 2, rawPath = "/dashboard-rooms/1"),
                DashboardViewOption(title = "living room", number = 3, rawPath = "/dashboard-rooms/living%20room"),
                DashboardViewOption(title = null, number = 4, rawPath = "/dashboard-rooms/3"),
            ),
            config.toDashboardViewOptions(rooms),
        )
    }

    @Test
    fun `Given paths when finding the dashboard then it matches the dashboard and its views with or without slashes`() {
        val dashboards = listOf(overview, rooms)

        assertEquals(rooms, dashboards.findForPath("/dashboard-rooms"))
        assertEquals(rooms, dashboards.findForPath("dashboard-rooms/kitchen"))
        assertEquals(overview, dashboards.findForPath("/lovelace/0/"))
        assertNull(dashboards.findForPath("/dashboard-roomsextra"))
        assertNull(dashboards.findForPath("/config/areas"))
        assertNull(dashboards.findForPath(""))
    }

    @Test
    fun `Given paths when finding the view then only the exact view path matches`() {
        val kitchen = DashboardViewOption(title = "Kitchen", number = 1, rawPath = "/dashboard-rooms/kitchen")
        val views = listOf(kitchen)

        assertEquals(kitchen, views.findForPath("dashboard-rooms/kitchen/"))
        assertNull(views.findForPath("/dashboard-rooms"))
        assertNull(views.findForPath("/dashboard-rooms/kitchen-2"))
    }
}
