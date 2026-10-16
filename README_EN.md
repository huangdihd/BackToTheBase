# BackToTheBase

[简体中文](README.md) | [English](README_EN.md)

A Xinbot plugin that presses a configured ender pearl button for a player and optionally returns the bot to a base location.

## Features

- Supports multiple button locations per player
- `back` uses location `1`; `back <number>` uses a selected location
- Automatically detects button facing direction and finds a reachable standing position.
- Optional global return location
- Console management commands with tab completion

## Installation

1. Install [MovementSync 1.11.0-RELEASE](https://github.com/huangdihd/MovementSync/releases/tag/1.11.0-RELEASE). This SNAPSHOT targets that version and does not support older MovementSync releases.
2. Put the BackToTheBase JAR in the Xinbot `plugins` folder.
3. Start Xinbot once to generate `base_config.json`.
4. Edit `base_config.json`.
5. Reload or restart Xinbot.

## Player Commands

Send these as private messages (/msg account_name) to the bot:

| Command | Description |
| --- | --- |
| `back` | Uses location `1`. |
| `back <number>` | Uses the matching configured location number. |

Example:

```text
/msg account_name back
/msg account_name back 2
```

## Console Commands

Use the command name `backtothebase` or the short alias `bttb`. The prefixed form `BackToTheBase:backtothebase` and `BackToTheBase:bttb` also works.

| Command | Description |
| --- | --- |
| `backtothebase stat` | Show plugin status. |
| `backtothebase player list` | List players. |
| `backtothebase player add <player>` | Add a player. |
| `backtothebase player remove <player>` | Ask to remove a player. Requires `confirm`. |
| `backtothebase loc list <player>` | List a player's locations. |
| `backtothebase loc add <player> <number> <x> <y> <z>` | Add a button location. |
| `backtothebase loc set <player> <number> <x> <y> <z>` | Set or replace a button location. |
| `backtothebase loc remove <player> <number>` | Ask to remove a location. Requires `confirm`. |
| `backtothebase returnenable true\|false` | Enable or disable returning after clicking. |
| `backtothebase returnpoint <x> <y> <z>` | Set the return location. |
| `backtothebase admin add\|remove <player>` | Manage in-game admins. |
| `backtothebase adminenable true\|false` | Enable or disable in-game admin commands. |
| `backtothebase confirm` | Confirm a pending remove action. |

## In-game Admin Commands

When enabled, admins can private message the bot with:

```text
/msg account_name @backtothebase <command>
/msg account_name @bttb <command>
```

**Most console commands are supported, except admin management commands such as `admin` and `adminenable`.**

## Configuration

Example `base_config.json`:

```json
{
  "players": {
    "Steve": {
      "locations": [
        {
          "number": "1",
          "x": 100,
          "y": 64,
          "z": 200
        },
        {
          "number": "2",
          "x": 120,
          "y": 64,
          "z": 210
        }
      ]
    }
  },
  "return": {
    "enabled": false,
    "location": {
      "x": 0,
      "y": 60,
      "z": 0
    }
  },
  "admin": {
    "enabled": false,
    "players": []
  }
}
```

Notes:

- Command output is rendered through Xinbot `LangManager`. The language follows the host system locale (a Chinese locale loads `lang/zh_cn.lang`; everything else falls back to `lang/en_us.lang`).
- `players` maps player names to their button locations.
- `locations[].number` must be a positive integer string.
- `x`, `y`, and `z` are the exact block coordinates of the button.
- `return.enabled` controls whether the bot walks back after clicking.
- `admin.enabled` controls in-game `@backtothebase` / `@bttb` management commands.

## Legacy Config

Old simple configs are still supported:

```json
{
  "Steve": {
    "x": 100,
    "y": 64,
    "z": 200
  }
}
```

They will automatically migrate to the new `players.locations` format.

## Build

```bash
mvn clean package
```

## Implementation Notes

- If MovementSync is moving, following, or paused, `back` reports that it is busy. Retry once it is idle.
- Each `back` tracks its own movements. If a later command finds that the previous action is over 30 seconds old, it invalidates that action while preserving other queued movements. Disabling or unloading the plugin also invalidates its unfinished actions.
- UseItemOnMovement sends a rotation packet before UseItemOnPacket so the server-side interaction is aimed at the button hit position. The click is delayed to a later movement tick to avoid sending rotation and interaction in the same tick.
