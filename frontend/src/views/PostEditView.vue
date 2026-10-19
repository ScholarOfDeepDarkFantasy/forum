<script setup>
import { reactive, ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi, boardApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useResponsive } from '@/composables/useResponsive'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isEdit = computed(() => !!route.params.id)
const boards = ref([])
const loading = ref(false)
const formRef = ref()

const form = reactive({
  boardId: null,
  title: '',
  content: '',
  tags: []
})

const { isMobile } = useResponsive()
// 窄屏两个 tab：edit / preview；桌面端不分 tab
const mobileTab = ref('edit')
const previewHtml = computed(() => renderMarkdown(form.content))

const rules = {
  boardId: [{ required: true, message: '请选择板块', trigger: 'change' }],
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 200, message: '标题最长 200 字', trigger: 'blur' }
  ],
  content: [{ required: true, message: '请输入正文', trigger: 'blur' }]
}

onMounted(async () => {
  boards.value = await boardApi.list()
  if (isEdit.value) {
    const p = await postApi.detail(route.params.id)
    if (p.authorId !== auth.user?.id) {
      ElMessage.error('只能编辑自己的帖子')
      router.back()
      return
    }
    form.boardId = p.boardId
    form.title = p.title
    form.content = p.content
    form.tags = p.tags || []
  }
})

async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const payload = {
      boardId: form.boardId,
      title: form.title,
      content: form.content,
      tags: form.tags.filter(Boolean)
    }
    if (isEdit.value) {
      await postApi.update(route.params.id, payload)
      ElMessage.success('帖子已更新')
      router.push(`/posts/${route.params.id}`)
    } else {
      const created = await postApi.create(payload)
      ElMessage.success('发布成功')
      router.push(`/posts/${created.id}`)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-card shadow="never" class="editor-card">
    <h2 class="title">{{ isEdit ? '编辑帖子' : '发布帖子' }}</h2>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="板块" prop="boardId">
        <el-select v-model="form.boardId" placeholder="选择板块" style="width: 280px">
          <el-option v-for="b in boards" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="一句话说清问题/主题" />
      </el-form-item>
      <el-form-item label="正文（支持 Markdown）" prop="content">
        <div v-if="!isMobile" class="editor-grid">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="18"
            placeholder="正文内容…支持 Markdown：标题、列表、代码块、表格、链接、图片"
          />
          <div class="preview markdown-body" v-html="previewHtml"></div>
        </div>
        <template v-else>
          <el-tabs v-model="mobileTab" class="tabs">
            <el-tab-pane label="编辑" name="edit">
              <el-input v-model="form.content" type="textarea" :rows="14" placeholder="正文内容…支持 Markdown" />
            </el-tab-pane>
            <el-tab-pane label="预览" name="preview">
              <div class="preview markdown-body" v-html="previewHtml"></div>
            </el-tab-pane>
          </el-tabs>
        </template>
        <div class="md-hint">支持 GFM：标题、列表、代码块、表格、链接、图片（仅外链）</div>
      </el-form-item>
      <el-form-item label="标签（最多 5 个，回车添加）">
        <el-select
          v-model="form.tags"
          multiple
          filterable
          allow-create
          default-first-option
          :multiple-limit="5"
          placeholder="如 spring-boot、java"
          style="width: 100%"
        />
      </el-form-item>
      <div class="actions">
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submit">
          {{ isEdit ? '保存修改' : '发布' }}
        </el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.editor-card {
  max-width: 860px;
  margin: var(--jf-space-4) auto;
}
.title {
  margin: 0 0 var(--jf-space-4);
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--jf-space-3);
  width: 100%;
}
.editor-grid .preview,
.tabs .preview {
  min-height: 200px;
  padding: var(--jf-space-3);
  border: 1px solid var(--jf-color-border);
  border-radius: var(--jf-radius-sm);
  overflow: auto;
}
.md-hint {
  margin-top: 6px;
  color: var(--jf-color-text-3);
  font-size: var(--jf-font-meta);
}

/* 窄屏单列不在这里写 @media：模板已用 v-if="!isMobile" 在同一个 768px 断点把
   .editor-grid 整个换成 el-tabs，此处的媒体查询永远不可达；而新增断点字面量会撞上
   src/styles/breakpoint.spec.js 的精确计数闸门（该闸门刻意要求同步 tokens.css/README）。 */
</style>
