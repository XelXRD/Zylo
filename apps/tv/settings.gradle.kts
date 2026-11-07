// repo1.maven.org is Maven Central under its other name (some networks only allow that one).
pluginManagement { repositories { google(); maven("https://repo1.maven.org/maven2") } }
dependencyResolutionManagement { repositories { google(); maven("https://repo1.maven.org/maven2") } }
rootProject.name = "zylo-tv"
