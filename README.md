# RealTime
Synchronizes Minecraft time with real time in each configured world. Optionally, each player can see the time of day for the timezone detected from their IP address.

Requires Java 21. See [here](https://docs.papermc.io/misc/java-install) how to update your installed Java version.

## Features
- Supports Paper/Folia 1.21.x
- Loads as a Paper plugin using `paper-plugin.yml`
- Uses Java timezones, including daylight saving time
- Optional per-player visual time based on IP geolocation

## Default config.yml
```
worlds:
  exampleworld1:
    timezone: Europe/Kyiv
    auto-timezone: false
  exampleworld2:
    timezone: UTC
    auto-timezone: false
  exampleworld3:
    timezone: GMT-5
    auto-timezone: false
```

Set `auto-timezone: true` for a world to show each player their local time of day. `timezone` remains the world's time and is used for a player if their IP timezone cannot be determined. Existing configs without `auto-timezone` behave as `false`. The personal time changes the sky only; gameplay time remains shared by the world.

Automatic detection sends the player's public IP to the [ipwho.is](https://ipwhois.io/documentation) HTTPS API. Results are cached in memory. VPNs and proxies can make the detected timezone inaccurate.

## Commands

- `/realtime reload` reloads the config.
- `/realtime set auto [world|all]` enables automatic time for a world.
- `/realtime set Europe/Kyiv [world|all]` sets a fixed timezone and disables automatic time.

Without a world argument, `set` changes the player's current world. The console must specify a world or `all`. `all` changes every loaded world and saves their settings to the config. These commands require the `realtime.admin` permission (operators by default).

Tab completion suggests subcommands, all available Java timezone IDs, and loaded worlds as you type `/realtime`. Minecraft sorts the displayed suggestions alphabetically, even though the plugin sends `auto` first.

## Downloads
- [Releases](https://github.com/amapekibert/RealTime/releases)
