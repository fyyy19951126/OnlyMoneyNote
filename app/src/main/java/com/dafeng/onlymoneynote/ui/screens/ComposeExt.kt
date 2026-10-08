package com.dafeng.onlymoneynote.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一的 collectAsState 包装。
 * 单独抽出来是为了以后换生命周期感知实现时只改一处。
 */
@Composable
internal fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()
