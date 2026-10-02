package com.scorebook.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 휴대폰 세로 화면처럼 폭이 좁은 화면(600dp 미만)인지 */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600
