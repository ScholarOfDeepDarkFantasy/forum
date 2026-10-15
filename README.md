# JavaForum

基于 Spring Boot 3 + Vue3 的论坛网站 MVP。

## 技术栈

- 后端：Java 17 / Maven / Spring Boot 3.5 / Spring Security 6（JWT 无状态认证）/ Spring Data JPA / PostgreSQL / Flyway / springdoc-openapi
- 前端：Vue3 + Vite + Pinia + Vue Router + Axios + Element Plus（`frontend/` 子目录，后续阶段创建）
- 测试：JUnit 5 + Spring Boot Test + H2

## 环境要求

- JDK 17+
- Maven 3.8+
- PostgreSQL 14+
- Node.js 18+（前端阶段）

## 数据库准备

```sql
CREATE DATABASE javaforum WITH ENCODING 'UTF8';
```

## 运行（后端）

```bash
# 方式一：修改 application.yml 中的数据库密码后直接运行
mvn spring-boot:run

# 方式二：通过环境变量覆盖
# Windows (PowerShell)
$env:DB_PASSWORD="你的密码"; mvn spring-boot:run
```

启动后：

- 健康检查：http://localhost:8080/api/health
- Swagger 文档：http://localhost:8080/swagger-ui.html

## 运行（前端，第 7 阶段后）

```bash
cd frontend
npm install
npm run dev
```

## 测试

```bash
mvn test
```

测试使用 H2 内存库（PostgreSQL 兼容模式），无需本地 PostgreSQL。

## 目录结构

```
src/main/java/org/song/forum/
├── config/        # Security、OpenAPI、JPA 审计等配置
├── controller/    # REST 控制器（无业务逻辑）
├── service/       # 业务逻辑与事务
├── repository/    # Spring Data JPA 数据访问
├── entity/        # JPA 实体
├── dto/           # 请求/响应 DTO
├── security/      # JWT 过滤器、UserDetails
├── exception/     # 全局异常处理
└── util/          # Result<T> 等工具
```
