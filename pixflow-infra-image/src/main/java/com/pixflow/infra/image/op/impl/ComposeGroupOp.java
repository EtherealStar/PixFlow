package com.pixflow.infra.image.op.impl;

import com.pixflow.infra.image.ImageFormat;
import com.pixflow.infra.image.ImageProcessingException;
import com.pixflow.infra.image.RasterImage;
import com.pixflow.infra.image.geometry.ComposeGeometry;
import com.pixflow.infra.image.geometry.RasterDimensions;
import com.pixflow.infra.image.op.ComposeSpec;
import com.pixflow.infra.image.op.MultiImageOp;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class ComposeGroupOp implements MultiImageOp {
    private final ComposeSpec spec;

    public ComposeGroupOp(ComposeSpec spec) {
        this.spec = spec;
    }

    public ComposeSpec spec() {
        return spec;
    }

    @Override
    public RasterImage apply(List<RasterImage> members) {
        if (members == null || members.isEmpty()) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.INVALID_OP_PARAM,
                    null,
                    null,
                    null,
                    "合成图片列表不能为空");
        }
        List<RasterImage> ordered = ordered(members);
        return switch (spec.layout()) {
            case HORIZONTAL -> horizontal(ordered);
            case VERTICAL -> vertical(ordered);
            case GRID -> grid(ordered);
        };
    }

    private RasterImage horizontal(List<RasterImage> members) {
        RasterDimensions dimensions = dimensions(members);
        return draw(members, dimensions.intWidth(), dimensions.intHeight(),
                positionsHorizontal(members), members.get(0).sourceFormat());
    }

    private RasterImage vertical(List<RasterImage> members) {
        RasterDimensions dimensions = dimensions(members);
        return draw(members, dimensions.intWidth(), dimensions.intHeight(),
                positionsVertical(members), members.get(0).sourceFormat());
    }

    private RasterImage grid(List<RasterImage> members) {
        int count = members.size();
        // 只按数量推导稳定的近似方阵，不从图片内容猜业务排版。
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) columns);
        int cellWidth = members.stream().mapToInt(RasterImage::width).max().orElseThrow();
        int cellHeight = members.stream().mapToInt(RasterImage::height).max().orElseThrow();
        RasterDimensions dimensions = dimensions(members);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            RasterImage image = members.get(i);
            int col = i % columns;
            int row = i / columns;
            int x = col * (cellWidth + spec.gap()) + (cellWidth - image.width()) / 2;
            int y = row * (cellHeight + spec.gap()) + (cellHeight - image.height()) / 2;
            points.add(new Point(x, y));
        }
        return draw(members, dimensions.intWidth(), dimensions.intHeight(),
                points, members.get(0).sourceFormat());
    }

    private RasterDimensions dimensions(List<RasterImage> members) {
        return ComposeGeometry.resolve(members.stream()
                .map(image -> new RasterDimensions(image.width(), image.height()))
                .toList(), spec);
    }

    private RasterImage draw(
            List<RasterImage> members,
            int width,
            int height,
            List<Point> points,
            ImageFormat sourceFormat) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setColor(spec.background());
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < members.size(); i++) {
                Point p = points.get(i);
                g.drawImage(members.get(i).borrowBuffer(), p.x, p.y, null);
            }
        } finally {
            g.dispose();
        }
        return RasterImage.takeOwnership(output, sourceFormat);
    }

    private List<Point> positionsHorizontal(List<RasterImage> members) {
        int x = 0;
        int maxHeight = members.stream().mapToInt(RasterImage::height).max().orElseThrow();
        List<Point> points = new ArrayList<>();
        for (RasterImage image : members) {
            points.add(new Point(x, (maxHeight - image.height()) / 2));
            x += image.width() + spec.gap();
        }
        return points;
    }

    private List<Point> positionsVertical(List<RasterImage> members) {
        int y = 0;
        int maxWidth = members.stream().mapToInt(RasterImage::width).max().orElseThrow();
        List<Point> points = new ArrayList<>();
        for (RasterImage image : members) {
            points.add(new Point((maxWidth - image.width()) / 2, y));
            y += image.height() + spec.gap();
        }
        return points;
    }

    private List<RasterImage> ordered(List<RasterImage> members) {
        if (spec.order().isEmpty()) {
            return List.copyOf(members);
        }
        if (spec.order().size() != members.size()) {
            throw new ImageProcessingException(
                    ImageProcessingException.Reason.INVALID_OP_PARAM,
                    null,
                    null,
                    null,
                    "合成排序数量与图片数量不一致");
        }
        return spec.order().stream()
                .map(index -> {
                    if (index < 0 || index >= members.size()) {
                        throw new ImageProcessingException(
                                ImageProcessingException.Reason.INVALID_OP_PARAM,
                                null,
                                null,
                                null,
                                "合成排序索引越界");
                    }
                    return members.get(index);
                })
                .toList();
    }

    private record Point(int x, int y) {
    }
}
