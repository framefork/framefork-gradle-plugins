package org.framefork.build

import org.gradle.api.Project

/**
 * Opt-in Gradle dependency locking for every library module, gated on the `framefork { dependencyLocking }` knob.
 *
 * When enabled, all configurations are locked in the DEFAULT lock mode (never STRICT): a normal build resolves against
 * the committed `gradle.lockfile` but a fresh module without a lockfile still resolves, so adopting locking never blocks
 * a first build. Re-locking is done per module through the registered `resolveAndLockAll` task, because in a multi-module
 * build a root `dependencies --write-locks` re-locks nothing — the root has no resolvable configurations. Running
 * `./gradlew resolveAndLockAll --write-locks` then rewrites every module's `gradle.lockfile`.
 *
 * The NORMAL build stays configuration-cache-compatible; only the maintenance `resolveAndLockAll` task self-marks as
 * incompatible (it filters configurations and resolves them at execution time), so it must be run with the config cache
 * off, but it never taints the everyday build.
 */
internal fun Project.configureDependencyLocking() {
    val ext = frameforkProjectExtension()

    if (!ext.dependencyLocking.getOrElse(false)) {
        return
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Filters configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "$path must be run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}
