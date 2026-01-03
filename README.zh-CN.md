# BackToTheBase

[English](README.md) | [简体中文](README.zh-CN.md)

一个用于触发末影珍珠、让机器人返回基地的 Xinbot 插件。

## 功能
- 触发配置的按钮来发射末影珍珠
- 支持按用户配置按钮位置与朝向
- JSON 配置简单易用

## 使用方法
1. 安装 [MovementSync](https://github.com/huangdihd/movementsync)。
2. 从 Releases 页面下载插件 JAR。
3. 将 JAR 放入 Xinbot 的 plugins 目录。
4. 放置一个可以触发末影珍珠的按钮，并确保机器人能到达。
5. 重载 Xinbot 实例。
6. 编辑 `base_config.json`，填写按钮坐标和朝向：

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

   本插件支持多用户。每个顶级键（例如 `Name1`）替换为用户名。每个条目描述按钮位置与按钮朝向。
7. 再次重载 Xinbot 实例。
8. 发送私聊消息 `back` 给机器人。
