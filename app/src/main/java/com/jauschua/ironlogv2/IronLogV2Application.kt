package com.jauschua.ironlogv2

import android.app.Application
import com.jauschua.ironlogv2.di.AppContainer

class IronLogV2Application : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
