package org.song.forum.controller;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.song.forum.dto.auth.AuthResponse;
import org.song.forum.dto.auth.LoginRequest;
import org.song.forum.dto.auth.RegisterRequest;
import org.song.forum.dto.user.UserResponse;
import org.song.forum.util.Result;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像全链路：上传 → 换图删旧 → 非法/超限被拒 → 静态直出 → 删除幂等 → 资料接口不再改头像。
 * 上传目录指向 JUnit 的 @TempDir，避免污染工作区。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:avatar-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvatarFlowIntegrationTest {

    private static final String AVATAR_PREFIX = "/uploads/avatars/";

    @TempDir
    static Path uploadDir;

    /**
     * JUnit 的 @TempDir 与 Spring 的上下文创建都挂在 beforeAll，
     * 谁先执行取决于扩展顺序；这里在属性求值时兜底建目录，保证两条路径都拿得到同一个目录。
     */
    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        // @Value 注入的是 String；直接给 Path 会因缺少 Path→String 转换器导致上下文启动失败
        registry.add("app.upload-dir", () -> resolveUploadDir().toString());
    }

    private static Path resolveUploadDir() {
        if (uploadDir == null) {
            try {
                uploadDir = Files.createTempDirectory("avatar-upload-");
            } catch (IOException e) {
                throw new IllegalStateException("无法创建临时上传目录", e);
            }
        }
        return uploadDir;
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private static String token;
    private static String avatarUrl;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String registerAndLogin(String name) {
        RegisterRequest register = new RegisterRequest();
        register.setUsername(name);
        register.setPassword("password123");
        register.setEmail(name + "@test.com");
        rest.postForEntity(url("/api/auth/register"), register, Result.class);

        LoginRequest login = new LoginRequest();
        login.setUsername(name);
        login.setPassword("password123");
        ResponseEntity<Result<AuthResponse>> resp = rest.exchange(url("/api/auth/login"),
                HttpMethod.POST, new HttpEntity<>(login), new ParameterizedTypeReference<>() {
                });
        return resp.getBody().getData().getToken();
    }

    private ResponseEntity<Result<UserResponse>> upload(String bearerToken, byte[] bytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Result<UserResponse>> deleteAvatar() {
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), new ParameterizedTypeReference<>() {
                });
    }

    private Path uploadedFile(String url) {
        return uploadDir.resolve("avatars").resolve(url.substring(AVATAR_PREFIX.length()));
    }

    private byte[] jpegBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] pngBytes(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @Order(1)
    void anonymousUploadRejected() throws IOException {
        ResponseEntity<Result<UserResponse>> resp = upload(null, jpegBytes(200, 200, Color.ORANGE), "a.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void uploadJpegStoresSquareJpeg() throws IOException {
        token = registerAndLogin("avatar-user");

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(400, 300, Color.ORANGE), "photo.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        avatarUrl = resp.getBody().getData().getAvatarUrl();
        assertThat(avatarUrl).startsWith(AVATAR_PREFIX).endsWith(".jpg");
        Path stored = uploadedFile(avatarUrl);
        assertThat(stored).exists();
        BufferedImage image = ImageIO.read(stored.toFile());
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
        byte[] bytes = Files.readAllBytes(stored);
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    @Order(3)
    void uploadPngAccepted() throws IOException {
        ResponseEntity<Result<UserResponse>> resp = upload(token, pngBytes(300, 500, Color.BLUE), "photo.png");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).startsWith(AVATAR_PREFIX);
    }

    @Test
    @Order(4)
    void replacingAvatarDeletesOldFile() throws IOException {
        String old = avatarUrl;

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(500, 500, Color.GREEN), "again.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        avatarUrl = resp.getBody().getData().getAvatarUrl();
        assertThat(avatarUrl).isNotEqualTo(old);
        assertThat(uploadedFile(old)).doesNotExist();
        assertThat(uploadedFile(avatarUrl)).exists();
    }

    @Test
    @Order(5)
    void textFileRenamedJpgRejected() {
        ResponseEntity<Result<UserResponse>> resp = upload(token, "not an image".getBytes(), "fake.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("仅支持 JPEG/PNG");
    }

    @Test
    @Order(6)
    void oversizeUploadRejected() throws IOException {
        byte[] jpeg = jpegBytes(300, 300, Color.RED);
        byte[] oversize = new byte[2 * 1024 * 1024 + 1024];
        System.arraycopy(jpeg, 0, oversize, 0, jpeg.length);

        ResponseEntity<Result<UserResponse>> resp = upload(token, oversize, "big.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("2MB");
    }

    @Test
    @Order(7)
    void uploadedFileServedByWebConfig() {
        ResponseEntity<byte[]> resp = rest.getForEntity(url(avatarUrl), byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString()).contains("image/jpeg");
    }

    @Test
    @Order(8)
    void deleteRemovesAvatarAndFile() {
        String target = avatarUrl;

        ResponseEntity<Result<UserResponse>> resp = deleteAvatar();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
        assertThat(uploadedFile(target)).doesNotExist();
    }

    @Test
    @Order(9)
    void deleteIsIdempotent() {
        ResponseEntity<Result<UserResponse>> resp = deleteAvatar();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
    }

    @Test
    @Order(10)
    void profileUpdateNoLongerChangesAvatar() throws IOException {
        ResponseEntity<Result<UserResponse>> uploaded = upload(token, jpegBytes(200, 200, Color.MAGENTA), "keep.jpg");
        String current = uploaded.getBody().getData().getAvatarUrl();
        assertThat(current).isNotNull();

        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"nickname\":\"新昵称\",\"avatarUrl\":\"/uploads/avatars/evil.jpg\",\"bio\":\"hi\"}";
        ResponseEntity<Result<UserResponse>> resp = rest.exchange(url("/api/auth/profile"), HttpMethod.PUT,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getNickname()).isEqualTo("新昵称");
        assertThat(resp.getBody().getData().getAvatarUrl()).isEqualTo(current);
    }
}
