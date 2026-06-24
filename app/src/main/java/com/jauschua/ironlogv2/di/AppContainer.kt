package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    // Task 4 adds:
    //   val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    //   val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
}
