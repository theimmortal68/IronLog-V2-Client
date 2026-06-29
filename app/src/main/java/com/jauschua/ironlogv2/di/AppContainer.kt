package com.jauschua.ironlogv2.di

import android.content.Context
import androidx.room.Room
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.data.repo.WizardRepo
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(private val appContext: Context) {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
    val wizardRepo: WizardRepo by lazy { WizardRepo(apiClient) }
    val captureDb: CaptureDatabase by lazy {
        Room.databaseBuilder(appContext, CaptureDatabase::class.java, "capture.db").build()
    }
    val captureRepo: CaptureRepo by lazy {
        CaptureRepo(apiClient, captureDb.captureDao())
    }

    /** Cross-tab pre-fill bridge. MovementDetail writes a movement id here; AutoregulateViewModel
     *  reads it once on init and resets to null. Simpler and more reliable than threading through
     *  savedStateHandle when the bottom-nav popUpTo(start)/saveState pattern reshuffles the back stack. */
    val autoregPrefill: MutableStateFlow<Int?> = MutableStateFlow(null)
}
