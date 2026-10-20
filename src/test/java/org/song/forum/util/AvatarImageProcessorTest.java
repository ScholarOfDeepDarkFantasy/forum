package org.song.forum.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.song.forum.exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯函数测试：方向映射、居中裁剪、缩放到 256、魔数判定、坏数据。
 */
class AvatarImageProcessorTest {

    /** 每个像素的颜色编码自身坐标：(x &lt;&lt; 16) | (y &lt;&lt; 8) | 7，便于断言映射 */
    private static BufferedImage gridImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x << 16) | (y << 8) | 7);
            }
        }
        return image;
    }

    private static int expectedRgb(int x, int y) {
        return (x << 16) | (y << 8) | 7;
    }

    private static int colorAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int[] sourcePixel(int orientation, int x, int y, int width, int height) {
        return switch (orientation) {
            case 2 -> new int[]{width - 1 - x, y};
            case 3 -> new int[]{width - 1 - x, height - 1 - y};
            case 4 -> new int[]{x, height - 1 - y};
            case 5 -> new int[]{y, x};
            case 6 -> new int[]{y, height - 1 - x};
            case 7 -> new int[]{width - 1 - y, height - 1 - x};
            case 8 -> new int[]{width - 1 - y, x};
            default -> new int[]{x, y};
        };
    }

    private static byte[] jpegBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void jpegMagicNumberDetected() throws IOException {
        assertThat(AvatarImageProcessor.detectType(jpegBytes(10, 10)))
                .isEqualTo(AvatarImageProcessor.ImageType.JPEG);
    }

    @Test
    void pngMagicNumberDetected() throws IOException {
        assertThat(AvatarImageProcessor.detectType(pngBytes(10, 10)))
                .isEqualTo(AvatarImageProcessor.ImageType.PNG);
    }

    @Test
    void unsupportedMagicNumberRejected() {
        assertThat(AvatarImageProcessor.detectType("GIF89a....".getBytes())).isNull();
        assertThat(AvatarImageProcessor.detectType("hello".getBytes())).isNull();
        assertThat(AvatarImageProcessor.detectType(new byte[]{1, 2})).isNull();
        assertThat(AvatarImageProcessor.detectType(null)).isNull();
    }

    @Test
    void readOrientationDefaultsToOneWithoutExif() throws IOException {
        assertThat(AvatarImageProcessor.readOrientation(jpegBytes(10, 10))).isEqualTo(1);
        assertThat(AvatarImageProcessor.readOrientation("not an image".getBytes())).isEqualTo(1);
    }

    @ParameterizedTest(name = "orientation {0}: 3x2 -> {1}x{2}")
    @CsvSource({
            "2, 3, 2",
            "3, 3, 2",
            "4, 3, 2",
            "5, 2, 3",
            "6, 2, 3",
            "7, 2, 3",
            "8, 2, 3"
    })
    void orientationTransformMapsPixels(int orientation, int outWidth, int outHeight) {
        BufferedImage source = gridImage(3, 2);
        BufferedImage result = AvatarImageProcessor.orientationTransform(source, orientation);

        assertThat(result.getWidth()).isEqualTo(outWidth);
        assertThat(result.getHeight()).isEqualTo(outHeight);
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int[] source0 = sourcePixel(orientation, x, y, 3, 2);
                assertThat(colorAt(result, x, y))
                        .as("orientation %d: dst(%d,%d) 应取自 src(%d,%d)",
                                orientation, x, y, source0[0], source0[1])
                        .isEqualTo(expectedRgb(source0[0], source0[1]));
            }
        }
    }

    @Test
    void orientationOneIsIdentity() {
        BufferedImage source = gridImage(3, 2);
        BufferedImage result = AvatarImageProcessor.orientationTransform(source, 1);

        assertThat(result.getWidth()).isEqualTo(3);
        assertThat(result.getHeight()).isEqualTo(2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertThat(colorAt(result, x, y)).isEqualTo(expectedRgb(x, y));
            }
        }
    }

    @Test
    void invalidOrientationReturnsSameImage() {
        BufferedImage source = gridImage(3, 2);
        assertThat(AvatarImageProcessor.orientationTransform(source, 9)).isSameAs(source);
    }

    @Test
    void centerCropWideImageTakesMiddleSquare() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(6, 4));

        assertThat(cropped.getWidth()).isEqualTo(4);
        assertThat(cropped.getHeight()).isEqualTo(4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x + 1, y));
            }
        }
    }

    @Test
    void centerCropTallImageTakesMiddleSquare() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(4, 6));

        assertThat(cropped.getWidth()).isEqualTo(4);
        assertThat(cropped.getHeight()).isEqualTo(4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x, y + 1));
            }
        }
    }

    @Test
    void centerCropSquareKeepsAllPixels() {
        BufferedImage cropped = AvatarImageProcessor.centerCrop(gridImage(4, 4));

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(colorAt(cropped, x, y)).isEqualTo(expectedRgb(x, y));
            }
        }
    }

    @Test
    void resizeOutputsExactTargetSize() {
        BufferedImage resized = AvatarImageProcessor.resizeTo(gridImage(6, 4), 256, 256);

        assertThat(resized.getWidth()).isEqualTo(256);
        assertThat(resized.getHeight()).isEqualTo(256);
    }

    @Test
    void processProduces256SquareJpeg() throws IOException {
        byte[] output = AvatarImageProcessor.process(jpegBytes(400, 300));

        assertThat(AvatarImageProcessor.detectType(output))
                .isEqualTo(AvatarImageProcessor.ImageType.JPEG);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
    }

    @Test
    void processAcceptsPngInput() throws IOException {
        byte[] output = AvatarImageProcessor.process(pngBytes(300, 500));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
    }

    @Test
    void processRejectsUnsupportedMagic() {
        assertThatThrownBy(() -> AvatarImageProcessor.process("GIF89a......".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持 JPEG/PNG");
    }

    @Test
    void processThrowsOnCorruptJpegPayload() {
        byte[] corrupt = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7};

        assertThatThrownBy(() -> AvatarImageProcessor.process(corrupt))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法解析图片");
    }
}
