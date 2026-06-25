package com.etherealstar.pixflow.infra.image;

import com.etherealstar.pixflow.infra.storage.StorageService;
import com.etherealstar.pixflow.module.dag.domain.DagNode;
import com.etherealstar.pixflow.module.dag.schema.ToolType;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

/**
 * 像素工具节点执行器（需求 8.4，infra/image）。
 *
 * <p>依据 {@link DagNode#getTool()} 将单个像素工具节点应用到 {@link ImageData} 中间态，覆盖
 * {@code remove_bg}、{@code set_background}、{@code resize}、{@code compress}、{@code watermark}、
 * {@code convert_format} 六类像素工具。{@code generate_copy} 不在此处理——它被建模为独立的文案
 * 生成分支（需求 10.1、10.6），由执行引擎单独调度。</p>
 *
 * <p>任一处理失败（含第三方抠图 API 错误）以 {@link ImageProcessingException} 抛出，交由执行引擎
 * 的失败隔离逻辑处理（需求 11.1）。参数已在 DAG_Validator 阶段通过 schema 校验，此处按约定取值，
 * 对意外取值仍做防御性校验。</p>
 */
@Component
public class ImageToolExecutor {

    private static final String DEFAULT_BACKGROUND = "#FFFFFF";

    private final BackgroundRemovalClient backgroundRemovalClient;
    private final ImageCodec imageCodec;
    private final StorageService storageService;

    public ImageToolExecutor(BackgroundRemovalClient backgroundRemovalClient,
                             ImageCodec imageCodec,
                             StorageService storageService) {
        this.backgroundRemovalClient = backgroundRemovalClient;
        this.imageCodec = imageCodec;
        this.storageService = storageService;
    }

    /**
     * 将一个像素工具节点应用到当前图像中间态，返回处理后的中间态。
     *
     * @param node 像素工具节点（tool 须为像素类工具）
     * @param data 当前图像中间态
     * @return 处理后的图像中间态
     * @throws ImageProcessingException 当工具不受支持或处理失败时
     */
    public ImageData apply(DagNode node, ImageData data) {
        ToolType toolType = ToolType.fromToolName(node.getTool())
                .orElseThrow(() -> new ImageProcessingException("非白名单工具: " + node.getTool()));
        Map<String, Object> params = node.getParams();
        return switch (toolType) {
            case REMOVE_BG -> removeBackground(data);
            case SET_BACKGROUND -> setBackground(data, params);
            case RESIZE -> resize(data, params);
            case COMPRESS -> compress(data, params);
            case WATERMARK -> watermark(data, params);
            case CONVERT_FORMAT -> convertFormat(data, params);
            case GENERATE_COPY -> throw new ImageProcessingException(
                    "generate_copy 为独立文案分支，不应作为像素节点执行");
        };
    }

    // ---- remove_bg：第三方抠图，产出带透明通道的 PNG ------------------------

    private ImageData removeBackground(ImageData data) {
        BufferedImage result = backgroundRemovalClient.removeBackground(data.getImage());
        if (result == null) {
            throw new ImageProcessingException("抠图服务返回空结果");
        }
        // 去背景后含透明通道，输出格式切换为支持 alpha 的 PNG（除非后续显式转换）。
        return new ImageData(result, "PNG", data.getMaxKb());
    }

    // ---- set_background：在底层铺纯色背景并合成 ----------------------------

    private ImageData setBackground(ImageData data, Map<String, Object> params) {
        String colorStr = stringParam(params, "color", DEFAULT_BACKGROUND);
        Color color = parseColor(colorStr);

        BufferedImage src = data.getImage();
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return data.withImage(out);
    }

    // ---- resize：缩放到指定宽高 -------------------------------------------

    private ImageData resize(ImageData data, Map<String, Object> params) {
        int width = positiveIntParam(params, "width");
        int height = positiveIntParam(params, "height");
        try {
            BufferedImage out = Thumbnails.of(data.getImage())
                    .forceSize(width, height)
                    .asBufferedImage();
            return data.withImage(out);
        } catch (Exception e) {
            throw new ImageProcessingException("缩放失败: " + e.getMessage(), e);
        }
    }

    // ---- compress：记录目标体积，在编码时实际压缩 --------------------------

