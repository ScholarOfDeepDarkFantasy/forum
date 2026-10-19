# JavaForum 部署手册（raspi：Jenkins + podman + nginx）

> 目标主机：SSH 别名 `raspi`（Debian 12 / aarch64，Jenkins 2.580.1，rootless podman 4.3.1）
> 设计背景与取舍见 [raspi-jenkins-podman-analysis.md](raspi-jenkins-podman-analysis.md)。

## 1. 部署形态

```
浏览器 ── http://raspi/forum/
  nginx :80（系统服务）
    ├─ /forum/       → /pd/Program/forum/frontend/      Vue dist（nginx 直出，不进容器）
    └─ /forum/api/   → 127.0.0.1:8086/api/              反向代理
                       forum-backend 容器（eclipse-temurin:17-jre-jammy）
                         │ jdbc:postgresql://host.containers.internal:5432/javaforum
                       主机已有 postgresql 容器（rootless，库 javaforum 数据保留）
Jenkins :8080 → job `javaforum`（Jenkinsfile from SCM，master，轮询 H/5）
```

要点：后端只绑 `127.0.0.1:8086`；数据库不新增容器；镜像/工作区/前端都在 `/pd`（根盘只剩 5G）。

## 2. 一次性引导（换机器时照做；当前主机已完成）

```bash
# 1) 目录（pi 属主；jenkins 在 pi 组，pipeline 用 sudo -u pi 写入）
sudo mkdir -p /pd/Program/forum/deploy /pd/Program/forum/frontend
sudo chown -R pi:pi /pd/Program/forum
sudo chmod 2775 /pd/Program/forum

# 2) 生成 deploy/.env，并重置已有 forum 角色密码（只做一次，值是随机的）
cd /pd/Program/forum/deploy
DB_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 32)"
cat > .env <<EOF
DB_URL=jdbc:postgresql://host.containers.internal:5432/javaforum
DB_USERNAME=forum
DB_PASSWORD='$DB_PASSWORD'
JWT_SECRET='$JWT_SECRET'
HOST_PORT=8086
TZ=Asia/Shanghai
IMAGE_TAG=latest
JAVA_OPTS='-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError'   # 不要用 MaxRAMPercentage，见第 7 节
EOF
chmod 600 .env
sudo -u pi podman exec postgresql psql -U postgres -c "ALTER ROLE forum WITH PASSWORD '$DB_PASSWORD';"

# 3) nginx：snippet + 在 app.conf 的 server 块内加 include（只加一次）
sudo cp /pd/Program/forum/deploy/nginx/forum.conf /etc/nginx/snippets/forum.conf 2>/dev/null || true
sudo sed -i '/server_name raspi;/a\    include /etc/nginx/snippets/forum.conf;' /etc/nginx/conf.d/app.conf
sudo nginx -t && sudo systemctl reload nginx
```

> 第 3 步的 snippet 内容来自仓库 `deploy/nginx/forum.conf`，部署时流水线会自动 `cp` 覆盖它；
> 如果 `app.conf` 里已经有 `include /etc/nginx/snippets/forum.conf;`，不要重复执行 `sed`。

```bash
# 4) Jenkins job（主机无公网入口，没有 Jenkins 管理员密码时用文件方式创建；有密码也可在 UI 里建）
sudo mkdir -p /var/lib/jenkins/jobs/javaforum
# config.xml 见附录 A，写入后：
sudo chown -R jenkins:jenkins /var/lib/jenkins/jobs/javaforum
sudo systemctl restart jenkins
```

Jenkins 重启后 30–60s 可用；job 配置里已带 `SCMTrigger H/5`，**首次轮询会直接触发第一次构建**。

## 3. 日常发布

1. 本地改代码，`git push` 到 `master`；
2. Jenkins 5 分钟内轮询到变更 → 构建 jar → 构建前端 → 构建镜像 → 部署 → 冒烟；
3. 看结果：

```bash
# 构建历史
sudo ls /var/lib/jenkins/jobs/javaforum/builds/
# 跟踪最新一次构建
sudo tail -f /var/lib/jenkins/jobs/javaforum/builds/*/log
# 容器状态与日志
sudo -u pi podman ps --filter name=forum-backend
sudo -u pi podman logs --tail 100 forum-backend
```

也可在 Jenkins UI 里 `Build with Parameters`：`RUN_TESTS`（跑测试）、`DEPLOY`（是否部署）、`IMAGE_TAG`（默认 BUILD_NUMBER）。

## 4. 回滚

- 方式一（推荐，可追溯）：Jenkins `Build with Parameters`，`IMAGE_TAG=<上一个 BUILD_NUMBER>`，`DEPLOY=true`。流水线会重新构建同版本 jar/前端并用该 tag 部署（历史镜像仍保留时 build 很快）。
- 方式二（只回滚容器）：`cd /pd/Program/forum/deploy && sudo -u pi sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<旧tag>/' .env && sudo -u pi podman-compose -p forum up -d`。
- 数据库无版本回滚概念（Flyway 只前进）；回滚代码前确认迁移兼容。

## 5. 冒烟检查（部署后）

