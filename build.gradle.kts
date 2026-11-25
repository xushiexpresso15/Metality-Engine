plugins {
    base
}

allprojects {
    group = property("maven_group") as String
    version = property("mod_version") as String
}
