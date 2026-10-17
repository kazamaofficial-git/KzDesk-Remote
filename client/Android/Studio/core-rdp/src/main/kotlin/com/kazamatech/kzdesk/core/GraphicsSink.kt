package com.kazamatech.kzdesk.core

/**
 * Sink event grafik dari inti FreeRDP (dipanggil di thread RDP).
 *
 * Implementasi KzDesk (M2) = [com.kazamatech.kzdesk.ui.SessionSurfaceController]
 * yang meneruskan ke `SessionView` (render) + `LibFreeRDP.updateGraphics`
 * (copy piksel ke bitmap permukaan) — semantik identik dengan jalur M0
 * (`SessionActivity.OnGraphicsUpdate/OnGraphicsResize`).
 *
 * Thread: semua metode bisa dipanggil dari thread RDP native — implementor
 * wajib post modifikasi View ke main thread.
 */
interface GraphicsSink {
    /** Region gambar berubah (piksel belum di-copy — sink harus panggil updateGraphics). */
    fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {}

    /** Desktop remote resize — bitmap permukaan harus diganti. */
    fun onGraphicsResize(width: Int, height: Int, bpp: Int) {}

    /** Kursor remote (pixels null/ukuran 0 = reset). */
    fun onPointerSet(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) {}

    fun onPointerSetNull() {}

    fun onPointerSetDefault() {}
}
