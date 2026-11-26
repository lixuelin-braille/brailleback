/*
 * Metec 驱动 moduleCount 取整算术的 host 断言测试。
 *
 * 被测逻辑与
 * service/jni/brlttywrapper/third_party/brltty/Drivers/Braille/Metec/braille.c
 * 中 setCellCount() 的越界守卫 + 向上取整公式逐行对应；常量 MT_MODULE_SIZE /
 * MT_MODULES_MAXIMUM / MT_CELLS_MAXIMUM 取自该文件头部。这里把不依赖 brl_driver.h
 * 的守卫与算术单独抽出来编成 host 可执行程序跑断言，便于在没有真机、不跑完整
 * NDK 工程的环境复现。
 *
 * 编译（NDK clang，host Windows，msys64 mingw64 作 sysroot）：
 *   clang --target=x86_64-w64-mingw32 --sysroot=C:/tools/msys64/mingw64 \
 *         -x c tools/metec_cellcount_host_test.c -o metec_cellcount_host_test.exe
 * 运行后退出码 0 = 全部断言通过；任一断言失败退出码 1。
 *
 * 注意：本文件的三个常量必须与 braille.c 头部保持一致；Kotlin 侧契约测试
 * （MetecCellCountContractTest）直接从 braille.c 源码解析常量并复核映射表。
 */
#include <stdio.h>

#define MT_MODULE_SIZE 8
#define MT_MODULES_MAXIMUM 10
#define MT_CELLS_MAXIMUM (MT_MODULES_MAXIMUM * MT_MODULE_SIZE)

typedef struct {
  unsigned char cellCount;
  unsigned char moduleCount;
} MetecCellCountState;

/* 与 braille.c setCellCount() 同构：先越界守卫，再向上取整算模块数。 */
static int
setCellCount (MetecCellCountState *data, unsigned char count) {
  if (count > MT_CELLS_MAXIMUM) {
    printf("  reject count=%u (> %d)\n", (unsigned int) count, MT_CELLS_MAXIMUM);
    return 0;
  }

  data->moduleCount = ((data->cellCount = count) + MT_MODULE_SIZE - 1) / MT_MODULE_SIZE;
  return 1;
}

static int failures = 0;

#define CHECK(cond, msg) do { \
  if (cond) { printf("  PASS: %s\n", msg); } \
  else { printf("  FAIL: %s\n", msg); failures++; } \
} while (0)

int main(void) {
  MetecCellCountState state;

  printf("== capacity constants: MT_MODULE_SIZE=%d MT_MODULES_MAXIMUM=%d "
         "MT_CELLS_MAXIMUM=%d\n",
         MT_MODULE_SIZE, MT_MODULES_MAXIMUM, MT_CELLS_MAXIMUM);

  /* 42 格（40 文本 + 2 状态）→ 向上取整 6 模块，6*8=48 字节 ≤ newCells[80]，
   * writeModule 6 项 ≤ 10 项。 */
  {
    state.cellCount = 0; state.moduleCount = 0;
    int ok = setCellCount(&state, 42);
    CHECK(ok == 1, "42 格未被越界守卫拒绝");
    CHECK(state.moduleCount == 6, "42 -> 6 模块（向上取整）");
    CHECK((unsigned int) state.moduleCount * MT_MODULE_SIZE <= MT_CELLS_MAXIMUM,
          "42 的 48 字节落在 newCells 80 字节内");
    CHECK(state.moduleCount <= MT_MODULES_MAXIMUM,
          "42 的 6 项落在 writeModule 10 项内");
  }

  /* 22 格 → 向上取整 3 模块，3*8=24 字节。 */
  {
    state.cellCount = 0; state.moduleCount = 0;
    int ok = setCellCount(&state, 22);
    CHECK(ok == 1, "22 格未被越界守卫拒绝");
    CHECK(state.moduleCount == 3, "22 -> 3 模块（向上取整）");
    CHECK((unsigned int) state.moduleCount * MT_MODULE_SIZE <= MT_CELLS_MAXIMUM,
          "22 的 24 字节落在 newCells 80 字节内");
  }

  /* 40 格 → 整除边界 5 模块，5*8=40 字节。 */
  {
    state.cellCount = 0; state.moduleCount = 0;
    int ok = setCellCount(&state, 40);
    CHECK(ok == 1, "40 格未被越界守卫拒绝");
    CHECK(state.moduleCount == 5, "40 -> 5 模块（整除边界）");
  }

  /* 越界：81 > 80 必须拒绝，守卫返回 0，不得写入 moduleCount。 */
  {
    state.cellCount = 0; state.moduleCount = 0xFF;
    int ok = setCellCount(&state, 81);
    CHECK(ok == 0, "81 格被越界守卫拒绝");
  }

  /* 合法上限 80 → 10 模块，80 字节，恰好顶满 newCells 与 writeModule 容量。 */
  {
    state.cellCount = 0; state.moduleCount = 0;
    int ok = setCellCount(&state, 80);
    CHECK(ok == 1, "80 格（合法上限）未被拒绝");
    CHECK(state.moduleCount == 10, "80 -> 10 模块");
    CHECK(state.moduleCount == MT_MODULES_MAXIMUM, "writeModule 恰好用满 10 项");
    CHECK((unsigned int) state.moduleCount * MT_MODULE_SIZE == MT_CELLS_MAXIMUM,
          "80 的 80 字节恰好用满 newCells");
  }

  if (failures) {
    printf("== FAIL: %d 条断言未通过\n", failures);
    return 1;
  }
  printf("== ALL PASS: 全部取整与守卫断言通过\n");
  return 0;
}
