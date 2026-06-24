# Alch Assistant

A RuneLite plugin that tracks the profit of items you buy on the Grand Exchange
and then high alch — including the cost of nature runes.

## Features

- **Auto-tracks GE buys.** When a buy offer fills, the plugin records the
  quantity and gp spent and keeps a running average of what you paid per item.
- **Counts high alch casts accurately.** Each cast is counted from the Magic XP
  it grants, so fast back-to-back alching is captured fully (and misclicks that
  don't actually cast are ignored).
- **Subtracts nature rune cost.** Each cast subtracts the price of one nature
  rune. By default it uses the live GE price; you can switch to a fixed value.
- **Alch only mode.** A toggle (top of the panel, also in config) that stops GE
  buy tracking and hides buy-only items — so your flips don't clutter the panel
  while you alch.
- **Side panel.** A "Summary" box (nature cost, total casts, total profit) and a
  "Magic XP" box with total XP gained, XP to the next level, and a level
  progress bar. Below that, a card per tracked item showing buy price, alch
  value, nature cost, profit per cast, casts, and total profit. Profit is shown
  green, loss red.

Profit is always recomputed from current prices, so setting or correcting a buy
price later updates the totals to match.

## Setting a buy price manually

Items you bought before installing the plugin (or while in alch-only mode) have
no recorded buy price. Open the panel, find the item, type what you paid per
item into the buy field, and click **Set buy**. From then on the profit per alch
and total are accurate. A manual price always overrides the tracked GE average.

## Profit formula

```
profit per alch = high alch value − buy price per item − nature rune cost
total           = profit per alch × number of casts
```

Nature rune cost is omitted if you turn off **Subtract nature rune cost** in the
config.

## Getting started

Install **Alch Assistant** from the RuneLite Plugin Hub, then open its side panel
(the gold "A" icon). Buy something on the Grand Exchange and high alch it to start
tracking. For items you already own, set the buy price manually as described above.

## Notes

- Cast counting uses the Magic XP gained per cast (65 XP), which fires once per
  completed cast regardless of how fast you click.
- The GE buy price is a weighted average across all buys of that item since
  tracking started; a manual buy price always overrides it.
- All data is stored in your RuneLite config and persists across sessions. Use
  **Reset all** in the panel to clear it, or the **x** on a card to drop one item.
