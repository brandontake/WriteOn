package com.ezpnix.writeon.data.local.database

import android.app.Application
import android.content.Context

/**
 * T5: the one place presentation code is allowed to ask for the backup database.
 *
 * The four workers used to cast `applicationContext as Application` and construct
 * [NoteDatabaseProvider] themselves, which put knowledge of the Room provider - and its singleton
 * connection lifecycle - into the presentation layer. Keeping the construction behind this factory
 * means a worker asks for "the database used by backup" instead of assembling infrastructure.
 */
object BackupDatabase {

    fun provider(context: Context): NoteDatabaseProvider =
        NoteDatabaseProvider(context.applicationContext as Application)
}
