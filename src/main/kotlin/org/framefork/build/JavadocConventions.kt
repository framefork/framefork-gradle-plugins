package org.framefork.build

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.CoreJavadocOptions
import org.gradle.kotlin.dsl.withType

/** The javadoc options the suite passes. A module drops the doclint one with `addBooleanOption(DOCLINT, false)`. */
internal object JavadocOptions {

    /** Every doclint group except `missing` — drops the `warning: no comment` noise, keeps the HTML/syntax/reference checks. */
    const val DOCLINT = "Xdoclint:all,-missing"

    /** Stops javadoc from copying the DejaVu web fonts (~4 MB) into the output, and so into every `-javadoc.jar`. */
    const val NO_FONTS = "-no-fonts"

    /** The first javadoc release that knows `--no-fonts` (and the first that bundles the fonts at all). */
    const val NO_FONTS_SINCE_JDK = 23
}

/**
 * Javadoc defaults for every `Javadoc` task of a library module, published or internal (applied by both `library-*`
 * plugins through [configureLibraryBaseConventions], so `./gradlew javadoc` behaves the same on a `testing/` module as on
 * the `-javadoc.jar` of a published one).
 *
 *  - **`-Xdoclint:all,-missing`** — javadoc's default doclint includes the `missing` group, which warns about every
 *    public member without a doc comment (down to implicit default constructors). That is noise for a library, not a
 *    defect. The other groups stay on: malformed HTML, bad tags and broken `{@link}` references still fail the build or
 *    warn exactly as before.
 *  - **`--no-fonts`**, only when the task's javadoc tool is JDK 23+ — earlier javadoc rejects the flag (`invalid flag`)
 *    and bundles no fonts anyway. The tool version is known only once the toolchain resolves, and the javadoc options are
 *    not lazy, so the flag is added by a task action right before javadoc runs: no `.get()` at configuration time. The
 *    resolved javadoc tool (its language version included) is already a task input, so up-to-date checks and the build
 *    cache key still tell a JDK 23+ run from an older one.
 *
 * `-quiet` is not added: Gradle already passes it by default (`JavadocOutputLevel.QUIET`), and it only hides javadoc's
 * progress lines, never warnings or errors.
 */
internal fun Project.configureJavadocConventions() {
    tasks.withType<Javadoc>().configureEach {
        (options as CoreJavadocOptions).addBooleanOption(JavadocOptions.DOCLINT, true)
        doFirst(AddNoFontsWhenSupported())
    }
}

/** Stateless, so the configuration cache serializes it without capturing anything from the project. */
private class AddNoFontsWhenSupported : Action<Task> {

    override fun execute(task: Task) {
        val javadoc = task as Javadoc
        val javadocVersion = javadoc.javadocTool.orNull?.metadata?.languageVersion?.asInt() ?: return
        if (javadocVersion >= JavadocOptions.NO_FONTS_SINCE_JDK) {
            (javadoc.options as CoreJavadocOptions).addBooleanOption(JavadocOptions.NO_FONTS, true)
        }
    }
}
