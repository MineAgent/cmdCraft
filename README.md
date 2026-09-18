# Craft Command (`craftcmd`)

[![License: LGPL-2.1-only](https://img.shields.io/badge/license-LGPL--3.0--only-blue.svg)](LICENSE)

A **client side** Fabric mod for **Minecraft 26.2** that adds a `/craft` chat command. It crafts an
item from the materials you are already carrying, using the vanilla crafting grid — no crafting table
GUI juggling required.

```
/craft <item id> [amount]
```

Examples:

```
/craft minecraft:crafting_table          # consumes 4 planks, gives 1 crafting table
/craft minecraft:crafting_table 2        # needs 8 planks, otherwise it fails without crafting anything
/craft stick 16                          # 2 planks -> 4 sticks per craft, so 4 crafts
/craft oak_planks 8                      # 1 log -> 4 planks, so 2 crafts
```

`amount` is the number of result items you want. The mod rounds up to whole crafts (a recipe that
yields 4 items per craft is only executed once when you ask for 1–4 items) and reports the actual
result in chat, e.g. `已合成 橡木木板 ×8`.

## How it works

The mod is purely client side — it can be used on any vanilla/Fabric/Paper server without installing
anything on the server.

1. `/craft` resolves the argument as an **item id**, looks the item up in the client registry, and
   fails immediately if the id does not exist.
2. It searches the **client recipe book** for a crafting recipe (`ShapedCraftingRecipeDisplay` /
   `ShapelessCraftingRecipeDisplay`) whose result is that item.
3. It resolves the recipe layout (per–grid-slot candidate items) and simulates the whole operation
   against a copy of your inventory:
   * are there enough ingredients for every craft?
   * is there enough inventory space for all results?
   * does the recipe fit into the currently available grid (2×2 for the player inventory, 3×3 while a
     crafting table is open)?
   If any of these checks fails, the command reports a chat error and **nothing is crafted**.
4. Only then does it drive the real crafting grid through ordinary `ServerboundContainerClickPacket`s
   (`PICKUP` to move one stack of each ingredient into the grid, `QUICK_MOVE` on the output slot to
   craft and collect). The server validates every click, so the inventory can never be duplicated or
   desynchronised, and leftovers are moved back into the inventory when the job finishes.

The job runs in the background, one step per client tick, so a large `/craft` does not freeze the
game. Only one `/craft` job runs at a time.

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
# -> build/libs/craftcmd-1.0.0.jar
```

## Error messages

| Situation | Result |
|---|---|
| amount bigger than the materials allow (e.g. 6 planks + `/craft minecraft:crafting_table 2`) | error, no crafting at all |
| inventory cannot hold all results | error, no crafting at all |
| unknown item id | error |
| recipe needs a 3×3 grid while only the inventory is open | error (`请先打开工作台`) |
| crafting grid not empty / cursor holding an item | error |
| item id exists but no unlocked crafting recipe produces it | error |

## Limitations

* The recipe has to be **known to the client recipe book**. In vanilla this is exactly the case when
  you own the ingredients (picking ingredients up unlocks the recipe), but a server plugin that hands
  out items without triggering recipe unlocks can defeat this.
* 3×3 recipes need a crafting table GUI to be open, because a client cannot open one by itself.
* Recipes that are not placeable in a grid (special recipes such as fireworks, map cloning, …) are
  rejected with a clear error; they are never sent to the client recipe book anyway.
* The item id in the command is the id of the **result item**, not of the recipe file. Several
  recipes can produce the same item; the cheapest one that your materials support is chosen.

## License

This project is licensed under the **GNU Lesser General Public License v3.0 only**
(`LGPL-3.0-only`) — see [`LICENSE`](LICENSE) (LGPL-3.0) and [`COPYING`](COPYING) (GPL-3.0, which the
LGPL builds upon).
