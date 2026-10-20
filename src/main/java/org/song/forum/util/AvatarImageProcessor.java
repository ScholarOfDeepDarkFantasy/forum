package org.song.forum.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;
import org.song.forum.exception.BusinessException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 头像图片处理：全部是纯函数，方便单测。
 * <p>安全策略：只按魔数接受 JPEG/PNG，并且一定重新编码为 JPEG——不落原始字节，
 * 因此 SVG 脚本、EXIF 元数据、伪造 Content-Type 都不会被写入磁盘。</p>
 */
@Slf4j
public final class AvatarImageProcessor {

    /** 规格固定 256×256 */
    public static final int AVATAR_SIZE = 256;
    private static final float JPEG_QUALITY = 0.9f;
    /** 单边上限：解码前先看头部，避免解压炸弹把堆打满 */
    private static final int MAX_DIMENSION = 4096;
    /** 总像素上限（4096×4096），与单边上限共同限制解码内存 */
    private static final long MAX_PIXELS = 16_777_216L;
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

    private AvatarImageProcessor() {
    }

    public enum ImageType {
        JPEG, PNG
    }

    /** 魔数判定；不认识返回 null */
    public static ImageType detectType(byte[] data) {
        if (data == null) {
            return null;
        }
        if (startsWith(data, JPEG_MAGIC)) {
            return ImageType.JPEG;
        }
        if (startsWith(data, PNG_MAGIC)) {
            return ImageType.PNG;
        }
        return null;
    }

    /** EXIF Orientation，1–8；无 EXIF / 读取失败一律按 1（不旋转） */
    public static int readOrientation(byte[] data) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            Metadata metadata = ImageMetadataReader.readMetadata(in);
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        } catch (Exception e) {
            log.debug("读取 EXIF 方向失败，按不旋转处理", e);
            return 1;
        }
    }

    /**
     * 按 EXIF Orientation（1–8）做旋转/镜像。
     * <p>每种取值对应一个精确的仿射矩阵，输出尺寸在 5–8 时交换宽高：</p>
     * <ul>
     *   <li>2 水平镜像、(3) 旋转 180°、(4) 垂直镜像</li>
     *   <li>5 主对角线转置、(6) 顺时针 90°、(7) 副对角线转置、(8) 逆时针 90°</li>
     * </ul>
     */
    public static BufferedImage orientationTransform(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        boolean swapAxes = orientation >= 5;
        int outWidth = swapAxes ? height : width;
        int outHeight = swapAxes ? width : height;
        // 保留 alpha：透明 PNG 必须撑到 JPEG 编码前才铺白底，否则会被合成到黑底上
        BufferedImage target = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        return target;
    }

    /** 取 min(宽, 高) 为边长居中裁剪正方形 */
    public static BufferedImage centerCrop(BufferedImage source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - size) / 2;
        int y = (source.getHeight() - size) / 2;
        BufferedImage cropped = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cropped.createGraphics();
        graphics.drawImage(source, 0, 0, size, size, x, y, x + size, y + size, null);
        graphics.dispose();
        return cropped;
    }

    /** 缩放到指定尺寸（小图允许放大）；大图分步减半，避免单步双线性降采样产生锯齿 */
    public static BufferedImage resizeTo(BufferedImage source, int width, int height) {
        BufferedImage current = toArgb(source);
        int currentWidth = current.getWidth();
        int currentHeight = current.getHeight();
        while (currentWidth / 2 >= width && currentHeight / 2 >= height) {
            currentWidth /= 2;
            currentHeight /= 2;
            current = scale(current, currentWidth, currentHeight);
        }
        return scale(current, width, height);
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /** 重编码为 JPEG，质量 0.9 */
    public static byte[] toJpegBytes(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new BusinessException("服务端不支持 JPEG 编码");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
        } catch (IOException e) {
            throw new BusinessException("头像编码失败");
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 一次完成：魔数校验 → 头部尺寸校验 → 解码 → EXIF 方向 → 居中裁剪 → 256×256 → JPEG 字节 */
    public static byte[] process(byte[] raw) {
        if (detectType(raw) == null) {
            throw new BusinessException("仅支持 JPEG/PNG 图片");
        }
        BufferedImage decoded = decodeChecked(raw);
        BufferedImage oriented = orientationTransform(decoded, readOrientation(raw));
        BufferedImage squared = centerCrop(oriented);
        BufferedImage resized = resizeTo(squared, AVATAR_SIZE, AVATAR_SIZE);
        return toJpegBytes(resized);
    }

    /**
     * 先读头部宽高再解码：解压炸弹的压缩包很小，但声明尺寸可以是 20000×20000。
     * 在 {@code reader.read} 之前拒绝超大图，避免一次性分配数百 MB 像素内存。
     */
    private static BufferedImage decodeChecked(byte[] raw) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (input == null) {
                throw new BusinessException("无法解析图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException("无法解析图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException("图片尺寸过大，最大支持 "
                            + MAX_DIMENSION + "×" + MAX_DIMENSION);
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new BusinessException("无法解析图片");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("无法解析图片", e);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = argb.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return argb;
    }

    /** 带 alpha 的 PNG 铺白底再转 RGB，避免透明区被编码成黑块 */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return rgb;
    }
}
