// Root build file for the MachineConstruct multi-project.
//
// Shared config lives in each subproject's own build.gradle.kts (mirrors the
// proven panel-bridge build, avoids Kotlin-DSL subprojects-block pitfalls).
//
//   :engine  -> MachineConstruct.jar  ("the system")
//   :content -> Foundry.jar           ("the machines & items")
//
// P0: plain jars, no shaded deps. packetevents + Shadow relocation arrive in P1.
//
//   ./gradlew :engine:jar :content:jar
