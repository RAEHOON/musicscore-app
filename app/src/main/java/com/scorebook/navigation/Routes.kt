package com.scorebook.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data class ViewerRoute(
    val scoreId: Long,
    val setlistId: Long = -1,
    val setlistIndex: Int = -1,
)

@Serializable
data class PageEditorRoute(val scoreId: Long)

@Serializable
data object ImageScoreRoute

@Serializable
data object SetlistsRoute

@Serializable
data class SetlistDetailRoute(val setlistId: Long)

@Serializable
data object PracticeRoute

@Serializable
data object SettingsRoute
