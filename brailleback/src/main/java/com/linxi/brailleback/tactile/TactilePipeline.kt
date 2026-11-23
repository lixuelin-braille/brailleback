package com.linxi.brailleback.tactile

import java.io.File

/**
 * 为什么需要它：
 * 入口层（导出界面）不该自己拼「解码 → 栅格化 → 编码」三步：每换一种来源（相册、文件、
 * 测试样本）都重复一遍接线，容易漏掉设置归一化或异常翻译。这里把三段固定成一条流水线，
 * 界面只给文件、设置与版面，出错时各层抛出的异常原样往上交，由入口层翻译成可读提示。
 */
object TactilePipeline {

    /**
     * 一张图片文件 → 8 位灰度 BMP 字节。
     *
     * 串起 [AndroidImageLoader]（解码成灰度平面）→ [TactileRasterizer]（按策略抬升等级）→
     * [TactileBmpEncoder]（落成 BMP 字节）。本方法不做界面、不碰线程，调用方负责后台执行。
     */
    fun imageToBmp(
        file: File,
        settings: TactileSettings,
        profile: TactileProfile = TactileProfile.A4,
    ): ByteArray {
        val image = AndroidImageLoader.load(file)
        val raster = TactileRasterizer.rasterize(image, settings, profile)
        return TactileBmpEncoder.encode(raster)
    }
}