    private ImageData compress(ImageData data, Map<String, Object> params) {
        int maxKb = positiveIntParam(params, "max_kb");
        return data.withMaxKb(maxKb);
    }

    // ---- watermark：文字或图片水印 ----------------------------------------

    private ImageData watermark(ImageData data, Map<String, Object> params) {
        String position = stringParam(params, "position", "bottom-right");
        String text = stringParam(params, "text", null);
        String imagePath = stringParam(params, "image", null);

        BufferedImage src = data.getImage();
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, null);
            if (text != null && !text.isBlank()) {
                drawTextWatermark(g, out.getWidth(), out.getHeight(), text, position);
            } else if (imagePath != null && !imagePath.isBlank()) {
                drawImageWatermark(g, out.getWidth(), out.getHeight(), imagePath, position);
            } else {
                throw new ImageProcessingException("watermark 需提供 text 或 image 其一");
            }
        } finally {
            g.dispose();
        }
        return data.withImage(out);
    }

    private void drawTextWatermark(Graphics2D g, int w, int h, String text, String position) {
        int fontSize = Math.max(12, Math.min(w, h) / 20);
        g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();
        int[] xy = anchor(w, h, textWidth, textHeight, position);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(Color.BLACK);
        g.drawString(text, xy[0] + 1, xy[1] + textHeight + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, xy[0], xy[1] + textHeight);
    }

    private void drawImageWatermark(Graphics2D g, int w, int h, String imagePath, String position) {
        byte[] bytes;
        try {
            bytes = storageService.readAllBytes(imagePath);
        } catch (Exception e) {
            throw new ImageProcessingException("无法读取水印图片: " + imagePath, e);
        }
        BufferedImage mark = imageCodec.decode(bytes);
        int markW = Math.min(mark.getWidth(), w / 4);
        int markH = mark.getHeight() * markW / Math.max(1, mark.getWidth());
        int[] xy = anchor(w, h, markW, markH, position);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        g.drawImage(mark, xy[0], xy[1], markW, markH, null);
    }

    /** 依据九宫格位置计算左上角锚点坐标（含 margin）。 */
    private int[] anchor(int w, int h, int objW, int objH, String position) {
        int margin = Math.max(8, Math.min(w, h) / 40);
        String pos = position == null ? "bottom-right" : position.toLowerCase(Locale.ROOT);
        int x;
        int y;
        if (pos.contains("left")) {
            x = margin;
        } else if (pos.contains("right")) {
            x = w - objW - margin;
        } else {
            x = (w - objW) / 2;
        }
        if (pos.startsWith("top")) {
            y = margin;
        } else if (pos.startsWith("bottom")) {
            y = h - objH - margin;
        } else {
            y = (h - objH) / 2;
        }
        return new int[]{Math.max(0, x), Math.max(0, y)};
    }

    // ---- convert_format：切换输出格式 -------------------------------------

    private ImageData convertFormat(ImageData data, Map<String, Object> params) {
        String format = stringParam(params, "format", null);
        if (format == null || format.isBlank()) {
            throw new ImageProcessingException("convert_format 缺少 format 参数");
        }
        return data.withFormat(format);
    }

    // ---- 参数解析辅助 ------------------------------------------------------

    private String stringParam(Map<String, Object> params, String name, String defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(name);
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? defaultValue : s;
    }

    private int positiveIntParam(Map<String, Object> params, String name) {
        if (params == null || params.get(name) == null) {
            throw new ImageProcessingException("缺少必填参数: " + name);
        }
        Object value = params.get(name);
        int result;
        if (value instanceof Number number) {
            result = number.intValue();
        } else {
            try {
                result = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new ImageProcessingException("参数 " + name + " 不是合法整数: " + value, e);
            }
        }
        if (result <= 0) {
            throw new ImageProcessingException("参数 " + name + " 必须为正整数: " + result);
        }
        return result;
    }

    private Color parseColor(String colorStr) {
        String s = colorStr == null ? DEFAULT_BACKGROUND : colorStr.trim();
        try {
            return Color.decode(s.startsWith("#") ? s : "#" + s);
        } catch (NumberFormatException e) {
            throw new ImageProcessingException("非法颜色值: " + colorStr, e);
        }
    }
}
