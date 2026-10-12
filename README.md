# Every_Day_is_a_Good_Article-desktop

Desktop companion PoC preparation, Windows first, Kotlin + planned Compose Multiplatform.
The phone is the authoritative data source.

See [PoC plan](docs/POC-PLAN.md) for scope, safety boundaries, hardware acceptance and limitations.

This branch contains a synthetic common session model and six automated tests.
It does not yet detect USB/MTP, transfer phone files, lock Android editing, persist recovery data or provide a Compose UI.
Android implementation and real data remain private.

JDK 17 + Gradle 8.12.1: `gradle jvmTest`.
