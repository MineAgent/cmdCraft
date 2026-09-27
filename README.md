# Craft Command (`craftcmd`)

[![License: LGPL-3.0-only](https://img.shields.io/badge/license-LGPL--3.0--only-blue.svg)](LICENSE)

**Minecraft 26.2**（Fabric）客户端容器操作接口：把合成、快捷栏、熔炉、箱子、转向这五件事，
挂在 **MGHttpdProvider** 的共享 HTTP 服务（`127.0.0.1:3420`）的 `/op` 前缀下，用
**一条 POST 请求** 完成。

```
POST :3420/op/     请求体 = 一条命令
GET  :3420/op/     使用说明
```

这是从 1.3.1 的客户端指令 `/cmdop` 改过来的：命令语法、校验规则、报错文案全部保留，
只是入口从聊天栏换成了 HTTP。`GET http://127.0.0.1:3420/` 由 MGHttpdProvider 提供，
列出当前挂载的所有 endpoint（本模组是 `/op`）。

```bash
curl -X POST --data-binary 'look yaw 90'                      http://127.0.0.1:3420/op/
curl -X POST --data-binary 'craft minecraft:crafting_table'   http://127.0.0.1:3420/op/
curl -X POST --data-binary 'inventory minecraft:torch 3'      http://127.0.0.1:3420/op/
curl -X POST --data-binary 'furnace put raw minecraft:raw_iron 8' http://127.0.0.1:3420/op/
curl -X POST --data-binary 'chest get minecraft:iron_ingot 16' http://127.0.0.1:3420/op/
curl http://127.0.0.1:3420/op/                                # 使用说明
```

整套操作都走普通的数据包 —— 容器点击和移动包，和玩家手点格子、手转鼠标发的包完全一样 ——
所以服务器始终是权威的，**不需要任何服务端模组**，原版服务器也能用。

## 命令

请求体是纯文本（`text/plain; charset=utf-8`），内容是**一条**命令，第一段是子命令。
前后空白、开头的 `/`、以及旧的 `cmdop` 前缀都会被忽略，所以下面三种写法等价：

```
craft stick 16
/craft stick 16
/cmdop craft stick 16
```

| 命令 | 说明 |
|---|---|
| `craft <物品ID> [数量]` | 用背包里的材料合成，走原版合成格 |
| `inventory <物品ID> [1-9]` | 把背包里的某种物品换到快捷栏第 N 格 |
| `furnace put <raw\|fuel> <物品ID> [数量]` | 往打开着的熔炉里放 |
| `furnace get <raw\|fuel\|product> [数量]` | 从打开着的熔炉里取 |
| `chest put <物品ID> [数量]` | 往打开着的箱子里放 |
| `chest get <物品ID> [数量]` | 从打开着的箱子里取 |
| `look <yaw\|pitch> <角度>` | 转动视角 |

物品 ID 是命名空间 ID，省略命名空间时默认 `minecraft`：`craft crafting_table` 与
`craft minecraft:crafting_table` 等价。

### `craft <物品ID> [数量]`

```
craft minecraft:crafting_table          # 消耗 4 木板，得到 1 个工作台
craft minecraft:crafting_table 2        # 需要 8 木板，否则什么都不做
craft stick 16                          # 2 木板 -> 4 木棍，所以合 4 次
craft oak_planks 8                      # 1 原木 -> 4 木板，所以合 2 次
```

`数量` 是**想要的成品个数**。模组会向上取整到整次合成（一次产出 4 个的配方，要 1–4 个都只合
一次），实际结果在响应正文里报告。

工作方式：

1. 把参数当**物品 ID** 解析，在客户端注册表里查；查不到立刻失败（返回 400）。
2. 在**客户端配方书**里找产出该物品的合成配方（`RecipeDisplay`）。
3. 模拟整次操作：材料够不够每一次合成、产物放不放得下、配方放不放得进当前格子
   （背包里是 2×2，开着工作台是 3×3）。任何一项不满足都返回 400 并且**什么都不合**。
4. 全部通过后才真正驱动合成格：用 `PICKUP` 放好一次合成的材料，对产物格 `QUICK_MOVE`。
   产物格点一下会把这组材料能做的一次合成全部产出，所以格子里始终只放一次量的材料。

合成在客户端 tick 上一步一步做（一个 tick 一步）。**HTTP 请求会一直等到合成结束**，
再返回最终结果（`200 已合成 木棍 ×16` 或 `400 原料不足：…`），不会只返回"已开始"。
同一时间只允许一个合成任务，第二个请求立刻返回 `400 已有合成任务正在进行中。`。

### `inventory <物品ID> [1-9]`

```
inventory minecraft:torch        # 把火把换到快捷栏第 1 格
inventory minecraft:sword 3      # 把剑换到快捷栏第 3 格
```

用原版"数字键对调"完成（一个 `SWAP` 包），换出的物品回到物品原来所在的格子，和玩家按 1–9 一样。

选择规则：找**第一个匹配的物品堆**，先搜 27 个主背包格（左上到右下），再搜快捷栏其余格子，
跳过目标格本身。物品已经在目标格且别处没有时，直接报告成功（不发任何点击）。
鼠标光标上拿着物品时返回 400（`请先放下鼠标光标上持有的物品。`）。

### `furnace put|get ...`

打开熔炉（熔炉 / 高炉 / 烟熏炉 —— 共用同一个菜单）并保持界面开着：

```
furnace put raw minecraft:raw_iron 8    # 从背包放进原料格
furnace put fuel minecraft:coal 4       # 从背包放进燃料格
furnace get product 1                   # 从产物格取出
furnace get raw 2                        # 从原料格取回 2 个
```

`数量` 默认 `1`。`get raw` / `get fuel` 可以按任意数量拆分；要的比格子里多时整格取出并报告实际数量。
`get product` **不能拆分**：产物格不接受放回物品（原版行为），所以少于整堆时会整堆取出并说明
（`产物格不能拆分，已整堆取出 …`）。

熔炉方块实体**不会同步到客户端**，所以必须打开熔炉界面 —— 这也是服务器唯一接受格子点击的状态。
没打开熔炉界面时返回 400（`请先右键打开一个熔炉，并保持在熔炉界面里。`）。

### `chest put|get ...`

打开箱子（箱子 / 陷阱箱 / 大箱子 / 木桶 —— 都用 `ChestMenu`）并保持界面开着：

```
chest put minecraft:cobblestone 64
chest get minecraft:iron_ingot 16
```

`数量` 默认 `1`。`put` 先并入同名物品堆（槽位号小的优先），再用空格子；`get` 从槽位号小的开始取。

选择规则：只匹配**完全相同的堆**（物品 ID **和**组件都相同 —— 改过名、掉过耐久的堆不会和普通堆混）。
模板取第一个匹配的堆：`put` 取背包里的，`get` 取箱子里的。
没打开箱子界面时返回 400（`请先右键打开一个箱子（陷阱箱、木桶也行）。`）。

### `look <yaw|pitch> <角度>`

```
look yaw 90          # 把 yaw 设成 90°
look yaw ~0.1        # 在当前 yaw 上加 0.1°
look yaw ~-20        # 在当前 yaw 上减 20°
look pitch 30        # 低头 30°
look pitch ~         # 保持当前 pitch（偏移 0）
```

角度支持原版 `~` 写法：

| 写法 | 含义 |
|---|---|
| `90` | 设成 90° |
| `~0.1` | 在当前角度上加 0.1° |
| `~-20` | 在当前角度上减 20° |
| `~` | 保持当前角度 |

只写客户端玩家的旋转字段 —— 和鼠标转视角写的是同一批字段 —— 下一个 tick 客户端会用普通的移动包
同步给服务器，不需要任何服务端模组。AdvancedInfoFetcher 的 `GET :3420/aif/info` 报的
`yaw` / `pitch` 就是这里改的值，这让 `look` 成为 `mouse move` 的精确替代（需要准确朝向时用）。

yaw 原样保存（可以超过 ±180）；pitch 夹到原版的 `-90..90`。实际生效的值在响应里回报
（`已转向 yaw：90.0（原 0.0）`）。

## 返回码

请求体会执行在客户端主线程上；HTTP 工作线程等结果，所以响应一定描述**真正发生了什么**。

| 状态 | 含义 | 正文 |
|---|---|---|
| `200` | 成功 | 执行结果，纯文本，一行一条（如 `已合成 木棍 ×16`） |
| `400` | 命令被拒绝 | 语法错误 / 原料不足 / 没打开对应界面 / 合成任务忙 … 语法错误还带出错行和插入符 `^` |
| `404` | `/op` 下没有这个路径 | 提示 + 使用说明 |
| `405` | 方法不允许 | `GET` 看说明，`POST` 执行命令（`OPTIONS` 返回 `204`） |
| `409` | 游戏客户端还没启动 / 还没进入世界 | 说明原因 |
| `413` | 请求体过大（>16KB） | 说明上限 |
| `500` | 内部错误 | 异常摘要 |
| `504` | 等客户端超过约 2 分钟 | 命令可能还在后台继续跑 |

```bash
$ curl -i -X POST --data-binary 'craft stick 16' http://127.0.0.1:3420/op/
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8

已合成 木棍 ×16

$ curl -i -X POST --data-binary 'craft minecraft:crafting_table 2' http://127.0.0.1:3420/op/
HTTP/1.1 400 Bad Request

原料不足：橡木木板 需要 8 个，背包中只有 6 个。
```

## 错误信息

`craft` 与 `furnace`：

| 情况 | 结果 |
|---|---|
| 数量超过材料允许（如 6 木板 + `craft minecraft:crafting_table 2`） | 400，什么都不合 |
| 背包放不下全部产物 | 400，什么都不合 |
| 物品 ID 不存在 | 400 |
| 配方需要 3×3 而只开了背包 | 400（`该配方需要 3×3 的工作台，请先打开工作台。`） |
| 合成格不为空 / 光标上有物品 | 400 |
| 物品 ID 存在但配方书里没有能产出它的已解锁配方 | 400 |
| 没打开熔炉界面 | 400 |
| `put`：格子已有别的物品 / 物品放不进该格 / 超过堆放上限 / 背包里不够 | 400，什么都不动 |
| `get`：格子是空的 / 背包放不下 | 400，什么都不动 |

`inventory`：

| 情况 | 结果 |
|---|---|
| 物品 ID 不存在 | 400 |
| 主背包和快捷栏里都没有该物品 | 400（`主背包和快捷栏里都没有 …`） |
| 物品只在目标格 | 200，成功提示，不发点击 |
| 目标快捷栏格已有同种物品 | 仍然对调（请求什么做什么） |
| 槽位参数不在 1–9 | 400（语法错误） |
| 光标上有物品 | 400 |

`chest`：

| 情况 | 结果 |
|---|---|
| 没打开箱子界面（或打开的不是 `ChestMenu`，如潜影盒） | 400 |
| 物品 ID 不存在 | 400 |
| `put`：没带该物品 / 带得不够 | 400，什么都不动 |
| `put`：箱子放不下这么多（算上可合并的半堆和空格） | 400，什么都不动 |
| `put`：物品会占多个格子 | **允许**，会分配到可合并/空格子里 |
| `get`：箱子里没有该物品 | 400 |
| `get`：箱子里比要的少 | 400（严格，**不**取部分） |
| `get`：背包放不下 | 400，什么都不动 |
| 箱子里有同物品但组件不同（改名/掉耐久） | 忽略这些堆，绝不合并 |
| 光标上有物品 | 400 |
| 数量不在 1–6400 | 400（语法错误） |

`look`：

| 情况 | 结果 |
|---|---|
| 还没进入世界 | 409 |
| 角度不是数字（如 `~abc`） | 400（`无效的角度值`） |
| `pitch` 超出 -90..90 | 夹到 -90 / 90，并回报实际值 |
| `yaw` 超出 ±180 | 原样保存，不绕回 |

## 注意事项与局限

* 配方必须是**客户端配方书里已知**的。原版里捡到材料就会解锁配方；如果服务端插件发物品时没有触发
  配方解锁，这里就找不到配方。
* 3×3 配方需要工作台界面开着，因为客户端不能自己打开工作台。
* 放不进格子的特殊配方（烟花、地图复制等）会被明确拒绝；它们本来也不会进客户端配方书。
* `craft` 的物品 ID 是**产物**的 ID，不是配方文件名。同一个物品可能有多条配方，模组会选材料够用的
  里最省的那条。
* `furnace` / `chest` 需要对应界面开着：客户端不知道关闭的方块实体的内容，服务器也只接受当前打开容器
  的格子点击。
* 严格的服务器反作弊可能不喜欢大数量命令产生的连续点击包（原版本身没有点击频率限制）。
* 一次 HTTP 请求只跑一条命令；想要脚本化就连发几个请求（顺序由调用方保证）。

## 实现方式

* 入口 `CraftCmdMod` 在客户端加载时把 `/op` 注册到 MGHttpdProvider 的共享服务上，
  自己不监听端口、不管退出（服务与退出处理都由 provider 负责）。
* `OpEndpoint` 是 `/op` 的 `PathHandler`：`GET /op/` 返回说明，`POST /op/` 读请求体，
  用 `Minecraft.execute` 把命令丢到客户端（渲染）线程执行，然后等工作线程拿到结果再返回。
* `OpCommands` 是一棵 Brigadier 命令树（`craft`/`inventory`/`furnace`/`chest`/`look`），
  数据源是 `OpCommandSource` 而不是聊天命令源：命令只往里面写文本，HTTP 线程读它拼响应。
  解析规则和老的 `/cmdop` 完全一致（物品 ID、`~` 角度、熔炉槽位、数量范围）。
* `CraftJob` 仍然一个 tick 一步地点击合成格；结束时调用 `OpCommandSource#complete()` 唤醒
  HTTP 线程。每 tick 的驱动点换成了对 `Minecraft#tick` 的 Mixin（`MinecraftMixin`），
  所以本模组**不需要 Fabric API**。
* 所有 `Minecraft` 状态只在客户端线程上读写；HTTP 线程只碰线程安全的 `OpCommandSource`。

## 依赖与要求

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.5 |
| Java | ≥ 25 |
| Fabric API | **不需要** |
| MGHttpdProvider | ≥ 1.0（**必需**，`/op` 挂在它的 3420 服务上） |

Minecraft 26.2 已不再混淆且 Fabric 使用 Mojang 官方名，所以项目不配 mappings，直接编译
`com.mojang:minecraft:26.2`（Loom `1.17-SNAPSHOT`，见 `gradle.properties`）。

## 构建 / 安装

编译依赖 MGHttpdProvider 的 API jar（`libs/httpdprovider-1.0.jar`，已在仓库里）。重新生成它：
在 [HttpdProvider 仓库](https://github.com/MineAgent/HttpdProvider) 跑 `./gradlew build`，把
`build/libs/httpdprovider-1.0.jar` 复制到本仓库的 `libs/`。它是 `compileOnly`，不会被打进本模组的 jar。

```bash
./gradlew build
# -> build/libs/craftcmd-1.3.2.jar
```

把本模组的 jar **和 MGHttpdProvider 的 jar（`httpdprovider-1.0.jar`，必需）** 一起放进
`.minecraft/mods/`。启动后日志里会有：

```
MGHttpdProvider listening on http://127.0.0.1:3420
registered /op (Craft Command — 客户端容器操作 (合成/背包/熔炉/箱子/转向))
```

## 目录

```
src/main/java/com/craftcmd/
  CraftCmdMod.java          Fabric 客户端入口, 把 /op 注册到 MGHttpdProvider
  OpEndpoint.java           /op 前缀下的 endpoint (GET 说明 / POST 执行)
  OpCommands.java           Brigadier 命令树 + 派发到客户端线程
  OpCommandSource.java      单次请求的反馈收集与完成信号 (线程安全)
  Help.java                 GET /op/ 返回的使用说明
  CraftCommand.java         craft  <物品ID> [数量]
  InventoryCommand.java     inventory <物品ID> [1-9]
  FurnaceCommand.java       furnace put|get ...
  ChestCommand.java         chest put|get ...
  LookCommand.java          look <yaw|pitch> <角度>
  CraftPlan.java            已校验的合成计划 (配方查找/材料模拟)
  CraftJob.java             每 tick 一步驱动合成
  MenuSlots.java            容器槽位读写小工具
  FurnaceSlot.java          熔炉槽位 (raw/fuel/product)
  Angle.java                一个旋转指令 (绝对或相对)
  ItemIdArgumentType.java   物品 ID 的 Brigadier 参数
  AngleArgumentType.java    角度的 Brigadier 参数
  FurnaceSlotArgumentType.java  熔炉槽位的 Brigadier 参数
src/main/java/com/craftcmd/mixin/
  MinecraftMixin.java       注入 Minecraft#tick, 驱动 CraftJob
src/main/resources/
  craftcmd.mixins.json      Mixin 配置
  assets/craftcmd/lang/     中英文文案 (响应正文按客户端语言本地化)
libs/httpdprovider-1.0.jar  MGHttpdProvider 的 API (编译用, compileOnly)
craftcmd                    命令行包装脚本 (curl 的语法糖)
```

## 调试

跑单人测试世界（不需要服务端）：参见 `/home/DSH/Documents/spMC/TestSave.sh`。
客户端进入世界后，直接 curl 即可：

```bash
curl http://127.0.0.1:3420/                       # 看 /op 有没有挂上
curl http://127.0.0.1:3420/op/                    # 使用说明
curl -X POST --data-binary 'look yaw 90' http://127.0.0.1:3420/op/
curl http://127.0.0.1:3420/aif/info | grep yaw    # 确认转向生效 (AdvancedInfoFetcher)
curl -X POST --data-binary 'craft stick 4' http://127.0.0.1:3420/op/
```

容器类命令需要先打开对应界面，可以用 mcctl（`/ctl`）代劳：给物品、放置方块、右键打开，例如

```bash
curl -X POST --data-binary 'chat /give @s minecraft:oak_log 8' http://127.0.0.1:3420/ctl/
curl -X POST --data-binary 'mouse right' http://127.0.0.1:3420/ctl/      # 对着箱子/熔炉右键
curl -X POST --data-binary 'chest put minecraft:oak_log 8' http://127.0.0.1:3420/op/
```

关掉游戏时不会多出 `crash-reports/` 文件：共享服务的退出处理在 MGHttpdProvider 里
（它守候渲染线程，客户端退出后停服务并结束 JVM，避免 post-main 看门狗写崩溃报告）。

## 许可证

LGPL-3.0-only：完整文本见 [`LICENSE`](LICENSE)，其中引用的 GPL-3.0 见 [`COPYING`](COPYING)。
源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
