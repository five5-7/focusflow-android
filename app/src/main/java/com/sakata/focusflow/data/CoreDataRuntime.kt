package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.PrototypeStore

/** Product migration remains disabled until the activation checkpoint is explicitly approved. */
object CoreDataRuntimePolicy {
    const val ACTIVATION_ENABLED: Boolean = false
}

sealed interface CoreDataRuntimeResolution {
    val decision: CoreDataActivationDecision

    data class Ready(
        override val decision: CoreDataActivationDecision,
        val repository: CoreDataRepository
    ) : CoreDataRuntimeResolution

    data class Blocked(
        override val decision: CoreDataActivationDecision
    ) : CoreDataRuntimeResolution
}

/**
 * Process-wide composition boundary. Source selection always completes before either writer is
 * constructed, and the result is immutable for the life of the process.
 */
class CoreDataRuntimeCompositionRoot(
    private val selectSource: () -> CoreDataActivationDecision,
    private val legacyRepositoryFactory: () -> CoreDataRepository,
    private val roomRepositoryFactory: () -> CoreDataRepository
) {
    @Volatile
    private var cached: CoreDataRuntimeResolution? = null

    fun resolve(): CoreDataRuntimeResolution = cached ?: synchronized(this) {
        cached ?: resolveOnce().also { cached = it }
    }

    private fun resolveOnce(): CoreDataRuntimeResolution {
        val decision = try {
            selectSource()
        } catch (error: Exception) {
            return blocked("source selection failed: ${error.javaClass.simpleName}")
        }
        if (decision.source == CoreDataRuntimeSource.NONE) {
            return CoreDataRuntimeResolution.Blocked(decision)
        }
        val repository = try {
            when (decision.source) {
                CoreDataRuntimeSource.LEGACY -> legacyRepositoryFactory()
                CoreDataRuntimeSource.ROOM -> roomRepositoryFactory()
                CoreDataRuntimeSource.NONE -> error("blocked source cannot create a repository")
            }
        } catch (error: Exception) {
            return blocked("repository assembly failed: ${error.javaClass.simpleName}")
        }
        if (repository.source != decision.source) {
            return blocked("selected source and repository source disagree")
        }
        repository.ensureTaskHistoryMigrated()
        return CoreDataRuntimeResolution.Ready(decision, repository)
    }

    private fun blocked(message: String) = CoreDataRuntimeResolution.Blocked(
        CoreDataActivationDecision(
            source = CoreDataRuntimeSource.NONE,
            status = CoreDataActivationStatus.BLOCKED_RUNTIME_ASSEMBLY,
            message = message
        )
    )
}

interface CoreDataRuntimeOwner {
    val coreDataRuntime: CoreDataRuntimeCompositionRoot
}

/** Accesses the single root owned by [android.app.Application]; it never creates a repository. */
object CoreDataRuntimeAccess {
    fun resolve(context: Context): CoreDataRuntimeResolution {
        val owner = context.applicationContext as? CoreDataRuntimeOwner
            ?: return CoreDataRuntimeResolution.Blocked(
                CoreDataActivationDecision(
                    source = CoreDataRuntimeSource.NONE,
                    status = CoreDataActivationStatus.BLOCKED_RUNTIME_ASSEMBLY,
                    message = "application does not own the core-data runtime"
                )
            )
        return owner.coreDataRuntime.resolve()
    }
}

/** Android dependency assembly. This is called only by FocusFlowApplication. */
object AndroidCoreDataRuntimeFactory {
    fun create(context: Context): CoreDataRuntimeCompositionRoot {
        val appContext = context.applicationContext
        val database = LazyCoreDataDatabase(appContext)
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = { LegacyPreferencesReader.fromContext(appContext).read() },
            migrationRunner = CoreDataMigrationRunner {
                LegacyDataImporter(
                    reader = LegacyPreferencesReader.fromContext(appContext),
                    store = RoomLegacyMigrationStore(database.get()),
                    marker = SharedPreferencesMigrationMarker(appContext)
                ).importIfNeeded()
            },
            roomVerifier = CoreDataRoomActivationVerifier {
                DatabaseCoreDataRoomActivationVerifier(database.get()).verify()
            },
            activationStore = SharedPreferencesCoreDataActivationStore(appContext)
        )
        return CoreDataRuntimeCompositionRoot(
            selectSource = {
                coordinator.selectSource(CoreDataRuntimePolicy.ACTIVATION_ENABLED)
            },
            legacyRepositoryFactory = {
                LegacyCoreDataRepository(PrototypeStore(appContext))
            },
            roomRepositoryFactory = {
                val room = database.get()
                val store = DatabaseRoomCoreDataWriteStore(room)
                RoomCoreDataRepository(
                    reader = RoomCoreDataReadRepository(store),
                    writer = RoomCoreDataWriteRepository(store)
                )
            }
        )
    }
}

private class LazyCoreDataDatabase(private val context: Context) {
    @Volatile
    private var database: FocusFlowDatabase? = null

    fun get(): FocusFlowDatabase = database ?: synchronized(this) {
        database ?: FocusFlowDatabase.create(context).also { database = it }
    }
}
