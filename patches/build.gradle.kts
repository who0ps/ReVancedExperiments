group = "li.auna"

patches {
    about {
        name = "ReVanced Experiments"
        description = "A collection of patches for ReVanced"
        source = "https://github.com/who0ps/ReVancedExperiments"
        author = "AunAli"
        contact = "hello@auna.li"
        website = "https://auna.li"
        license = "GNU General Public License v3.0; Telegram push patch based on AGPL-3.0-or-later source"
    }
}

dependencies {
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}
