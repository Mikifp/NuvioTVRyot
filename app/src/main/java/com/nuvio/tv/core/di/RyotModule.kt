package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.ryot.OkHttpRyotTransport
import com.nuvio.tv.data.ryot.RyotAuthStore
import com.nuvio.tv.data.ryot.RyotRepository
import com.nuvio.tv.data.ryot.RyotSnapshotStorage
import com.nuvio.tv.data.ryot.RyotTrackingHistoryWriter
import com.nuvio.tv.data.ryot.RyotTrackingLibraryProvider
import com.nuvio.tv.data.ryot.RyotTrackingProgressProvider
import com.nuvio.tv.data.ryot.RyotTrackingProvider
import com.nuvio.tv.data.ryot.TmdbRyotIdMapper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object RyotModule {
    @Provides
    @Singleton
    fun authStore(@ApplicationContext context: Context, profileDataStore: ProfileDataStore): RyotAuthStore {
        // ProfileManager depends on every credential store, so follow the profile through the data store.
        val store = RyotAuthStore(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: RyotAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        auth: RyotAuthStore,
        @Named("customServerAuth") client: OkHttpClient,
        tmdb: TmdbService
    ): RyotRepository = RyotRepository(
        auth = auth,
        transport = OkHttpRyotTransport(client),
        ids = TmdbRyotIdMapper(tmdb),
        storage = RyotSnapshotStorage(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RyotTrackingModule {
    @Binds
    @IntoSet
    abstract fun bindProvider(provider: RyotTrackingProvider): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindProgress(provider: RyotTrackingProgressProvider): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindHistory(writer: RyotTrackingHistoryWriter): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindLibrary(provider: RyotTrackingLibraryProvider): TrackingLibraryProvider
}
