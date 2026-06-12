# BackToTheBase

[English](README.md) | [简体中文](README.zh-CN.md)

一个 Xinbot 插件，可以根据玩家配置按下指定的珍珠超传按钮，并可选择让机器人返回基地坐标。

## 功能

- 支持每个玩家配置多个珍珠按钮坐标
- `back` 默认使用 `1` 号坐标，`back <number>` 使用指定编号
- 自动检测按钮朝向并自动寻找可到达的安全站位
- 支持全局返回坐标
- 支持控制台管理命令和 Tab 补全

## 安装

1. 安装前置插件 [MovementSync](https://github.com/huangdihd/MovementSync)。
2. 将 BackToTheBase JAR 放入 Xinbot 的 `plugins` 文件夹。
3. 启动一次 Xinbot，生成 `base_config.json`。
4. 编辑 `base_config.json`。
5. 重载或重启 Xinbot。

## 玩家命令

玩家向机器人发送私聊消息(/msg 珍珠号)：

| 命令 | 说明 |
| --- | --- |
| `back` | 使用 `1` 号珍珠坐标。 |
| `back <number>` | 使用对应编号的珍珠坐标。 |

示例：

```text
/msg 珍珠号 back
/msg 珍珠号 back 2
```

## 控制台命令

命令名为 `backtothebase`，也可以使用带前缀的 `BackToTheBase:backtothebase`。

| 所有命令 | 说明 |
| --- | --- |
| `backtothebase stat` | 查看插件状态。 |
| `backtothebase player list` | 查看玩家列表。 |
| `backtothebase player add <player>` | 添加玩家。 |
| `backtothebase player remove <player>` | 请求删除玩家，需要 `confirm` 确认。 |
| `backtothebase loc list <player>` | 查看玩家的珍珠坐标。 |
| `backtothebase loc add <player> <number> <x> <y> <z>` | 添加珍珠坐标。 |
| `backtothebase loc set <player> <number> <x> <y> <z>` | 设置或覆盖珍珠坐标。 |
| `backtothebase loc remove <player> <number>` | 请求删除珍珠坐标，需要 `confirm` 确认。 |
| `backtothebase returnenable true\|false` | 开启或关闭点击后的返回功能。 |
| `backtothebase returnpoint <x> <y> <z>` | 设置返回坐标。 |
| `backtothebase admin add\|remove <player>` | 管理游戏内管理员。 |
| `backtothebase adminenable true\|false` | 开启或关闭游戏内管理命令。 |
| `backtothebase confirm` | 确认待执行的删除操作。 |

## 游戏内管理命令

开启后，管理员可以向机器人发送私聊消息：

```text
/msg 珍珠号 @backtothebase <command>
```

**大部分控制台命令都支持，但不支持 `admin` 和 `adminenable` 等管理员设置命令。**

## 配置文件

`base_config.json` 示例：

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

说明：

- `players` 是玩家名到珍珠按钮坐标的映射。
- `locations[].number` 必须是正整数形式的字符串。
- `x`、`y`、`z` 是按钮方块本身的精确坐标。
- `return.enabled` 控制点击后是否返回。
- `admin.enabled` 控制游戏内 `@backtothebase` 管理命令是否启用。

## 旧版配置兼容

旧版简单配置仍然支持：

```json
{
  "Steve": {
    "x": 100,
    "y": 64,
    "z": 200
  }
}
```

插件会自动将其迁移到新的 `players.locations` 格式。

## 构建

```bash
mvn clean package
```

## 更多说明

- 当 BackToTheBase 任务超时时，插件会调用 MovementSync 的 `cancelAll()` 来清理过期移动状态。由于 MovementSync 目前没有按插件范围取消移动的 API，这可能会取消其他排队中的 MovementSync 移动。
- UseItemOnMovement 会先发送旋转包，再发送 UseItemOnPacket，这样服务端交互会瞄准按钮点击位置。点击会延迟到后续 movement tick 执行，避免旋转和交互在同一 tick 内发送。