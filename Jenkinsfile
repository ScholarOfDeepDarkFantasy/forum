// JavaForum CI/CD：在 raspi 上构建后端镜像与前端产物，部署到本机 podman + nginx
//
// 一次性前置（见 docs/deployment/README.md）：
//   1. Jenkins job：Pipeline script from SCM（本仓库，分支 master，Script Path=Jenkinsfile），
//      Custom Workspace 建议设为 /pd/jenkins-workspace/forum
//   2. /pd/Program/forum/deploy/.env 已初始化（DB_PASSWORD / JWT_SECRET）
//   3. /etc/nginx/conf.d/app.conf 已 include /etc/nginx/snippets/forum.conf
//
// 容器操作一律以 pi 身份执行（sudo -u pi；sudoers 已放行），
// 复用 pi 的 rootless podman 存储与已有镜像。不要改成 docker，也不要用 root podman。

pipeline {
    // 工作区放 /pd：根盘只剩约 5G，不能把构建产物写进 /var/lib/jenkins
    agent { node { label 'built-in'; customWorkspace '/pd/jenkins-workspace/forum' } }

    options {
        skipDefaultCheckout()
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15'))
    }

    parameters {
        booleanParam(name: 'RUN_TESTS', defaultValue: false, description: '部署前跑后端与前端测试')
        booleanParam(name: 'DEPLOY', defaultValue: true, description: '构建成功后部署到本机')
        string(name: 'IMAGE_TAG', defaultValue: '', description: '镜像 tag；留空使用 BUILD_NUMBER')
    }

    // 主机没有公网入口，GitHub webhook 进不来：每 5 分钟轮询 master
    triggers { pollSCM('H/5 * * * *') }

    tools {
        jdk 'Java17'
        maven 'M3'
    }

    environment {
        DEPLOY_SCRIPT = 'deploy/deploy.sh'
        PNPM = '/home/pi/.nvm/versions/node/v24.16.0/bin/pnpm'
        NPM_REGISTRY = 'https://registry.npmmirror.com'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.TAG = (params.IMAGE_TAG ?: '').trim() ?: env.BUILD_NUMBER
                }
                sh 'echo "commit=$(git rev-parse --short HEAD) image_tag=${TAG}"'
            }
        }

        stage('Tests') {
            when { expression { params.RUN_TESTS } }
            steps {
                sh 'mvn -B test'
                dir('frontend') {
                    sh "${PNPM} test"
                }
            }
        }

        stage('Build Backend') {
            steps {
                sh 'mvn -B -DskipTests clean package'
                archiveArtifacts artifacts: 'target/forum-0.0.1-SNAPSHOT.jar', fingerprint: true
            }
        }

        stage('Build Frontend') {
            steps {
                dir('frontend') {
                    // pnpm store 放 /pd，避免占满只剩 5G 的根盘
                    sh "${PNPM} install --frozen-lockfile --registry=${NPM_REGISTRY} --store-dir /pd/pnpm-store"
                    // 部署在子路径 /forum/：资源、路由、axios baseURL 三处联动（见 README「部署到子路径」）
                    sh "VITE_BASE_PATH=/forum/ ${PNPM} build"
                }
            }
        }

        stage('Build Image') {
            steps {
                // 用干净的临时上下文，避免把 node_modules / .git 等送进 buildah
                sh '''
                    set -eu
                    rm -rf /tmp/forum-image
                    mkdir -p /tmp/forum-image/target
                    cp Dockerfile /tmp/forum-image/
                    cp target/forum-0.0.1-SNAPSHOT.jar /tmp/forum-image/target/
                    sudo -u pi podman build --format docker -t "localhost/forum-backend:${TAG}" /tmp/forum-image
                '''
            }
        }

        stage('Deploy') {
            when { expression { params.DEPLOY } }
            steps {
                // 目录、compose、健康检查、nginx reload、冒烟都封装在部署脚本里（以 pi 身份运行）
                sh "sudo -u pi /usr/bin/bash -c 'cd ${WORKSPACE} && /usr/bin/bash ${DEPLOY_SCRIPT} ${TAG}'"
            }
        }
    }

    post {
        success {
            echo "部署完成：http://raspi/forum/ （镜像 localhost/forum-backend:${TAG}）"
        }
        failure {
            echo '构建或部署失败：检查对应 stage 输出；部署机排障见 docs/deployment/README.md'
        }
    }
}
