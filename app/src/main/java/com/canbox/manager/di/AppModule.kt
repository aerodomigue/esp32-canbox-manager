package com.canbox.manager.di

import com.canbox.manager.data.github.GitHubApi
import com.canbox.manager.data.github.GitHubRepository
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbSerialManager
import com.canbox.manager.ui.screens.calibration.CalibrationViewModel
import com.canbox.manager.ui.screens.canconfig.CanConfigViewModel
import com.canbox.manager.ui.screens.debug.DebugViewModel
import com.canbox.manager.ui.screens.live.LiveViewModel
import com.canbox.manager.ui.screens.update.UpdateViewModel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

val appModule = module {
    // USB Serial
    single { UsbSerialManager(androidContext()) }

    // Networking
    single {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(get())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    single { get<Retrofit>().create(GitHubApi::class.java) }

    // Repositories
    single { CanBoxRepository(get()) }
    single { GitHubRepository(get()) }

    // ViewModels
    viewModel { LiveViewModel(get()) }
    viewModel { CanConfigViewModel(get(), get()) }
    viewModel { CalibrationViewModel(get()) }
    viewModel { UpdateViewModel(get(), get()) }
    viewModel { DebugViewModel(get()) }
}
