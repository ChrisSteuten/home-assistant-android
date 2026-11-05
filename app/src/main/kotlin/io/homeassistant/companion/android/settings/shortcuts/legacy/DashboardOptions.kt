package io.homeassistant.companion.android.settings.shortcuts.legacy

import android.net.Uri
import io.homeassistant.companion.android.common.data.websocket.impl.entities.LovelaceConfigResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.PanelResponse

/** Component name of the panels that render a dashboard. */
private const val LOVELACE_COMPONENT = "lovelace"

/** URL path of the default dashboard ("Overview"). */
private const val DEFAULT_DASHBOARD_URL_PATH = "lovelace"

private const val PATH_SEPARATOR = "/"

/**
 * A dashboard that a shortcut can open.
 *
 * @property title the title of the dashboard, `null` only for the default dashboard without a title
 * @property rawPath the frontend path to store in the shortcut
 */
internal data class DashboardOption(val urlPath: String, val title: String?, val rawPath: String) {
    /** URL path to request the configuration of this dashboard with, `null` for the default dashboard. */
    val configUrlPath: String? = urlPath.takeUnless { it == DEFAULT_DASHBOARD_URL_PATH }
}

/**
 * A view of a dashboard that a shortcut can open.
 *
 * @property title the title of the view, or its path when it has no title, `null` when it has neither
 * @property number the 1-based position of the view in the dashboard, to name a view without title or path
 * @property rawPath the frontend path to store in the shortcut
 */
internal data class DashboardViewOption(val title: String?, val number: Int, val rawPath: String)

/** Identifies a dashboard on a server. */
internal data class ServerDashboard(val serverId: Int, val urlPath: String)

/**
 * Returns the dashboards from the panels of a server, the default dashboard first then sorted by title.
 */
internal fun List<PanelResponse>.toDashboardOptions(): List<DashboardOption> = filter {
    it.componentName == LOVELACE_COMPONENT
}.map { panel ->
    DashboardOption(
        urlPath = panel.urlPath,
        title = panel.title ?: panel.urlPath.takeUnless { it == DEFAULT_DASHBOARD_URL_PATH },
        rawPath = PATH_SEPARATOR + Uri.encode(panel.urlPath),
    )
}.sortedWith(
    compareBy<DashboardOption> { it.urlPath != DEFAULT_DASHBOARD_URL_PATH }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title.orEmpty() },
)

/**
 * Returns the views of [dashboard] from its configuration. Like the frontend, a view is addressed by its
 * path or, when it has none, by its index.
 */
internal fun LovelaceConfigResponse.toDashboardViewOptions(dashboard: DashboardOption): List<DashboardViewOption> =
    views.mapIndexed { index, view ->
        val viewPath = view.path?.takeUnless { it.isBlank() }
        DashboardViewOption(
            title = view.title?.takeUnless { it.isBlank() } ?: viewPath,
            number = index + 1,
            rawPath = dashboard.rawPath + PATH_SEPARATOR + (viewPath?.let { Uri.encode(it) } ?: index.toString()),
        )
    }

/** Returns the dashboard that [path] points to, or `null` if it doesn't point to one of these dashboards. */
internal fun List<DashboardOption>.findForPath(path: String): DashboardOption? {
    val normalizedPath = path.normalizedFrontendPath()
    return firstOrNull { dashboard ->
        val dashboardPath = dashboard.rawPath.normalizedFrontendPath()
        normalizedPath == dashboardPath || normalizedPath.startsWith(dashboardPath + PATH_SEPARATOR)
    }
}

/** Returns the view that [path] points to, or `null` if it doesn't point to one of these views. */
internal fun List<DashboardViewOption>.findForPath(path: String): DashboardViewOption? {
    val normalizedPath = path.normalizedFrontendPath()
    return firstOrNull { it.rawPath.normalizedFrontendPath() == normalizedPath }
}

/** Shortcut paths are accepted with or without a leading or trailing slash. */
private fun String.normalizedFrontendPath(): String = trim().removePrefix(PATH_SEPARATOR).removeSuffix(PATH_SEPARATOR)
