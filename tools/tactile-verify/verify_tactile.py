#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""触觉图（图片→凸起图）Python 独立复算 / 校验器。

为什么需要它：
Kotlin 侧的 TactileRasterizer + TactileBmpEncoder 是纯 JVM 内核，单测只能证明“它和它自己一致”。
本脚本用完全独立的 Python 实现，按同一份规格把「源灰度平面 + 设置 + 页面参数」重新算一遍，
再和内核真实输出的 BMP 做逐字节比对；顺带校验 BMP 结构（头字段、调色板、行序、行填充）。

校验项：
  1) BMP 结构：文件头/信息头/调色板/偏移/DPI/行 4 字节对齐/自下而上
  2) 像素：Python 复算的 level 与 BMP 实际像素逐点一致
  3) 字节：Python 重新编码出的整份 BMP 与内核输出 SHA256 完全相同

用法：
  python verify_tactile.py --dump-dir <内核导出目录> [--device-src a.bmp --device-out b.bmp]

规格（与 Kotlin 一一对应）：
  luma 已由调用方给出（0..255）；此脚本只复算栅格化与编码，不复算 RGB→luma。
"""

import argparse
import hashlib
import math
import os
import struct
import sys

import numpy as np

MM_PER_INCH = 25.4
RAISED = 255
FLAT = 0
SOBEL_MAX = 255.0 * 4
DEFAULT_LONG_EDGE_MM = 160.0


def round_half_up(x):
    """Kotlin roundToInt 对正数等价于 floor(x + 0.5)。"""
    return int(math.floor(float(x) + 0.5))


# ---------------------------------------------------------------- BMP 编码


def encode_bmp(levels, dpi):
    """levels: (h, w) uint8；返回完整 BMP 字节（8 位灰度，自下而上，4 字节对齐）。"""
    h, w = levels.shape
    row_size = ((w + 3) // 4) * 4
    pixel_size = row_size * h
    total = 1078 + pixel_size
    ppm = round_half_up(dpi / 0.0254)

    buf = bytearray(total)
    buf[0:2] = b"BM"
    struct.pack_into("<I", buf, 2, total)
    struct.pack_into("<I", buf, 10, 1078)

    struct.pack_into("<I", buf, 14, 40)
    struct.pack_into("<i", buf, 18, w)
    struct.pack_into("<i", buf, 22, h)
    struct.pack_into("<H", buf, 26, 1)
    struct.pack_into("<H", buf, 28, 8)
    struct.pack_into("<I", buf, 30, 0)
    struct.pack_into("<I", buf, 34, pixel_size)
    struct.pack_into("<i", buf, 38, ppm)
    struct.pack_into("<i", buf, 42, ppm)

    for v in range(256):
        off = 54 + v * 4
        buf[off] = buf[off + 1] = buf[off + 2] = v
        buf[off + 3] = 0

    cursor = 1078
    for y in range(h - 1, -1, -1):
        buf[cursor:cursor + w] = (255 - levels[y]).astype(np.uint8).tobytes()
        cursor += row_size
    return bytes(buf)


def read_bmp(path):
    data = open(path, "rb").read()
    if data[:2] != b"BM":
        raise ValueError(f"{path}: 不是 BMP")
    off = struct.unpack("<I", data[10:14])[0]
    (info_len, w, h, planes, bpp, comp, img_size, xppm, yppm,
     clr_used, clr_imp) = struct.unpack("<IiiHHIIiiII", data[14:54])
    if bpp != 8 or comp != 0 or planes != 1:
        raise ValueError(f"{path}: 期望 8 位无压缩单平面，实际 bpp={bpp} comp={comp} planes={planes}")
    row = ((w + 3) // 4) * 4
    raw = np.frombuffer(data, dtype=np.uint8, count=row * h, offset=off)
    pixels = raw.reshape(h, row)[:, :w][::-1].astype(np.uint8)  # 翻正成自上而下
    palette = np.frombuffer(data[54:54 + 1024], dtype=np.uint8).reshape(256, 4)
    return dict(data=data, w=w, h=h, dpi=(xppm, yppm), pixels=pixels,
                palette=palette, off=off, info_len=info_len,
                img_size=img_size, clr_used=clr_used, clr_imp=clr_imp,
                row=row, total=len(data))


# ---------------------------------------------------------------- 栅格化


def mm_to_px(mm, dpi):
    return max(1, round_half_up(mm * (dpi / MM_PER_INCH)))


def derive_box(src_w, src_h, width_mm, height_mm):
    ratio = src_w / src_h
    w_mm, h_mm = (0.0 if (math.isnan(x) or math.isinf(x) or x <= 0) else float(x)
                  for x in (width_mm, height_mm))
    if w_mm > 0 and h_mm > 0:
        return w_mm, h_mm
    if w_mm == 0 and h_mm == 0:
        if src_w >= src_h:
            return DEFAULT_LONG_EDGE_MM, DEFAULT_LONG_EDGE_MM / ratio
        return DEFAULT_LONG_EDGE_MM * ratio, DEFAULT_LONG_EDGE_MM
    if h_mm == 0:
        return w_mm, w_mm / ratio
    return h_mm * ratio, h_mm


def fit_content(src_w, src_h, page_w, page_h):
    scale = min(page_w / src_w, page_h / src_h)
    cw = min(round_half_up(src_w * scale), page_w)
    ch = min(round_half_up(src_h * scale), page_h)
    return cw, ch, (page_w - cw) // 2, (page_h - ch) // 2


def resample_area(src, tw, th):
    """面积平均重采样：每个目标像素对源矩形 [x0,x1)×[y0,y1) 做重叠面积加权平均。"""
    sh, sw = src.shape
    sx = sw / tw
    sy = sh / th
    srcf = src.astype(np.float64)

    # 列向权重矩阵：Wx[tx, ix] = 第 tx 个目标列与源第 ix 列的重叠长度
    wx = np.zeros((tw, sw), dtype=np.float64)
    for tx in range(tw):
        x0, x1 = tx * sx, (tx + 1) * sx
        ix0 = max(0, min(sw - 1, int(x0)))
        ix1 = max(0, min(sw - 1, int(x1 - 1.0)))
        for ix in range(ix0, ix1 + 1):
            ov = min(x1, ix + 1.0) - max(x0, float(ix))
            if ov > 0:
                wx[tx, ix] = ov

    wy = np.zeros((th, sh), dtype=np.float64)
    for ty in range(th):
        y0, y1 = ty * sy, (ty + 1) * sy
        iy0 = max(0, min(sh - 1, int(y0)))
        iy1 = max(0, min(sh - 1, int(y1 - 1.0)))
        for iy in range(iy0, iy1 + 1):
            ov = min(y1, iy + 1.0) - max(y0, float(iy))
            if ov > 0:
                wy[ty, iy] = ov

    num = wy @ srcf @ wx.T
    den = wy.sum(axis=1)[:, None] * wx.sum(axis=1)[None, :]
    out = np.zeros((th, tw), dtype=np.int64)
    ok = den > 0
    out[ok] = np.floor(num[ok] / den[ok] + 0.5).astype(np.int64)
    np.clip(out, 0, 255, out=out)
    return out


def sobel_levels(gray, threshold):
    h, w = gray.shape
    p = np.pad(gray, 1, mode="edge").astype(np.int64)
    gx = (p[0:h, 2:w + 2] + 2 * p[1:h + 1, 2:w + 2] + p[2:h + 2, 2:w + 2]) - \
         (p[0:h, 0:w] + 2 * p[1:h + 1, 0:w] + p[2:h + 2, 0:w])
    gy = (p[2:h + 2, 0:w] + 2 * p[2:h + 2, 1:w + 1] + p[2:h + 2, 2:w + 2]) - \
         (p[0:h, 0:w] + 2 * p[0:h, 1:w + 1] + p[0:h, 2:w + 2])
    mag = np.hypot(gx.astype(np.float64), gy.astype(np.float64))
    norm = np.floor(np.minimum(mag, SOBEL_MAX) / SOBEL_MAX * 255.0 + 0.5).astype(np.int64)
    norm = np.clip(norm, 0, 255)
    return np.where(norm > threshold, RAISED, FLAT).astype(np.int64)


def rasterize(src, mode, threshold, invert, width_mm, height_mm, dpi, page_w_mm, page_h_mm):
    sh, sw = src.shape
    threshold = max(0, min(255, int(threshold)))
    box_w_mm, box_h_mm = derive_box(sw, sh, width_mm, height_mm)
    page_w = mm_to_px(box_w_mm, dpi)
    page_h = mm_to_px(box_h_mm, dpi)

    cw, ch, ox, oy = fit_content(sw, sh, page_w, page_h)
    gray = resample_area(src, cw, ch)

    if mode == "THRESHOLD":
        levels = np.where(gray < threshold, RAISED, FLAT).astype(np.int64)
    elif mode == "GRAYSCALE":
        levels = RAISED - gray
    elif mode == "EDGE":
        levels = sobel_levels(gray, threshold)
    else:
        raise ValueError(f"未知模式 {mode}")

    if invert:
        levels = RAISED - levels

    page = np.zeros((page_h, page_w), dtype=np.int64)
    page[oy:oy + ch, ox:ox + cw] = levels
    return page.astype(np.uint8)


# ---------------------------------------------------------------- 校验


def structural_report(bmp, path):
    problems = []
    h, w = bmp["pixels"].shape
    expect_row = ((w + 3) // 4) * 4
    if bmp["off"] != 1078:
        problems.append(f"像素偏移 {bmp['off']} ≠ 1078")
    if bmp["info_len"] != 40:
        problems.append(f"信息头长度 {bmp['info_len']} ≠ 40")
    if bmp["row"] != expect_row:
        problems.append(f"行对齐 {bmp['row']} ≠ {expect_row}")
    if bmp["img_size"] != expect_row * h:
        problems.append(f"图像字节数 {bmp['img_size']} ≠ {expect_row * h}")
    if bmp["total"] != 1078 + expect_row * h:
        problems.append("文件总长与头不符")
    if bmp["clr_used"] != 0 or bmp["clr_imp"] != 0:
        problems.append("biClrUsed/Important 应留 0")
    if bmp["dpi"][0] != bmp["dpi"][1]:
        problems.append("x/y 分辨率不一致")
    if not (bmp["palette"][:, 0] == np.arange(256)).all() or not (bmp["palette"][:, 3] == 0).all():
        problems.append("调色板不是恒等灰度 / 第 4 字节非 0")
    # 行填充必须为 0
    raw = np.frombuffer(bmp["data"], dtype=np.uint8, count=bmp["row"] * h, offset=bmp["off"])
    raw = raw.reshape(h, bmp["row"])
    if w < bmp["row"] and raw[:, w:].any():
        problems.append("行填充字节存在非 0")
    return problems


def check_case(dump_dir, name):
    meta = {}
    for line in open(os.path.join(dump_dir, name + ".meta"), encoding="utf-8"):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            meta[k] = v
    src = np.frombuffer(open(os.path.join(dump_dir, name + ".src"), "rb").read(), dtype=np.uint8)
    src = src.reshape(int(meta["srcH"]), int(meta["srcW"]))
    kernel_bmp = read_bmp(os.path.join(dump_dir, name + ".bmp"))

    page = rasterize(
        src, meta["mode"], int(meta["threshold"]), meta["invert"] == "true",
        float(meta["widthMm"]), float(meta["heightMm"]),
        int(meta["dpi"]), float(meta["pageWmm"]), float(meta["pageHmm"]),
    )

    problems = structural_report(kernel_bmp, name)
    if (page.shape[1], page.shape[0]) != (kernel_bmp["w"], kernel_bmp["h"]):
        problems.append(f"尺寸 {page.shape[1]}×{page.shape[0]} ≠ BMP {kernel_bmp['w']}×{kernel_bmp['h']}")
        return problems, None, None

    expect_px = 255 - page  # 编码器只在这里翻一次极性
    diff = np.abs(expect_px.astype(np.int16) - kernel_bmp["pixels"].astype(np.int16))
    mismatches = int((diff > 0).sum())
    if mismatches:
        problems.append(f"像素不一致 {mismatches}/{diff.size}（最大差 {int(diff.max())}）")

    expect_bytes = encode_bmp(page, int(meta["dpi"]))
    same = hashlib.sha256(expect_bytes).hexdigest() == hashlib.sha256(kernel_bmp["data"]).hexdigest()
    if not same:
        problems.append("整份 BMP 字节不一致（编码器布局有偏差）")
    return problems, mismatches, (expect_bytes, kernel_bmp["data"])


def check_device_pair(src_path, out_path):
    """设备真实链路：源 BMP（经 Android 解码）→ 导出 BMP。1:1、A4、阈值 128。"""
    src_bmp = read_bmp(src_path)
    out_bmp = read_bmp(out_path)
    src_pix = src_bmp["pixels"].astype(np.uint8)
    page = rasterize(src_pix, "THRESHOLD", 128, False, 210.0, 297.0,
                     300, 210.0, 297.0)
    problems = structural_report(out_bmp, out_path)
    if (page.shape[1], page.shape[0]) != (out_bmp["w"], out_bmp["h"]):
        problems.append("尺寸不一致")
        return problems, None
    expect_px = 255 - page
    mismatch = int((expect_px != out_bmp["pixels"]).sum())
    if mismatch:
        problems.append(f"像素不一致 {mismatch}")
    same = hashlib.sha256(encode_bmp(page, 300)).hexdigest() == hashlib.sha256(out_bmp["data"]).hexdigest()
    if not same:
        problems.append("整份 BMP 字节不一致")
    return problems, mismatch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", required=True)
    ap.add_argument("--device-src")
    ap.add_argument("--device-out")
    args = ap.parse_args()

    fails = 0
    names = sorted({f[:-4] for f in os.listdir(args.dump_dir) if f.endswith(".bmp")})
    print(f"用例 {len(names)} 个，目录 {args.dump_dir}")
    for name in names:
        problems, mism, _ = check_case(args.dump_dir, name)
        bmp = read_bmp(os.path.join(args.dump_dir, name + ".bmp"))
        status = "PASS" if not problems else "FAIL"
        if problems:
            fails += 1
        print(f"  [{status}] {name:22s} {bmp['w']}×{bmp['h']} 像素差 {mism if mism is not None else '-'}"
              + ("" if not problems else "  ← " + "；".join(problems)))

    if args.device_src and args.device_out:
        problems, mism = check_device_pair(args.device_src, args.device_out)
        status = "PASS" if not problems else "FAIL"
        if problems:
            fails += 1
        print(f"  [{status}] 设备实链路 {os.path.basename(args.device_out)} 像素差 {mism}"
              + ("" if not problems else "  ← " + "；".join(problems)))

    print("结果:", "全部通过" if fails == 0 else f"{fails} 项失败")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
