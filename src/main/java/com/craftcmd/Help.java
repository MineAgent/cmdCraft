/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.craftcmd;

/**
 * The manual returned for {@code GET /op/}. Kept in one place so the endpoint, the README and the
 * {@code craftcmd} helper script stay in sync.
 */
public final class Help {
	private Help() {
	}

	public static String text() {
		return """
				Craft Command — Minecraft 客户端容器操作 (Fabric, Minecraft 26.2)
				============================================================
				挂载地址: http://127.0.0.1:3420/op
				HTTP 服务由 MGHttpdProvider 提供, 它把各模组挂在 3420 端口的不同前缀下;
				GET http://127.0.0.1:3420/ 列出当前所有可用的 endpoint。

				  GET  /op/    返回本使用说明
				  POST /op/    执行一条命令; 请求体是纯文本 (text/plain, UTF-8), 一条命令一行
				               命令跑在客户端主线程上, 请求会等到有结果才返回 (合成是异步的,
				               会一直等到合成结束/失败, 上限约 2 分钟)

				命令
				------------------------------------------------------------
				  craft <物品ID> [数量]
				      用背包里的材料合成物品, 走原版合成格 (数量 = 想要的成品个数,
				      按整次合成向上取整, 默认 1)。2×2 配方直接可用, 3×3 配方需要
				      先打开工作台界面, 否则返回 400 (craftcmd.error.needs_table)。

				  inventory <物品ID> [1-9]
				      把背包里的某种物品换到快捷栏第 N 格 (默认 1), 使用原版"数字键对调",
				      一次点击完成; 被换出的物品回到物品原来的格子。

				  furnace put <raw|fuel> <物品ID> [数量]
				  furnace get <raw|fuel|product> [数量]
				      在打开着的熔炉界面里放/取物品 (熔炉/高炉/烟熏炉共用)。没打开熔炉
				      界面时返回 400。产物格不能拆分, get product 少于整堆时会整堆取出。

				  chest put <物品ID> [数量]
				  chest get <物品ID> [数量]
				      在打开着的箱子界面里放/取物品 (箱子/陷阱箱/大箱子/木桶)。没打开箱子
				      界面时返回 400。只匹配"完全相同的堆"(物品 ID + 组件都相同)。

				  look <yaw|pitch> <角度>
				      转动视角, 角度支持原版 ~ 写法: 90 = 绝对角度, ~0.1 = 在当前角度上
				      加 0.1°, ~-20 = 减 20°, ~ = 保持不变。yaw 可超出 ±180, pitch 限制
				      在 -90..90。改的就是 AdvancedInfoFetcher 的 /aif/info 里读的
				      yaw / pitch 字段, 下一个 tick 由客户端同步给服务器。

				物品 ID
				  命名空间 ID, 例如 minecraft:crafting_table; 省略命名空间时默认 minecraft,
				  即 craft crafting_table 与 craft minecraft:crafting_table 等价。

				示例
				  curl -X POST --data-binary 'craft minecraft:crafting_table'   http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'craft stick 16'                   http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'inventory minecraft:torch 3'      http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'furnace put raw minecraft:raw_iron 8' http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'furnace get product'              http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'chest put minecraft:cobblestone 64' http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'chest get minecraft:iron_ingot 16' http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'look yaw 90'                      http://127.0.0.1:3420/op/
				  curl -X POST --data-binary 'look yaw ~-20'                    http://127.0.0.1:3420/op/
				  curl http://127.0.0.1:3420/op/                                # 本说明

				  命令行包装脚本: ./craftcmd craft stick 16    (等价于上面的 curl)

				返回
				  200  成功, 正文是执行结果 (纯文本, 一行一条), 例如 "已合成 木棍 ×16"
				  400  命令被拒绝: 语法错误 / 原料不足 / 没打开对应界面 / 合成任务忙 ...
				       正文是出错原因; 语法错误还附带出错位置和插入符 (^)
				  404  /op 下没有这个路径
				  405  方法不允许 (GET 看说明, POST 执行命令)
				  409  游戏客户端还没启动 / 还没进入世界
				  413  请求体过大 (>16KB)
				  504  等客户端超过约 2 分钟 (命令可能还在后台继续跑)

				注意事项
				  整套操作都走普通的数据包 (容器点击、移动包), 服务器始终是权威的, 不需要
				  服务端模组; 合成一次只点一下产物格, 不会比玩家手点更激进。
				  合成需要客户端配方书里"已解锁"的配方; 找不到配方时返回 400, 不会合成任何东西。
				  合成过程中背包必须能装下全部成品, 否则什么都不做 (craftcmd.error.no_space)。
				  打开界面时鼠标光标上不能拿着物品 (craftcmd.error.cursor)。
				  客户端只有打开容器界面时才知道容器内容, 所以熔炉/箱子命令必须先右键打开界面。
				  容器操作会检查结果, 任何一步失败都尽量回滚; 但界面中途被关掉时可能已经产生
				  部分点击 (craftcmd.error.menu_changed)。
				  同时只允许一个合成任务; 第二个 craft 请求会立刻返回 400 busy。
				  玩家信息 (坐标/背包/聊天/声音) 在 AdvancedInfoFetcher 下:
				  GET http://127.0.0.1:3420/aif/info
				  按键/鼠标/截图/聊天在 mcctl 下: GET http://127.0.0.1:3420/ctl/
				""";
	}
}
