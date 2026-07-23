package com.jauschua.ironlogv2.di

import android.content.Context
import androidx.room.Room
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.local.CAPTURE_MIGRATION_1_2
import com.jauschua.ironlogv2.data.local.CaptureDatabase
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.CardioLogRepo
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.data.repo.GenerateRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.data.repo.MissedDaysRepo
import com.jauschua.ironlogv2.data.repo.NotesRepo
import com.jauschua.ironlogv2.data.repo.ReadinessRepo
import com.jauschua.ironlogv2.data.repo.WeakPointsRepo
import com.jauschua.ironlogv2.data.repo.WizardRepo
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(private val appContext: Context) {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
    val wizardRepo: WizardRepo by lazy { WizardRepo(apiClient) }
    val generateRepo: GenerateRepo by lazy { GenerateRepo(apiClient) }
    val notesRepo: NotesRepo by lazy { NotesRepo(apiClient) }
    val cardioLogRepo: CardioLogRepo by lazy { CardioLogRepo(apiClient) }
    val missedDaysRepo: MissedDaysRepo by lazy { MissedDaysRepo(apiClient) }
    val weakPointsRepo: WeakPointsRepo by lazy { WeakPointsRepo(apiClient) }
    val readinessRepo: ReadinessRepo by lazy { ReadinessRepo(apiClient) }
    val captureDb: CaptureDatabase by lazy {
        Room.databaseBuilder(appContext, CaptureDatabase::class.java, "capture.db")
            .addMigrations(CAPTURE_MIGRATION_1_2)
            .build()
    }
    val captureRepo: CaptureRepo by lazy {
        CaptureRepo(apiClient, captureDb.captureDao())
    }

    /** Cross-tab pre-fill bridge. MovementDetail writes a movement id here; AutoregulateViewModel
     *  reads it once on init and resets to null. Simpler and more reliable than threading through
     *  savedStateHandle when the bottom-nav popUpTo(start)/saveState pattern reshuffles the back stack. */
    val autoregPrefill: MutableStateFlow<Int?> = MutableStateFlow(null)

    /** In-memory phase-transition signal. Set by CaptureViewModel.finish() on a successful submit
     *  whose response carries a non-null phase_transition_available; read by TodayViewModel to show
     *  a confirmation banner; cleared on dismiss or confirm. Resets to null on process death, same
     *  characteristic as autoregPrefill above -- acceptable, the underlying gate condition re-derives
     *  on the athlete's next qualifying submit. */
    val pendingPhaseTransition: MutableStateFlow<String?> = MutableStateFlow(null)
}
