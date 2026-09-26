# Craft Command (`craftcmd`)

[![License: LGPL-3.0-only](https://img.shields.io/badge/license-LGPL--3.0--only-blue.svg)](LICENSE)

A **client side** Fabric mod for **Minecraft 26.2** that adds five commands:

| Command | What it does |
|---|---|
| `/craft <item id> [amount]` | crafts from the materials you carry, using the vanilla crafting grid |
| `/inventory <item id> [1-9]` | swaps a stack from the inventory with a hotbar slot |
| `/furnace put\|get <raw\|fuel\|product> <item id> [amount]` | moves items in the furnace screen you have open |
| `/chest put\|get <item id> [amount]` | moves items in the chest screen you have open |
| `/rot <yaw\|pitch> <angle>` | turns the player's view (this is what AdvancedInfoFetcher's `/info` reports) |

Everything is done by sending ordinary `ServerboundContainerClickPacket`s — exactly what a player clicking
the slots would send — so the server stays fully authoritative and **no server side mod is needed**.

## `/craft`

```
/craft <item id> [amount]
```

```
/craft minecraft:crafting_table          # consumes 4 planks, gives 1 crafting table
/craft minecraft:crafting_table 2        # needs 8 planks, otherwise it fails without crafting anything
/craft stick 16                          # 2 planks -> 4 sticks per craft, so 4 crafts
/craft oak_planks 8                      # 1 log -> 4 planks, so 2 crafts
```

`amount` is the number of result items you want. The mod rounds up to whole crafts (a recipe that yields
4 items per craft only runs once when you ask for 1–4 items) and reports the actual result in chat.

How it works:

1. `/craft` resolves the argument as an **item id**, looks the item up in the client registry and fails
   immediately if the id does not exist.
2. It searches the **client recipe book** for a crafting recipe whose result is that item.
3. It resolves the recipe layout and simulates the whole operation against a copy of your inventory: are
   there enough ingredients for every craft, is there room for all results, does the recipe fit into the
   available grid (2×2 in the inventory, 3×3 while a crafting table is open)? If any check fails the
   command reports an error and **nothing is crafted**.
4. Only then does it drive the real grid (`PICKUP` to stock one craft worth of ingredients, `QUICK_MOVE`
   on the output slot). One click on the output crafts as often as the grid allows, so the grid is always
   stocked with exactly one craft worth of items and the count stays predictable.

The job runs in the background, one step per client tick; only one `/craft` runs at a time.

## `/inventory`

```
/inventory <item id> [1-9]
```

```
/inventory minecraft:torch        # bring torches to hotbar slot 1
/inventory minecraft:sword 3      # bring a sword to hotbar slot 3
```

Swaps the chosen item with the hotbar slot (default 1), using the vanilla "number key" swap — one packet,
and whatever was in the hotbar slot goes back to the slot the item came from.

Selection rule: the **first matching stack** is used, searching the 27 main inventory slots first (top left
to bottom right) and then the rest of the hotbar, skipping the target slot itself. If the item is already
in the target slot and nowhere else, the command says so and sends no click at all.

## `/furnace`

Open a furnace (or blast furnace / smoker — they share the same menu) and keep the screen open:

```
/furnace put <raw|fuel> <item id> [amount]   # move items from your inventory into the furnace
/furnace get <raw|fuel|product> [amount]     # move items from the furnace into your inventory
```

```
/furnace put raw minecraft:raw_iron 8
/furnace put fuel minecraft:coal 4
/furnace get product 1
```

`amount` defaults to `1`. `get raw` / `get fuel` can split stacks to any amount; asking for more than the
slot holds takes the whole slot and reports the real amount.

`get product` **cannot** split a stack: a result slot never accepts items back (vanilla behaviour), so
asking for fewer items than the output holds takes the whole stack and says so
(`产物格不能拆分，已整堆取出 …`).

A furnace block entity is **not** synchronised to the client, so the commands need the furnace screen to
be open — that is also the only way the server accepts slot clicks.

## `/chest`

Open a chest (chest, trapped chest, double chest or barrel — anything using `ChestMenu`) and keep the
screen open:

```
/chest put <item id> [amount]    # move items from your inventory into the chest
/chest get <item id> [amount]    # move items from the chest into your inventory
```

```
/chest put minecraft:cobblestone 64
/chest get minecraft:iron_ingot 16
```

`amount` defaults to `1`. `put` merges into matching stacks first (lowest slot first) and then uses empty
slots; `get` takes from the lowest matching slot first.

Selection rule: items are matched as **identical stacks** (same item id *and* same components — a renamed
or damaged stack is never mixed with a plain one). The template is the first matching stack: for `put` the
first one in your inventory, for `get` the first one in the chest.

## `/rot`

```
/rot <yaw|pitch> <angle>
```

```
/rot yaw 90          # set the yaw to exactly 90°
/rot yaw ~0.1        # turn 0.1° from the current yaw
/rot yaw ~-20        # turn 20° back from the current yaw
/rot pitch 30        # look 30° down
/rot pitch ~         # keep the current pitch (offset 0)
```

`angle` uses vanilla's `~` notation:

| Form | Meaning |
|---|---|
| `90` | set the angle to 90° |
| `~0.1` | add 0.1° to the current angle |
| `~-20` | subtract 20° from the current angle |
| `~` | keep the current angle |

The command writes only the client player's rotation fields — the same ones the mouse look writes — so the
client's ordinary movement packets sync it to the server on the next tick and no server side mod is needed.
It is exactly what AdvancedInfoFetcher's `GET :3421/info` reports as `yaw` / `pitch`, which makes `/rot` the
precise replacement for `mouse move` when an exact heading is needed (playbook §13).

Yaw is stored as given (it may exceed ±180); pitch is clamped to vanilla's `-90..90`. The applied value is
echoed in chat (`已转向 yaw：90.0（原 0.0）`).

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | ≥ 0.160.0+26.2 |
| Java | ≥ 25 |

Minecraft 26.2 is unobfuscated and Fabric now uses Mojang's names, so the project is built against
`com.mojang:minecraft:26.2` with Loom `1.17-SNAPSHOT` (see `gradle.properties`).

## Building

```bash
./gradlew build
# -> build/libs/craftcmd-1.3.0.jar
```

## Error messages

`/craft` and `/furnace`:

| Situation | Result |
|---|---|
| amount bigger than the materials allow (e.g. 6 planks + `/craft minecraft:crafting_table 2`) | error, no crafting at all |
| inventory cannot hold all results | error, no crafting at all |
| unknown item id | error |
| recipe needs a 3×3 grid while only the inventory is open | error (`请先打开工作台`) |
| crafting grid not empty / cursor holding an item | error |
| item id exists but no unlocked crafting recipe produces it | error |
| no furnace screen open | error |
| `put`: slot already holds another item / item cannot go into that slot / stack limit reached / not enough carried | error, nothing moves |
| `get`: slot empty / inventory cannot hold the items | error, nothing moves |

`/inventory`:

| Situation | Result |
|---|---|
| unknown item id | error |
| item neither in the main inventory nor in another hotbar slot | error (`主背包和快捷栏里都没有 …`) |
| item only in the target slot | success message, no click sent |
| target hotbar slot already holds the same item | the two stacks are still swapped (as requested) |
| slot argument outside 1–9 | brigadier error |
| cursor holding an item | error |

`/chest`:

| Situation | Result |
|---|---|
| no chest screen open (or the open screen is not a `ChestMenu`, e.g. a shulker box) | error |
| unknown item id | error |
| `put`: not carrying the item / not carrying enough | error, nothing moves |
| `put`: the chest cannot hold that many (counting matching partial stacks and empty slots) | error, nothing moves |
| `put`: the item would fill more than one slot | **allowed**, it is distributed over matching/empty slots |
| `get`: the chest has none of that item | error |
| `get`: the chest has fewer than requested | error (strict — it does **not** take a partial amount) |
| `get`: the inventory cannot hold the items | error, nothing moves |
| the chest holds the same item with different components (renamed/damaged) | those stacks are ignored, they are never merged |
| cursor holding an item | error |
| amount outside 1–6400 | brigadier error |

`/rot`:

| Situation | Result |
|---|---|
| not in a world (still on a menu) | error (`尚未连接到世界`) |
| angle is not a number (e.g. `~abc`) | brigadier error (`无效的角度值`) |
| `pitch` outside -90..90 | clamped to -90 / 90, the applied value is reported |
| `yaw` outside ±180 | stored as given, no wrapping |

## Limitations

* The recipe has to be **known to the client recipe book**. In vanilla this is exactly the case when you
  own the ingredients (picking ingredients up unlocks the recipe), but a server plugin that hands out items
  without triggering recipe unlocks can defeat this.
* 3×3 recipes need a crafting table GUI to be open, because a client cannot open one by itself.
* Recipes that are not placeable in a grid (special recipes such as fireworks, map cloning, …) are rejected
  with a clear error; they are never sent to the client recipe book anyway.
* The item id in `/craft` is the id of the **result item**, not of the recipe file. Several recipes can
  produce the same item; the cheapest one that your materials support is chosen.
* `/furnace` and `/chest` need their screen to be open, because the client does not know the contents of a
  closed block entity and the server only accepts slot clicks for the container the player has open.
* A strict server side anti-cheat could dislike the burst of click packets a large command produces
  (vanilla itself has no click rate limit).

## License

This project is licensed under the **GNU Lesser General Public License v3.0 only**
(`LGPL-3.0-only`) — see [`LICENSE`](LICENSE) (LGPL-3.0) and [`COPYING`](COPYING) (GPL-3.0, which the
LGPL builds upon).
