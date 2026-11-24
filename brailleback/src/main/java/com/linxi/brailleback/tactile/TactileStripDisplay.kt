package com.linxi.brailleback.tactile

import android.content.Context
import android.util.Log
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient

/**
 * 为什么需要它：
 * 点显分页字节最终要落到硬件上。本类是 [ImageStripEncoder] 输出与 [DisplayClient] 之间的
 * 薄封装：持有一个自绑服务的 [DisplayClient]，把一页字节加页签文案下发到点显器，关闭时解绑。
 *
 * 设计约束：
 * - 只依赖 client 模块的 [DisplayClient]，不经过 DisplayManager；独立页面 / 测试可以直接 new。
 * - 无设备、服务未连接时 [DisplayClient] 内部自己记日志并丢帧，本类不因此崩溃；
 * - 下发前若发现页长与点显文本格容量不符，只记 [Log.w] 提醒，不抛异常——容量要等服务连上
 *   上报属性后才知道，无设备场景下拿不到属性属于常态。
 *
 * 注意：本类尚未在真实点显器上验证过连接与落屏效果，仅完成了代码层封装。
 */
class TactileStripDisplay(context: Context) {

    private val client = DisplayClient(context)

    /**
     * 把一页点位显示到点显器。
     *
     * @param page 每方一个字节的点位，位序 dot1=bit0 … dot8=bit7。
     * @param label 无障碍播报 / 文本等价物，透传给 displayDots。
     */
    fun show(page: ByteArray, label: CharSequence) {
        val cells = client.displayProperties?.numTextCells ?: 0
        if (cells > 0 && page.size != cells) {
            Log.w(
                LOG_TAG,
                "页面长度 ${page.size} 与点显文本格容量 $cells 不符，仍按原长度下发"
            )
        }
        client.displayDots(page, label, null)
    }

    /**
     * 挂上点显器按键监听：设备自己的平移键 / 上下行键 / 回车键。
     *
     * 事件由 DisplayService 广播给所有客户端，所以这里挂监听不影响服务侧原有分发；
     * 传 null 解除监听（Activity 销毁前调用，避免回调打到已解绑的界面）。
     */
    fun setOnInputEventListener(listener: Display.OnInputEventListener?) {
        client.setOnInputEventListener(listener)
    }

    /** 解绑服务、释放连接；之后不要再调用 [show]。 */
    fun close() {
        client.shutdown()
    }

    companion object {
        private const val LOG_TAG = "TactileStripDisplay"
    }
}
