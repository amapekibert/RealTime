package org.titago

import com.google.gson.JsonParser
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RealTime : JavaPlugin(), Listener {
    private data class WorldSettings(val timezone: ZoneId, val autoTimezone: Boolean)
    private data class CachedZone(val timezone: ZoneId?, val expiresAt: Instant)

    private var task: ScheduledTask? = null
    @Volatile private var worldSettings: Map<String, WorldSettings> = emptyMap()
    @Volatile private var loadedWorldNames: List<String> = emptyList()
    private val zoneCache = ConcurrentHashMap<String, CachedZone>()
    private val lookupsInProgress = ConcurrentHashMap.newKeySet<String>()
    private val overriddenPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val timezoneSuggestions = listOf("auto", "GMT-5") + ZoneId.getAvailableZoneIds().sorted()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun onEnable() {
        saveDefaultConfig()
        loadWorldSettings()
        registerCommand("realtime", "Configure real-world time per world", object : BasicCommand {
            override fun execute(source: CommandSourceStack, args: Array<out String>) {
                executeRealtime(source.sender, args)
            }

            override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> =
                suggestRealtime(args)

            override fun permission(): String = "realtime.admin"
        })
        server.pluginManager.registerEvents(this, this)
        task = server.globalRegionScheduler.runAtFixedRate(this, { _ -> updateWorldsAndPlayers() }, 1L, 100L)
        logger.info("RealTime enabled successfully.")
    }

    override fun onDisable() {
        task?.cancel()
        overriddenPlayers.clear()
        zoneCache.clear()
        logger.info("RealTime has been disabled.")
    }

    private fun executeRealtime(sender: CommandSender, args: Array<out String>) {
        if (args.size == 1 && args[0].equals("reload", ignoreCase = true)) {
            server.globalRegionScheduler.run(this) { _ ->
                reloadConfig()
                loadWorldSettings()
                updateWorldsAndPlayers()
                reply(sender, "Configuration reloaded.")
            }
            return
        }
        if (args.size in 2..3 && args[0].equals("set", ignoreCase = true)) {
            val value = args[1]
            val auto = value.equals("auto", ignoreCase = true)
            val timezone = if (auto) null else try {
                ZoneId.of(value)
            } catch (_: Exception) {
                sender.sendMessage("Invalid timezone: $value")
                return
            }
            val target = args.getOrNull(2) ?: (sender as? Player)?.world?.name
            if (target == null) {
                sender.sendMessage("Console usage: /realtime set <auto|timezone> <world|all>")
                return
            }
            server.globalRegionScheduler.run(this) { _ ->
                val worldNames = if (target.equals("all", ignoreCase = true)) {
                    Bukkit.getWorlds().map { it.name }
                } else {
                    listOfNotNull(Bukkit.getWorld(target)?.name)
                }
                if (worldNames.isEmpty()) {
                    reply(sender, "World not found: $target")
                    return@run
                }
                for (name in worldNames) {
                    if (auto) {
                        if (!config.contains("worlds.$name.timezone")) {
                            config.set("worlds.$name.timezone", "UTC")
                        }
                        config.set("worlds.$name.auto-timezone", true)
                    } else {
                        config.set("worlds.$name.timezone", timezone.toString())
                        config.set("worlds.$name.auto-timezone", false)
                    }
                }
                saveConfig()
                loadWorldSettings()
                updateWorldsAndPlayers()
                reply(sender, "Updated ${worldNames.size} world(s): ${if (auto) "auto" else timezone}.")
            }
            return
        }
        sender.sendMessage("Usage: /realtime reload")
        sender.sendMessage("Usage: /realtime set <auto|timezone> [world|all]")
    }

    private fun suggestRealtime(args: Array<out String>): Collection<String> {
        val options = when (args.size) {
            0, 1 -> listOf("reload", "set")
            2 -> if (args[0].equals("set", ignoreCase = true)) timezoneSuggestions else emptyList()
            3 -> if (args[0].equals("set", ignoreCase = true)) {
                listOf("all") + loadedWorldNames
            } else emptyList()
            else -> emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return options.asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun reply(sender: CommandSender, message: String) {
        if (sender is Player) {
            sender.scheduler.execute(this, { sender.sendMessage(message) }, null, 1L)
        } else {
            sender.sendMessage(message)
        }
    }

    private fun loadWorldSettings() {
        val section = config.getConfigurationSection("worlds")
        worldSettings = section?.getKeys(false)?.associateWith { name ->
            val raw = section.getString("$name.timezone", "UTC") ?: "UTC"
            val timezone = try {
                ZoneId.of(raw)
            } catch (_: Exception) {
                logger.warning("Invalid timezone '$raw' for world '$name'; using UTC.")
                ZoneId.of("UTC")
            }
            WorldSettings(timezone, section.getBoolean("$name.auto-timezone", false))
        } ?: emptyMap()
    }

    private fun updateWorldsAndPlayers() {
        val settings = worldSettings
        loadedWorldNames = Bukkit.getWorlds().map { it.name }
        for ((name, setting) in settings) {
            Bukkit.getWorld(name)?.time = minecraftTime(setting.timezone)
        }
        for (player in server.onlinePlayers) {
            player.scheduler.execute(this, { applyPlayerTime(player) }, null, 1L)
        }
    }

    private fun applyPlayerTime(player: Player) {
        val setting = worldSettings[player.world.name]
        if (setting == null || !setting.autoTimezone) {
            if (overriddenPlayers.remove(player.uniqueId)) player.resetPlayerTime()
            return
        }
        val zone = player.address?.address?.let { findTimezone(it) } ?: setting.timezone
        val offset = minecraftTime(zone) - player.world.time
        player.setPlayerTime(offset, true)
        overriddenPlayers.add(player.uniqueId)
    }

    private fun findTimezone(address: InetAddress): ZoneId? {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return null
        val ip = address.hostAddress
        val cached = zoneCache[ip]
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) return cached.timezone
        if (lookupsInProgress.add(ip)) {
            server.asyncScheduler.runNow(this) { _ ->
                try {
                    val zone = lookupTimezone(ip)
                    val ttl = if (zone == null) Duration.ofMinutes(5) else Duration.ofHours(24)
                    zoneCache[ip] = CachedZone(zone, Instant.now().plus(ttl))
                } finally {
                    lookupsInProgress.remove(ip)
                }
            }
        }
        return null
    }

    private fun lookupTimezone(ip: String): ZoneId? = try {
        val request = HttpRequest.newBuilder(URI("https", "ipwho.is", "/$ip", "fields=success,timezone.id", null))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() != 200) return null
            val bytes = body.readNBytes(16_385)
            if (bytes.size > 16_384) return null
            val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            if (json.get("success")?.asBoolean != true) return null
            val timezone = json.getAsJsonObject("timezone")?.get("id")?.asString ?: return null
            ZoneId.of(timezone)
        }
    } catch (_: Exception) {
        null
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = applyPlayerTime(event.player)

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) = applyPlayerTime(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        overriddenPlayers.remove(event.player.uniqueId)
    }

    private fun minecraftTime(zone: ZoneId): Long {
        val seconds = ZonedDateTime.now(zone).toLocalTime().toSecondOfDay()
        return Math.floorMod(seconds.toLong() * 24_000L / 86_400L - 6_000L, 24_000L)
    }
}
