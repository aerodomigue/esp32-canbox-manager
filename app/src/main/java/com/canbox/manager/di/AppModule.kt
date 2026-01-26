package com.canbox.manager.di

import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbSerialManager
import com.canbox.manager.ui.screens.calibration.CalibrationViewModel
import com.canbox.manager.ui.screens.canconfig.CanConfigViewModel
import com.canbox.manager.ui.screens.debug.DebugViewModel
import com.canbox.manager.ui.screens.live.LiveViewModel
import com.canbox.manager.ui.screens.update.UpdateViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // USB Serial
    single { UsbSerialManager(androidContext()) }

    // Repository
    single { CanBoxRepository(get()) }

    // ViewModels
    viewModel { LiveViewModel(get()) }
    viewModel { CanConfigViewModel(get()) }
    viewModel { CalibrationViewModel(get()) }
    viewModel { UpdateViewModel(get()) }
    viewModel { DebugViewModel(get()) }
}
