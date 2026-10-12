# workpro

Java/Spring Boot + React/Vite 的文档问答与投标工作流原型。后端支持文档解析与 OCR、PostgreSQL/pgvector 持久化、Elasticsearch BM25 与向量混合检索、交叉编码器重排、RAG 回答，以及投标分析与文档生成流程。

## 目录

- `backend/`：Spring Boot API、RAG 检索与生成、数据库迁移和单元测试。
- `frontend/`：React 工作台。
- `ocr-service/`：FastAPI OCR 服务。
- `rag工作记录与脚本/脚本/`：检索和生成评估脚本；评测原始资料与运行结果不在仓库中。
- `.agents/skills/workpro-tender-agent/`：项目投标工作流技能与参考说明。

## 本地运行

需要 Java 17+、Maven、Node.js、PostgreSQL（含 pgvector）和 Redis。BM25 检索需要 Elasticsearch；PDF 的表格/扫描页处理需要单独启动 OCR 服务。按需配置 `backend/src/main/resources/application.yml` 中列出的环境变量，尤其是数据库连接、模型端点与 API Key。不要把密钥写入仓库。

```powershell
cd backend
mvn spring-boot:run
```

另开终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

Reranker 的容器配置及启动说明见 `backend/RERANKER.md`。OCR 服务入口为 `ocr-service/app/main.py`，运行前需安装 FastAPI、Uvicorn、python-multipart、PaddleOCR 和 openpyxl 等依赖，并根据本机环境配置模型与服务端口。

## 主要 API

- `POST /api/chat`：聚合对话入口。
- `POST /api/rag/ingest`、`POST /api/rag/ingest/async`：同步/异步文档入库。
- `GET /api/rag/search`：混合检索。
- `POST /api/rag/answer`：检索并生成带证据引用的答案。

此仓库只包含代码和技术文档。招标原件、评测语料、生成文档、日志、缓存及本机环境均未上传。
