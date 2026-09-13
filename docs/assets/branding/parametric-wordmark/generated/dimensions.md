# 尺寸比例表

由 parameters.json、真实字体度量和几何公式生成。所有 u 均属于同一坐标体系。

| 尺寸 | 数值 | 单位 | 定义 |
| --- | ---: | --- | --- |
| B width | 14 | u | s+r+d |
| B height | 21 | u | 2t+2h+g |
| Stem width | 4 | u | s |
| Fold depth run | 4 | u | d |
| Diagonal angle | 26.56505118 | degrees | atan(rise/run) |
| Counter run | 6 | u | r |
| Counter height | 6 | u | h=2mr |
| Counter pitch | 7 | u | p=h+g |
| Counter gap | 1 | u | g |
| Band vertical intercept | 4 | u | t=2md; not normal thickness |
| Type cap height | 15 | u | measured font cap height times uniform scale |
| Type/B height ratio | 0.71428571 | ratio | cap height / B height |
| Type baseline | 19 | u | (B height+cap height)/2 + optical shift |
| Optical baseline shift | 1 | u | relative to centered type ink box |
| Cap line | 4 | u | baseline - cap height |
| X-height line | 8.28571429 | u | baseline - 10 font units times scale |
| Visible letter gap | 3.5 | u | next ink left - previous ink right |
| B-to-type gap | 3.5 | u | first glyph ink left - B right bound |
| Extra tracking per gap | 0.28571429 | u | 2/7 |
| Font scale | 1.07142857 | u/font unit | 15/14 |
| Font pixel step | 2.14285714 | u | 2 font units times scale |
| Text ink width | 81 | u | font outlines plus six visible gaps |
| Lockup ink width | 98.5 | u | B width + emblem gap + text ink width |
| Canvas width | 109.5 | u | lockup width + 2*horizontal padding |
| Canvas height | 36.5 | u | canvas width / 3 |
| Origin x | 5.5 | u | horizontal padding + offset x |
| Origin y | 7.75 | u | (canvas height-B height)/2 + offset y |
| Export unit | 28 | px/u | uniform scale |

## B 顶点

| 顶点 | x / u | y / u |
| --- | ---: | ---: |
| A | 4 | 0 |
| B | 0 | 2 |
| C | 0 | 19 |
| D | 4 | 21 |
| E | 14 | 16 |
| F | 14 | 12 |
| G | 11 | 10.5 |
| H | 14 | 9 |
| I | 14 | 5 |
| J | 4 | 4 |
| K | 4 | 10 |
| L | 10 | 7 |
| M | 7 | 8.5 |
| N | 4 | 11 |
| O | 4 | 17 |
| P | 10 | 14 |
| Q | 10 | 18 |

## 字形位置

相对 B 局部原点；所有字形共用同一基线。左右边界按实际墨迹测量。

| 字符 | Unicode | Glyph ID | 原生 advance / fu | 左边界 / u | 右边界 / u | 前置附加字距 / u |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| l | U+006C | 77 | 7 | 17.5 | 21.78571429 | 0 |
| e | U+0065 | 70 | 13 | 25.28571429 | 36 | 2/7 |
| n | U+006E | 79 | 13 | 39.5 | 50.21428571 | 2/7 |
| d | U+0064 | 69 | 13 | 53.71428571 | 64.42857143 | 2/7 |
| L | U+004C | 45 | 13 | 67.92857143 | 78.64285714 | 2/7 |
| i | U+0069 | 74 | 5 | 82.14285714 | 84.28571429 | 2/7 |
| b | U+0062 | 67 | 13 | 87.78571429 | 98.5 | 2/7 |
