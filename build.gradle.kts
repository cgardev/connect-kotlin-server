allprojects {
    group = "com.metalogenia"
    // Gradle reads 'version' from gradle.properties automatically,
    // but this ensures it is applied to the project object explicitly if needed.
    version = providers.gradleProperty("version").getOrElse("0.0.0")
}
