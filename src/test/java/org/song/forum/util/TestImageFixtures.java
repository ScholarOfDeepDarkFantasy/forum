package org.song.forum.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 测试图片夹具：体积很小，但能覆盖「头部声明」与「EXIF 元数据」两类真实输入。
 * <p>解压炸弹用例只伪造 IHDR，不分配真实像素，避免测试自身 OOM。</p>
 */
public final class TestImageFixtures {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private TestImageFixtures() {
    }

    /**
     * 构造一个 IHDR 声明为 {@code width×height} 的 PNG，仅含最小合法 IDAT。
     * <p>文件本身只有几十字节；任何在解码前做尺寸校验的实现都能安全拒绝它。</p>
     */
    public static byte[] pngDeclaringSize(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);

        byte[] ihdr = new byte[13];
        writeIntBE(ihdr, 0, width);
        writeIntBE(ihdr, 4, height);
        ihdr[8] = 8;  // bit depth
        ihdr[9] = 2;  // color type: truecolor
        writeChunk(out, "IHDR", ihdr);

        // 最小 zlib 流（空 deflate 块），只为让 PNG 结构完整；解码器不会读到它之前就已被拒绝
        writeChunk(out, "IDAT", new byte[]{0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** 在 SOI 之后插入一个只带 Orientation 标签的 APP1 EXIF 段（小端 TIFF）。 */
    public static byte[] jpegWithExifOrientation(int orientation, byte[] jpeg) {
        byte[] tiff = {
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,          // 小端 TIFF 头，IFD0 偏移 8
                0x01, 0x00,                                            // 1 个条目
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,        // tag 0x0112 SHORT count=1
                (byte) orientation, 0x00, 0x00, 0x00,                  // 值
                0x00, 0x00, 0x00, 0x00                                 // 无下一个 IFD
        };
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        payload.writeBytes(tiff);
        byte[] exif = payload.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);              // SOI
        out.write(0xFF);
        out.write(0xE1);                    // APP1
        writeShortBE(out, exif.length + 2); // 段长包含长度字段自身
        out.writeBytes(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** 200×200 透明 PNG，左上角 100×100 是纯红方块。 */
    public static byte[] transparentPngWithRedQuadrant() throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(255, 0, 0, 255));
        graphics.fillRect(0, 0, 100, 100);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeIntBE(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeIntBE(out, (int) crc.getValue());
    }

    private static void writeIntBE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeIntBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShortBE(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
