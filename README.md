# BackToTheBase
A xinbot plugin to trigger ender pearls
## How to use
1. Install [MovementSync](https://github.com/huangdihd/movementsync)
2. Download the plugin from the releases page
3. Put the jar in your plugins folder
4. Place a button which can trigger the ender pearl at a place that the bot can trigger
5. Reload your xinbot instance
6. Modify `base_config.json` formated like the example:

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

This plugin is supporting muti-users. So each Name can be replaced with a user's name. The content of the json is the position of the button and the direction which the button is facing.
