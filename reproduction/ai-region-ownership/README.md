# Folia AI region ownership reproductions

This directory contains deterministic native reproductions for three independent fixes against Folia `ver/26.3.x` at `597e41371802648e0455fc1571192ee1c83fca2b`. The probe runs real server behaviors on Folia owner threads, invokes real events and `teleportAsync`, and exercises `Level.guardEntityTick`. Autonomous AI is disabled so each precondition can be established explicitly.

| Case | Independent fix commit | Fixture |
| --- | --- | --- |
| profession | `974e5240f8c69b74e2ae946ca705115e229502ed` | [VillagerProfessionRegionNativeTest.kt](native-probe/src/main/kotlin/dev/folia/probe/VillagerProfessionRegionNativeTest.kt) |
| bed | `585c34c38236667b4f0704eac3b918121b4636b1` | [VillagerJumpBedNativeTest.kt](native-probe/src/main/kotlin/dev/folia/probe/VillagerJumpBedNativeTest.kt) |
| item | `4893464f54bc1e86e458b11379966eaa26e8bf86` | [WantedItemRegionNativeTest.kt](native-probe/src/main/kotlin/dev/folia/probe/WantedItemRegionNativeTest.kt) |

## Build

Use Git and JDK 25 or later. Clone the reproduction branch and create a baseline checkout:

```sh
git clone --branch repro/folia-ai-region-guards-26.3 https://github.com/PluginsKers/Folia.git folia-region-repro
git -C folia-region-repro worktree add --detach ../folia-baseline 597e41371802648e0455fc1571192ee1c83fca2b
cd folia-baseline
./gradlew applyAllPatches createPaperclipJar -PpublishDevBundle publishDevBundlePublicationToMavenLocal :folia-api:publishMavenPublicationToMavenLocal
./gradlew -p ../folia-region-repro/reproduction/ai-region-ownership/native-probe jar
```

Copy the generated paperclip JAR from `folia-baseline/folia-server/build/libs/` to the baseline run directory as `folia.jar`. The probe is `folia-region-repro/reproduction/ai-region-ownership/native-probe/build/libs/folia-ai-region-probe.jar`; use this same JAR for both server variants.

From `folia-region-repro`, create a fixed checkout using the chosen commit from the table, then run `./gradlew applyAllPatches createPaperclipJar` in that checkout. For example, for the bed case:

```sh
git worktree add --detach ../folia-fixed 585c34c38236667b4f0704eac3b918121b4636b1
cd ../folia-fixed
./gradlew applyAllPatches createPaperclipJar
```

Copy its paperclip JAR to a separate fixed run directory as `folia.jar`. On Windows, use `gradlew.bat` for the Gradle commands. The recorded runs used cached Gradle 9.8.0; the repository wrapper remains at 9.8.1. The exact combined source used for the recorded fixed runtime is `09b3c7bedac1d40ba327d490131a9a6f08d347c5`, also reachable from this branch. Each independent commit has the same upstream parent and produces its corresponding recorded source diff.

## Run

Use one fresh, disposable server directory per case and variant. The fixture creates blocks and entities, drives internal AI, and shuts down the server. Place only `folia-ai-region-probe.jar` in `plugins/`. These runs need no player login or other plugin.

Complete Minecraft's normal EULA setup. Keep the Nether enabled. Set these values in `server.properties`:

```properties
server-ip=127.0.0.1
online-mode=false
server-port=25941
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
pause-when-empty-seconds=-1
```

The probe enforces a loopback-only offline server for this isolated, playerless fixture. Use different free ports for simultaneous runs. For these small runs, `config/paper-global.yml` can use:

```yaml
threaded-regions:
  threads: 2
chunk-system:
  worker-threads: 2
```

Run the chosen case in its server directory, replacing `profession` with `bed` or `item` as appropriate:

```sh
java -Xms1G -Xmx2G -Duser.language=en -Dfolia.probe=profession -jar folia.jar --nogui
```

Read `logs/latest.log`. The fixtures log assertion failures and shut down normally, so exit code zero does not establish success. Expected final markers are `VILLAGER_PROFESSION_REGION_NATIVE_PASS`, `VILLAGER_JUMP_BED_NATIVE_PASS`, or `WANTED_ITEM_REGION_NATIVE_PASS cases=16`. The unchanged baseline is expected to log the corresponding failure marker.

## Evidence

The [profession](evidence/profession/), [bed](evidence/bed/), and [item](evidence/item/) directories contain the sanitized production stack, every captured production frame in entry-to-fault order, current source diff, complete current baseline/fixed logs, and the current reproduction stack and call chain. Production stacks are from `26.2-7-14b7fee`; their line numbers and method names describe that version. Production locations are omitted. The current reproduction logs are from 26.3 at the pinned base above.

[observations.json](evidence/observations.json) records artifact/log SHA-256 values, observed markers, and bounds. These hashes identify the recorded artifacts; a new build can differ because of build metadata. No server binaries are redistributed here.

In 26.3 the profession behavior removed `assignProfessionWhenSpawned`. A distant same-world site is therefore a distance-gate control; the current failure uses a foreign-dimension `GlobalPos` at nearby numeric coordinates. The bed helper is now `isJumpable` and uses `VILLAGER_BABIES_CAN_JUMP_ON_BED`. The item behavior retains the new `canPickUpLoot` condition.

The recorded fixed runtime contains all three independent guards. Each fixture directly invokes its own behavior. Separate single-fix runtime JARs and complete autonomous Brain/passenger journeys were not recorded. The profession baseline stops at the dimension failure after its distance control; the bed baseline stops at the mounted unloaded-bed failure after five controls. Later controls for those cases are fixed-only observations. All sixteen item cases run on both variants. Windows OSHI/Perflib startup diagnostics precede the fixture execution in these logs.
