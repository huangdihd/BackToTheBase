# BackToTheBase

[English](README.md) | [简体中文](README.zh-CN.md)

A Xinbot plugin that triggers ender pearls to bring a bot back to base.

## Features
- Trigger a configured button to launch an ender pearl
- Per-user button location and facing direction
- Simple JSON configuration

## How to use
1. Install [MovementSync](https://github.com/huangdihd/movementsync).
2. Download the plugin JAR from the Releases page.
3. Place the JAR in your Xinbot plugins folder.
4. Place a button that can trigger the ender pearl where the bot can reach it.
5. Reload your Xinbot instance.
6. Edit `base_config.json` with your button coordinates and facing direction:

   ```json
   {
     "Name1": {
       "x": 0,
       "y": 0,
       "z": 0,
       "direction": "UP"
     },
     "Name2": {
       "x": 1,
       "y": 1,
       "z": 1,
       "direction": "DOWN"
     }
   }
   ```

   This plugin supports multiple users. Each top-level key (for example, `Name1`) should be replaced with the user's name. Each entry defines the button position and the direction the button faces.
7. Reload your Xinbot instance again.
8. Send the bot a private message with the content `back`.