```bash
curl -fsS http://127.0.0.1:8086/api/health                                   # {"success":true,...}
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/ | grep '/forum/assets/'    # 前端资源前缀
curl -fsS -H 'Host: raspi' http://127.0.0.1/forum/posts/1 | grep 'id="app"'   # SPA 回退
curl -fsS -H 'Host: raspi' 'http://127.0.0.1/forum/api/posts?page=0&size=1'   # 经 nginx 的 API
```

> 必须带 `Host: raspi`：`app.conf` 是 `server_name raspi`，用 IP 访问会落到 `sites-enabled/default`。

## 6. 排障

| 现象 | 排查 |
|---|---|
| Jenkins 构建红在 Checkout | 确认 `git ls-remote https://gh-proxy.org/https://github.com/ScholarOfDeepDarkFantasy/forum.git` 可用（jenkins 用户） |
| `pom.xml` 依赖下载慢/失败 | 复用 `/var/lib/jenkins/.m2`；Maven Central 实测 0.57s，一般无需镜像 |
| 前端 install 失败 | 强制 `--registry=https://registry.npmmirror.com`（Jenkinsfile 已带）；pnpm 绝对路径 `/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm` |
| Build Image 失败 | 用 `sudo -u pi podman build --format docker ...`（`--format docker` 才会保留 HEALTHCHECK）；上下文必须含 `target/forum-0.0.1-SNAPSHOT.jar` |
| 部署阶段 `缺少 .env` | 执行第 2 步引导；`chmod 600` 且属主 pi |
| `podman-compose: command not found` | 脚本在 pi 环境执行；jenkins 的 sudoers 已放行 `/usr/bin/podman-compose` |
| 后端启动即退出 | `sudo -u pi podman logs forum-backend`：常见是 DB 连不上（`host.containers.internal` 解析/密码） |
| 容器 `Created` 但起不来，日志有 `crun: opening file 'memory.max' ...` | compose 里写了 `mem_limit`，而本机 `/proc/cmdline` 带 `cgroup_disable=memory`（没有内存 cgroup 控制器）；删掉 `mem_limit`，用 `JAVA_OPTS` 的 `-Xmx` 限制堆 |
| 数据库密码错 | `sudo -u pi podman exec postgresql psql -U postgres -c "ALTER ROLE forum WITH PASSWORD '<.env 里的值>';"` |
| `/forum/` 404 | `app.conf` 是否 include snippet；`/pd/Program/forum/frontend/index.html` 是否存在 |
| `/forum/api/` 502 | `forum-backend` 未起或未监听 8086：`sudo -u pi curl -v http://127.0.0.1:8086/api/health` |
| Jenkins 起不来 | `sudo journalctl -u jenkins -n 100 --no-pager`；job config.xml 写坏时先移走 `/var/lib/jenkins/jobs/javaforum` 再重启 |

## 7. 已知限制

- **没有公网入口**：不用 GitHub webhook，靠 `pollSCM`（最长延迟约 5 分钟）；想即时构建需手动 Build。
- **重启后容器不会自动拉起**：pi（和 jenkins）没有开启 linger，rootless 容器依赖登录/手动启动（与主机上现有 mini-mall 等服务同一现状）。重启后执行：`sudo -u pi podman-compose -p forum -f /pd/Program/forum/deploy/docker-compose.yml up -d`。
- **`/` 根盘只剩约 5G**：Jenkins 工作区固定在 `/pd/jenkins-workspace/forum`，pnpm store 在 `/pd/pnpm-store`，镜像在 `/pd/container`。
- **不能用 `mem_limit` 限制容器内存**：内核 cmdline 带 `cgroup_disable=memory`，rootless crun 写 `memory.max` 会失败；堆大小由 `.env` 里的 `JAVA_OPTS=-Xmx512m` 控制。
- **不要执行全局 `podman image prune`**：会把 mini-mall 等其它项目的镜像一起删掉；流水线只清理 `localhost/forum-backend` 的旧数字 tag。

## 附录 A：Jenkins job `config.xml`

```xml
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>JavaForum 自动构建部署（raspi：podman + nginx）</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <jenkins.model.BuildDiscarderProperty>
      <strategy class="hudson.tasks.LogRotator">
        <daysToKeep>-1</daysToKeep>
        <numToKeep>15</numToKeep>
        <artifactDaysToKeep>-1</artifactDaysToKeep>
        <artifactNumToKeep>-1</artifactNumToKeep>
      </strategy>
    </jenkins.model.BuildDiscarderProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>https://gh-proxy.org/https://github.com/ScholarOfDeepDarkFantasy/forum.git</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/master</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions/>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers>
    <hudson.triggers.SCMTrigger>
      <spec>H/5 * * * *</spec>
      <ignorePostCommitHooks>false</ignorePostCommitHooks>
    </hudson.triggers.SCMTrigger>
  </triggers>
  <disabled>false</disabled>
</flow-definition>
```

> 工作区不在 job 里配，而由 `Jenkinsfile` 的 `agent { node { customWorkspace '/pd/jenkins-workspace/forum' } }` 指定。
> 无 Jenkins 密码时也可以登录后 «新建 Item → Pipeline → Pipeline script from SCM» 手工建，参数同上。
