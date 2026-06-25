package com.etherealstar.pixflow.infra.image;

import java.awt.image.BufferedImage;

/**
 * {@link BackgroundRemovalClient} 的离线兜底实现（需求 8.4）。
 *
 * <p>MVP 阶段在未接入或未配置第三方抠图 API 时提供一个可离线运行的简单实现：以图像四角采样估计
 * 背景色，将与背景色相近的像素置为透明，得到带 alpha 通道的图像。它使整条链路在无外部依赖时仍可
 * 端到端运行与测试；接入真实第三方服务时，只需提供另一个 {@link BackgroundRemovalClient} Bean
 * 即可通过 {@link ConditionalOnMissingBean} 覆盖本实现。</p>
 *
 * <p>注意：这是占位实现，非生产级抠图质量。</p>
 */
public class NaiveBackgroundRemovalClient implements BackgroundRemovalClient {

    /** 与背景色的颜色距离阈值（0–441），小于该值的像素视为背景并置透明。 */
    private static final int COLOR_DISTANCE_THRESHOLD = 60;

    @Override
    public BufferedImage removeBackground(BufferedImage input) {
        if (input == null) {
            throw new ImageProcessingException("待抠图图像为空");
        }
        int w = input.getWidth();
        int h = input.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int bg = estimateBackgroundColor(input);
        int bgR = (bg >> 16) & 0xFF;
        int bgG = (bg >> 8) & 0xFF;
        int bgB = bg & 0xFF;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = input.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int dr = r - bgR;
                int dg = g - bgG;
                int db = b - bgB;
                double dist = Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db);
                if (dist <= COLOR_DISTANCE_THRESHOLD) {
                    out.setRGB(x, y, 0x00000000); // 透明
                } else {
                    out.setRGB(x, y, 0xFF000000 | (argb & 0x00FFFFFF));
                }
            }
        }
        return out;
    }

    /** 以四角像素的均值估计背景色。 */
    private int estimateBackgroundColor(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] corners = {
                img.getRGB(0, 0),
                img.getRGB(w - 1, 0),
                img.getRGB(0, h - 1),
                img.getRGB(w - 1, h - 1)
        };
        long r = 0;
        long g = 0;
        long b = 0;
        for (int c : corners) {
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int n = corners.length;
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }
}
