package org.song.forum.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import org.song.forum.entity.User;
import org.song.forum.entity.UserStatus;
import org.song.forum.repository.UserRepository;
import org.song.forum.util.Result;
import org.song.forum.util.TestImageFixtures;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像全链路：上传 → 换图删旧 → 非法/超限被拒 → 静态直出 → 删除幂等 → 多人共享旧 URL。
 * 每个用例自注册唯一用户，可单独运行；上传目录只创建一次，结束后清理。
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
class AvatarFlowIntegrationTest {

    private static final String AVATAR_PREFIX = "/uploads/avatars/";

    /** 类加载时创建一次，DynamicPropertySource 与断言共用同一个目录，避免兜底逻辑再建第二个目录。 */
    private static final Path UPLOAD_DIR = createUploadDir();

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        // @Value 注入的是 String；直接给 Path 会因缺少 Path→String 转换器导致上下文启动失败
        registry.add("app.upload-dir", () -> resolveUploadDir().toString());
    }

    private static Path createUploadDir() {
        try {
            return Files.createTempDirectory("avatar-upload-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时上传目录", e);
        }
    }

    private static Path resolveUploadDir() {
        return UPLOAD_DIR;
    }

    @AfterAll
    static void cleanUploadDir() throws IOException {
        try (Stream<Path> paths = Files.walk(UPLOAD_DIR)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository userRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private static String newUsername() {
        return "avatar" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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

    private String freshToken() {
        return registerAndLogin(newUsername());
    }

    private ResponseEntity<Result<UserResponse>> upload(String bearerToken, byte[] bytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return postMultipart(bearerToken, body);
    }

    private ResponseEntity<Result<UserResponse>> postMultipart(String bearerToken,
                                                              MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Result<UserResponse>> deleteAvatar(String bearerToken) {
        return rest.exchange(url("/api/users/me/avatar"), HttpMethod.DELETE,
                new HttpEntity<>(bearer(bearerToken)), new ParameterizedTypeReference<>() {
                });
    }

    private Path uploadedFile(String avatarUrl) {
        return UPLOAD_DIR.resolve("avatars").resolve(avatarUrl.substring(AVATAR_PREFIX.length()));
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
    void anonymousUploadRejected() throws IOException {
        ResponseEntity<Result<UserResponse>> resp = upload(null, jpegBytes(200, 200, Color.ORANGE), "a.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void uploadJpegStoresSquareJpeg() throws IOException {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(400, 300, Color.ORANGE), "photo.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String avatarUrl = resp.getBody().getData().getAvatarUrl();
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
    void uploadPngAccepted() throws IOException {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = upload(token, pngBytes(300, 500, Color.BLUE), "photo.png");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).startsWith(AVATAR_PREFIX);
    }

    @Test
    void replacingAvatarDeletesOldFile() throws IOException {
        String token = freshToken();
        String old = upload(token, jpegBytes(400, 300, Color.ORANGE), "first.jpg").getBody().getData().getAvatarUrl();

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpegBytes(500, 500, Color.GREEN), "again.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String current = resp.getBody().getData().getAvatarUrl();
        assertThat(current).isNotEqualTo(old);
        assertThat(uploadedFile(old)).doesNotExist();
        assertThat(uploadedFile(current)).exists();
    }

    @Test
    void textFileRenamedJpgRejected() {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = upload(token, "not an image".getBytes(), "fake.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("仅支持 JPEG/PNG");
    }

    @Test
    void webpAndGifRejected() {
        String token = freshToken();
        byte[] webp = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

        ResponseEntity<Result<UserResponse>> webpResp = upload(token, webp, "photo.webp");
        ResponseEntity<Result<UserResponse>> gifResp = upload(token, "GIF89a......".getBytes(), "photo.gif");

        assertThat(webpResp.getStatusCode().value()).isEqualTo(400);
        assertThat(webpResp.getBody().getMessage()).contains("仅支持 JPEG/PNG");
        assertThat(gifResp.getStatusCode().value()).isEqualTo(400);
        assertThat(gifResp.getBody().getMessage()).contains("仅支持 JPEG/PNG");
    }

    @Test
    void oversizeUploadRejected() throws IOException {
        String token = freshToken();
        byte[] jpeg = jpegBytes(300, 300, Color.RED);
        byte[] oversize = new byte[2 * 1024 * 1024 + 1024];
        System.arraycopy(jpeg, 0, oversize, 0, jpeg.length);

        ResponseEntity<Result<UserResponse>> resp = upload(token, oversize, "big.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("2MB");
    }

    @Test
    void twoPointOneMegabyteUploadRejected() throws IOException {
        String token = freshToken();
        byte[] jpeg = jpegBytes(300, 300, Color.RED);
        byte[] oversize = new byte[(int) (2.1 * 1024 * 1024)];
        System.arraycopy(jpeg, 0, oversize, 0, jpeg.length);

        ResponseEntity<Result<UserResponse>> resp = upload(token, oversize, "big.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("2MB");
    }

    @Test
    void decompressionBombRejectedWithoutOom() {
        String token = freshToken();
        byte[] bomb = TestImageFixtures.pngDeclaringSize(20000, 20000);

        ResponseEntity<Result<UserResponse>> resp = upload(token, bomb, "bomb.png");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("尺寸");
    }

    @Test
    void transparentPngBecomesWhiteJpeg() throws IOException {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = upload(token,
                TestImageFixtures.transparentPngWithRedQuadrant(), "alpha.png");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        BufferedImage image = ImageIO.read(uploadedFile(resp.getBody().getData().getAvatarUrl()).toFile());
        int corner = image.getRGB(250, 250);
        assertThat((corner >> 16) & 0xFF).as("透明区域应为白色").isGreaterThan(240);
        assertThat((corner >> 8) & 0xFF).isGreaterThan(240);
        assertThat(corner & 0xFF).isGreaterThan(240);
        int red = image.getRGB(64, 64);
        assertThat((red >> 16) & 0xFF).as("红色方块应保留").isGreaterThan(200);
        assertThat(red & 0xFF).isLessThan(80);
    }

    @Test
    void exifOrientedJpegIsRotated() throws IOException {
        String token = freshToken();
        BufferedImage source = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 60, 60);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(60, 0, 60, 60);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "jpg", out);
        byte[] jpeg = TestImageFixtures.jpegWithExifOrientation(6, out.toByteArray());

        ResponseEntity<Result<UserResponse>> resp = upload(token, jpeg, "exif.jpg");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        BufferedImage image = ImageIO.read(uploadedFile(resp.getBody().getData().getAvatarUrl()).toFile());
        int top = image.getRGB(128, 40);
        assertThat((top >> 16) & 0xFF).as("顺时针旋转后上半应为红色").isGreaterThan(180);
        assertThat(top & 0xFF).isLessThan(90);
        int bottom = image.getRGB(128, 216);
        assertThat(bottom & 0xFF).as("顺时针旋转后下半应为蓝色").isGreaterThan(180);
        assertThat((bottom >> 16) & 0xFF).isLessThan(90);
    }

    @Test
    void missingFilePartReturns400() {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = postMultipart(token, new LinkedMultiValueMap<>());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("缺少图片文件");
    }

    @Test
    void wrongPartNameReturns400() {
        String token = freshToken();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });

        ResponseEntity<Result<UserResponse>> resp = postMultipart(token, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("缺少图片文件");
    }

    @Test
    void nonMultipartPostReturns400() {
        String token = freshToken();
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Result<UserResponse>> resp = rest.exchange(url("/api/users/me/avatar"), HttpMethod.POST,
                new HttpEntity<>("{}", headers), new ParameterizedTypeReference<>() {
                });

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).contains("multipart/form-data");
    }

    @Test
    void uploadedFileServedByWebConfig() throws IOException {
        String token = freshToken();
        String avatarUrl = upload(token, jpegBytes(300, 300, Color.ORANGE), "served.jpg")
                .getBody().getData().getAvatarUrl();

        ResponseEntity<byte[]> resp = rest.getForEntity(url(avatarUrl), byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString()).contains("image/jpeg");
    }

    @Test
    void deleteRemovesAvatarAndFile() throws IOException {
        String token = freshToken();
        String avatarUrl = upload(token, jpegBytes(300, 300, Color.ORANGE), "delete.jpg")
                .getBody().getData().getAvatarUrl();

        ResponseEntity<Result<UserResponse>> resp = deleteAvatar(token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
        assertThat(uploadedFile(avatarUrl)).doesNotExist();
    }

    @Test
    void deleteIsIdempotent() {
        String token = freshToken();

        ResponseEntity<Result<UserResponse>> resp = deleteAvatar(token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData().getAvatarUrl()).isNull();
    }

    @Test
    void profileUpdateNoLongerChangesAvatar() throws IOException {
        String token = freshToken();
        String current = upload(token, jpegBytes(200, 200, Color.MAGENTA), "keep.jpg")
                .getBody().getData().getAvatarUrl();
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

    @Test
    void sharedLegacyAvatarUrlIsKeptWhileAnotherUserReferencesIt() throws IOException {
        String ownerName = newUsername();
        String ownerToken = registerAndLogin(ownerName);
        String sharedUrl = upload(ownerToken, jpegBytes(300, 300, Color.CYAN), "shared.jpg")
                .getBody().getData().getAvatarUrl();
        Path sharedFile = uploadedFile(sharedUrl);
        assertThat(sharedFile).exists();

        String otherName = newUsername();
        User other = User.builder()
                .username(otherName)
                .password("x")
                .email(otherName + "@test.com")
                .nickname("另一个用户")
                .status(UserStatus.ACTIVE)
                .avatarUrl(sharedUrl)
                .build();
        User saved = userRepository.save(other);

        ResponseEntity<Result<UserResponse>> resp = deleteAvatar(ownerToken);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(sharedFile).as("另一个用户仍引用该文件，不能删除").exists();
        assertThat(rest.getForEntity(url(sharedUrl), byte[].class).getStatusCode().value()).isEqualTo(200);
        assertThat(userRepository.findById(saved.getId()).orElseThrow().getAvatarUrl()).isEqualTo(sharedUrl);
    }
}
