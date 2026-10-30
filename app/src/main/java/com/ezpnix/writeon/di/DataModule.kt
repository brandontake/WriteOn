package com.ezpnix.writeon.di

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.data.repository.ImportExportRepository
import com.ezpnix.writeon.data.repository.NoteRepositoryImpl
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.domain.repository.NoteFileReader
import com.ezpnix.writeon.domain.repository.NoteRepository
import com.ezpnix.writeon.domain.repository.SettingsRepository
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.widget.GlanceWidgetRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
annotation class WidgetCoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {

    /**
     * Single-threaded view over the shared IO pool, used to serialize database work.
     *
     * This used to be `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`, and nothing
     * ever closed the executor, so it leaked a thread for the life of the process. `limitedParallelism`
     * keeps the "one job at a time" behaviour this app wanted while running on a pool that
     * kotlinx-coroutines manages and can grow and shrink on its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    fun provideExecutorCoroutineDispatcher(): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(1)

    /**
     * Scope for widget and other fire-and-forget work that must outlive the caller's screen.
     *
     * Shares the single-threaded dispatcher above so widget updates queue behind database work
     * instead of competing with it, and so no second executor has to be leaked for them.
     */
    @Provides
    @Singleton
    @WidgetCoroutineScope
    fun providesWidgetCoroutineScope(
        executorCoroutineDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(executorCoroutineDispatcher)

    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Guards database replacement so an export and an import cannot interleave.
     *
     * This was unscoped, so Hilt handed every injection site its own `Mutex` and the mutual
     * exclusion it was written for never actually held. It has to be a singleton for the lock to
     * mean anything.
     */
    @Provides
    @Singleton
    fun provideMutex(): Mutex = Mutex()

    @Provides
    @Singleton
    fun provideNoteDatabaseProvider(application: Application): NoteDatabaseProvider = NoteDatabaseProvider(application)

    @Provides
    @Singleton
    fun provideNoteRepository(noteDatabaseProvider: NoteDatabaseProvider): NoteRepositoryImpl {
        return NoteRepositoryImpl(noteDatabaseProvider)
    }

    /** The domain layer depends on the interface; this binds it to the one real implementation. */
    @Provides
    @Singleton
    fun provideNoteRepositoryInterface(impl: NoteRepositoryImpl): NoteRepository = impl

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepositoryImpl {
        return SettingsRepositoryImpl(context)
    }

    /** Domain-layer binding for settings; a fake implementation makes the migration testable. */
    @Provides
    @Singleton
    fun provideSettingsRepositoryInterface(impl: SettingsRepositoryImpl): SettingsRepository = impl

    /** Domain-layer binding for "turn this Uri into a note"; a fake makes import testable. */
    @Provides
    @Singleton
    fun provideNoteFileReader(impl: ImportExportRepository): NoteFileReader = impl

    @Provides
    @Singleton
    fun provideBackupRepository(
        noteDatabaseProvider: NoteDatabaseProvider,
        application: Application,
        mutex: Mutex,
        executorCoroutineDispatcher: CoroutineDispatcher,
    ): ImportExportRepository {
        return ImportExportRepository(
            provider = noteDatabaseProvider,
            context = application,
            mutex = mutex,
            dispatcher = executorCoroutineDispatcher
        )
    }

    @Provides
    @Singleton
    fun provideWidgetRefresher(refresher: GlanceWidgetRefresher): WidgetRefresher = refresher

    /** The Compose-state registry, behind the domain-facing interface. */
    @Provides
    @Singleton
    fun provideAttachmentIndex(): AttachmentIndex = AttachmentRegistry

    @Provides
    @Singleton
    fun provideMutableVaultPassword(): StringBuilder {
        return StringBuilder()
    }

    @Provides
    @Singleton
    fun provideEncryptionHelper(mutableVaultPassword: StringBuilder): EncryptionHelper {
        return EncryptionHelper(mutableVaultPassword)
    }

    @Provides
    fun provideHandler(): Handler {
        return Handler(Looper.getMainLooper())
    }
}