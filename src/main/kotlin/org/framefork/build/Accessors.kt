package org.framefork.build

import org.gradle.api.Project

/**
 * The single accessor for the per-project [FrameforkProjectExtension] the settings plugin propagates to every project.
 * Every library convention helper reads its knobs through here, so the "extension is missing" diagnostic — the signal
 * that a build forgot to apply the `org.framefork.build` settings plugin — is worded once.
 */
internal fun Project.frameforkProjectExtension(): FrameforkProjectExtension =
    extensions.findByType(FrameforkProjectExtension::class.java)
        ?: error(
            "The 'frameforkProject' extension is missing on project '$path'. " +
                "Apply the 'org.framefork.build' settings plugin in settings.gradle.kts so it can propagate the framefork {} knobs to every project.",
        )
