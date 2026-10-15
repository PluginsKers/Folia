package dev.folia.probe

import org.bukkit.plugin.java.JavaPlugin

class FoliaAiRegionProbe : JavaPlugin() {
    override fun onEnable() {
        check(server.ip == "127.0.0.1" && !server.onlineMode) { "Use an isolated localhost offline server" }
        when (System.getProperty("folia.probe")) {
            "profession" -> VillagerProfessionRegionNativeTest(this).run()
            "bed" -> VillagerJumpBedNativeTest(this).run()
            "item" -> WantedItemRegionNativeTest(this).run()
            else -> error("Set -Dfolia.probe=profession, bed or item on an isolated server")
        }
    }
}
