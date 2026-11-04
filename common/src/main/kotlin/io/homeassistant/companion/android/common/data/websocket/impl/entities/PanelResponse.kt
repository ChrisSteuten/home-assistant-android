package io.homeassistant.companion.android.common.data.websocket.impl.entities

import kotlinx.serialization.Serializable

/**
 * A frontend panel as returned by the `get_panels` websocket command.
 * The server only returns the panels the current user is allowed to see.
 */
@Serializable
data class PanelResponse(
    val componentName: String,
    val urlPath: String,
    val title: String? = null,
    val icon: String? = null,
)
