# BackToTheBase
A xinbot plugin to trigger ender pearls
## How to use
1. Download the plugin from the releases page
2. Put the jar in your plugins folder
3. Place a button which can trigger the ender pearl at a place that the bot can trigger
4. Reload your xinbot instance
5. Modify `base_config.json` formated like the example:

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
