package org.framefork.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * No-op shared build service used purely as a one-permit semaphore to serialize test execution across the whole build.
 *
 * Registered once (idempotent by name) with `maxParallelUsages = 1`; every `Test` task declares `usesService(...)` on
 * it, so Gradle's scheduler grants the single permit to at most one test JVM at a time while every other task keeps
 * running in parallel. See [FrameforkProjectInitAction], where the `framefork { sequentialTests }` knob wires it.
 *
 * The contract is **mutual exclusion, not ordering**: two test tasks never overlap, but there is no deterministic
 * cross-module run order — a test must not assume it runs before or after any other module's tests. A shared BuildService
 * is chosen over a `mustRunAfter` chain because `mustRunAfter` only orders tasks that are both in the graph, so running
 * a subset or having up-to-date tasks silently breaks the chain; the service is the CC-safe, Isolated-Projects-legal
 * shared-resource API and is enforced by the scheduler against whatever actually runs.
 */
abstract class TestSerializerService : BuildService<BuildServiceParameters.None>
