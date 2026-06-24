package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }

    /** Cross-tab pre-fill bridge. MovementDetail writes a movement id here; AutoregulateViewModel
     *  reads it once on init and resets to null. Simpler and more reliable than threading through
     *  savedStateHandle when the bottom-nav popUpTo(start)/saveState pattern reshuffles the back stack. */
    val autoregPrefill: MutableStateFlow<Int?> = MutableStateFlow(null)
}
