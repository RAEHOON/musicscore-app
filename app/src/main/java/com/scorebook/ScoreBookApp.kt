package com.scorebook

import android.app.Application
import android.content.ComponentCallbacks2
import com.scorebook.core.render.PageRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ScoreBookApp : Application() {

    @Inject lateinit var renderer: PageRenderer

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }

    // 메모리가 모자라거나 앱이 화면에서 내려가면 페이지 비트맵 캐시를 비운다(다시 보면 새로 그린다)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::renderer.isInitialized) renderer.clearMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::renderer.isInitialized) renderer.clearMemory()
    }
}
