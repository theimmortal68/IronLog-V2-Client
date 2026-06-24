package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
}
