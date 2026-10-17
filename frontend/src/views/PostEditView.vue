<script setup>
import { reactive, ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi, boardApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

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
      <el-form-item label="正文" prop="content">
        <el-input v-model="form.content" type="textarea" :rows="12" placeholder="正文内容…" />
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
</style>
