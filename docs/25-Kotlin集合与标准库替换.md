# 25-Kotlin 集合与标准库替换

> 日期：2026-11-15
> 目标：把「Kotlin 本来就内置」的 Java 集合 import 清掉，把 `Arrays`/`Collections` 调用换成标准库
> 结果：`import java.util.*` 89 → **52** 行，27 个文件 +13 / −50 行

---

## 为什么做这一批

上一轮清点出来的低风险项里，这一条最干净：Kotlin 对 `ArrayList`、`HashMap`、`HashSet`、
`LinkedHashMap`、`Comparator` 都有内置类型别名，**同一份代码删掉 import 就能编译**。
真正需要改的只有 `Arrays.*` 与 `Collections.*` 那几处。

判据是「删掉 import 后语义是否完全一致」，不是「看起来像 Java」。

---

## 改了什么

| 原来 | 现在 | 数量 |
|:---|:---|--:|
| `import java.util.ArrayList / HashMap / HashSet / LinkedHashMap / Comparator` | 删除（Kotlin 内置别名） | 32 行 |
| `Arrays.hashCode(x)` | `x.contentHashCode()` | 3 |
| `Arrays.sort(array, cmp)` | `array.sortWith(cmp)` | 1 |
| `Arrays.asList(*array)` | `array.asList()` | 1 |
| `Collections.sort(list, cmp)` | `list.sortWith(cmp)` | 2 |
| `Collections.binarySearch(list, x, cmp)` | `list.binarySearch(x, cmp)` | 1 |
| `Collections.emptyList() / emptyMap()` | `emptyList() / emptyMap()` | 12 |

计数（git 跟踪的 `.kt`）：

```
import java.util.*     89 → 52
Collections. 调用      24 → 9
Arrays. 调用            5 → 1
```

剩下的 `Collections.` 调用是故意留下的：`Collections.unmodifiableList/Map/Set` 是**防御性视图**，
换成 `toList()` 会从「视图」变成「复制」，语义和大列表开销都变了；`java.util.Collections.swap` 也没有
完全等价的 Kotlin 写法。这类不为了好看去动。

---

## 两次编译失败，都是 ArrayDeque

Kotlin 1.4 起自带 `kotlin.collections.ArrayDeque`，但它**不是** `java.util.Deque` 的实现：

| 文件 | 报错 | 结论 |
|:---|:---|:---|
| `WujieGattConnection.kt` | `Unresolved reference 'poll'` | Kotlin 版没有 `poll()`，改回 `import java.util.ArrayDeque` |
| `BrailleImeHostBridge.kt` | `expected 'Queue<CharSequence>', actual 'ArrayDeque<...>'` | 字段类型是 `java.util.Queue`，Kotlin 版不满足，同样改回 |

教训：**「Kotlin 有同名类型」不等于「API 相同」**。集合类型别名可以删 import，队列/双端队列要先看方法。

---

## 验证

| 项 | 结果 |
|:---|:---|
| `:brailleback:compileDebugKotlin` / `:service:compileDebugKotlin` | 通过 |
| 主模块单测 | **50 套件 / 520 用例 / 0 失败** |
| service 单测 | **24 套件 / 168 用例 / 0 失败** |
| `detektCheck` | 0 个 baseline 外新问题 |
| `lintDebug` | **0 errors / 536 warnings**（未变） |
| 真机冒烟 | 5 轮 `Crashed services:{}`、两进程在、无 FATAL；无障碍清单已恢复 |

排序语义没变：`Arrays.sort(Object[], Comparator)` 是稳定排序，Kotlin 的 `sortWith` 底层就是它；
`Collections.sort(List)` 同理。`contentHashCode()` 与 `Arrays.hashCode()` 算法相同。

---

## 没动的那部分

- `Collections.unmodifiable*`（9 处）与 `Collections.swap`（1 处）
- `java.util.concurrent`（Atomic*/CountDownLatch/TimeUnit/Executor）——这些没有 Kotlin 等价物，
  换协程属于运行时改造，不在这一批
- `mXxx` 字段名与 `fun getXxx()` 取值器：它们被三份源码契约逐条断言，改名等于重写公开 API
