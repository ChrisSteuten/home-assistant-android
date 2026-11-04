package io.homeassistant.companion.android.settings.shortcuts.legacy.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.settings.shortcuts.legacy.DashboardOption
import io.homeassistant.companion.android.settings.shortcuts.legacy.DashboardViewOption
import io.homeassistant.companion.android.settings.shortcuts.legacy.findForPath
import io.homeassistant.companion.android.util.compose.ExposedDropdownMenu

/**
 * Lets the user pick one of the [dashboards] and optionally one of its views, instead of typing the path.
 * The current selection is derived from [path], so a typed path pointing to a dashboard is reflected too.
 *
 * @param viewsOf the views of a dashboard, `null` while they are not loaded
 * @param onDashboardSelected called with the dashboard the [path] points to, to load its views
 * @param onPathSelected called with the path of the picked dashboard or view
 */
@Composable
internal fun DashboardPicker(
    dashboards: List<DashboardOption>,
    viewsOf: (DashboardOption) -> List<DashboardViewOption>?,
    path: String,
    onDashboardSelected: (DashboardOption) -> Unit,
    onPathSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDashboard = remember(dashboards, path) { dashboards.findForPath(path) }
    LaunchedEffect(selectedDashboard) {
        selectedDashboard?.let(onDashboardSelected)
    }

    Column(modifier = modifier) {
        val defaultDashboardTitle = stringResource(R.string.shortcut_dashboard_default)
        ExposedDropdownMenu(
            label = stringResource(R.string.lovelace),
            keys = dashboards.map { it.title ?: defaultDashboardTitle },
            currentIndex = selectedDashboard?.let { dashboards.indexOf(it) },
            onSelected = { onPathSelected(dashboards[it].rawPath) },
            modifier = Modifier.fillMaxWidth(),
        )

        val views = selectedDashboard?.let(viewsOf)
        if (!views.isNullOrEmpty()) {
            DashboardViewPicker(
                views = views,
                path = path,
                onPathSelected = onPathSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DashboardViewPicker(
    views: List<DashboardViewOption>,
    path: String,
    onPathSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedView = remember(views, path) { views.findForPath(path) }
    val keys = views.map { it.title ?: stringResource(R.string.shortcut_dashboard_view_n, it.number) }
    ExposedDropdownMenu(
        label = stringResource(R.string.shortcut_dashboard_view),
        keys = keys,
        currentIndex = selectedView?.let { views.indexOf(it) },
        onSelected = { onPathSelected(views[it].rawPath) },
        modifier = modifier,
    )
}
