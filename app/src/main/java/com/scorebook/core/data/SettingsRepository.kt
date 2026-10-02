package com.scorebook.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.scorebook.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[VIEW_MODE] = next.viewMode.name
            prefs[TWO_PAGE_LANDSCAPE] = next.twoPageInLandscape
            prefs[COLOR_MODE] = next.colorMode.name
            prefs[AUTO_CROP] = next.autoCropMargins
            prefs[TAP_ZONES] = next.tapZonesEnabled
            prefs[TAP_ZONE_FRACTION] = next.tapZoneFraction
            prefs[TURN_ANIMATION] = next.pageTurnAnimation
            prefs[REMEMBER_LAST_PAGE] = next.rememberLastPage
            prefs[VOLUME_KEYS] = next.volumeKeysTurnPages
            prefs[PALM_REJECTION] = next.palmRejection
            prefs[STYLUS_BUTTON_ERASES] = next.stylusButtonErases
            prefs[ERASER_MODE] = next.eraserMode.name
            prefs[ERASER_SIZE] = next.eraserSize
            prefs[FACE_MODE] = next.faceGestureMode.name
            prefs[FACE_SENSITIVITY] = next.faceGestureSensitivity
            prefs[AUTO_TURN_SECONDS] = next.autoTurnSeconds
            prefs[AUTO_SCROLL_SPEED] = next.autoScrollSpeed
            prefs[METRONOME_BPM] = next.metronomeBpm
            prefs[METRONOME_BEATS] = next.metronomeBeats
            prefs[LIBRARY_SORT] = next.librarySort.name
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            viewMode = enumOr(this[VIEW_MODE], d.viewMode),
            twoPageInLandscape = this[TWO_PAGE_LANDSCAPE] ?: d.twoPageInLandscape,
            colorMode = enumOr(this[COLOR_MODE], d.colorMode),
            autoCropMargins = this[AUTO_CROP] ?: d.autoCropMargins,
            tapZonesEnabled = this[TAP_ZONES] ?: d.tapZonesEnabled,
            tapZoneFraction = this[TAP_ZONE_FRACTION] ?: d.tapZoneFraction,
            pageTurnAnimation = this[TURN_ANIMATION] ?: d.pageTurnAnimation,
            rememberLastPage = this[REMEMBER_LAST_PAGE] ?: d.rememberLastPage,
            volumeKeysTurnPages = this[VOLUME_KEYS] ?: d.volumeKeysTurnPages,
            palmRejection = this[PALM_REJECTION] ?: d.palmRejection,
            stylusButtonErases = this[STYLUS_BUTTON_ERASES] ?: d.stylusButtonErases,
            eraserMode = enumOr(this[ERASER_MODE], d.eraserMode),
            eraserSize = this[ERASER_SIZE] ?: d.eraserSize,
            faceGestureMode = enumOr(this[FACE_MODE], d.faceGestureMode),
            faceGestureSensitivity = this[FACE_SENSITIVITY] ?: d.faceGestureSensitivity,
            autoTurnSeconds = this[AUTO_TURN_SECONDS] ?: d.autoTurnSeconds,
            autoScrollSpeed = this[AUTO_SCROLL_SPEED] ?: d.autoScrollSpeed,
            metronomeBpm = this[METRONOME_BPM] ?: d.metronomeBpm,
            metronomeBeats = this[METRONOME_BEATS] ?: d.metronomeBeats,
            librarySort = enumOr(this[LIBRARY_SORT], d.librarySort),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    private companion object {
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val TWO_PAGE_LANDSCAPE = booleanPreferencesKey("two_page_landscape")
        val COLOR_MODE = stringPreferencesKey("color_mode")
        val AUTO_CROP = booleanPreferencesKey("auto_crop")
        val TAP_ZONES = booleanPreferencesKey("tap_zones")
        val TAP_ZONE_FRACTION = floatPreferencesKey("tap_zone_fraction")
        val TURN_ANIMATION = booleanPreferencesKey("turn_animation")
        val REMEMBER_LAST_PAGE = booleanPreferencesKey("remember_last_page")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val PALM_REJECTION = booleanPreferencesKey("palm_rejection")
        val STYLUS_BUTTON_ERASES = booleanPreferencesKey("stylus_button_erases")
        val ERASER_MODE = stringPreferencesKey("eraser_mode")
        val ERASER_SIZE = floatPreferencesKey("eraser_size")
        val FACE_MODE = stringPreferencesKey("face_mode")
        val FACE_SENSITIVITY = floatPreferencesKey("face_sensitivity")
        val AUTO_TURN_SECONDS = intPreferencesKey("auto_turn_seconds")
        val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
        val METRONOME_BPM = intPreferencesKey("metronome_bpm")
        val METRONOME_BEATS = intPreferencesKey("metronome_beats")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
    }
}
