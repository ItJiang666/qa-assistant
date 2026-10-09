-- =============================================================================
-- qa-assistant · H2 建表脚本（默认数据源）
-- =============================================================================
-- 与 sql/schema-mysql.sql 表结构完全一致，只是方言不同。
--
-- ⚠️ 三处必须与 MySQL 版区别对待（开发文档 4.1）：
--   1. 不能写 ENGINE=InnoDB / DEFAULT CHARSET —— H2 不认
--   2. 索引必须用独立的 CREATE INDEX 语句 —— H2 不支持在 CREATE TABLE 里内联 INDEX
--   3. 不能写 ON UPDATE CURRENT_TIMESTAMP —— H2 不支持。
--      因此 updated_at 一律由业务代码显式赋值（MyBatis 里写 updated_at = CURRENT_TIMESTAMP），
--      这样两份脚本的行为才一致。
--
-- ⚠️ AUTO_INCREMENT 依赖 JDBC URL 中的 MODE=MySQL（见 application-h2.yml）。
--
-- 脚本必须幂等：spring.sql.init.mode=always 会在每次启动时执行它。
-- =============================================================================

-- ── 知识库文档元数据 ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qa_document (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  file_name       VARCHAR(255) NOT NULL,              -- 原始文件名
  file_type       VARCHAR(32)  NOT NULL,              -- 扩展名：pdf/docx/txt/md/xlsx/html
  file_size       BIGINT       NOT NULL,              -- 字节
  file_path       VARCHAR(512) NOT NULL,              -- 落盘路径
  chunk_count     INT          NOT NULL DEFAULT 0,    -- 分块数
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/PARSING/INDEXED/FAILED
  error_message   VARCHAR(1000),                      -- 失败原因，留痕给用户看
  embedding_model VARCHAR(64)  NOT NULL,              -- 入库用的向量模型，换模型需重建
  collection_name VARCHAR(128) NOT NULL,              -- 所属向量集合
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_document_status     ON qa_document(status);
CREATE INDEX IF NOT EXISTS idx_qa_document_collection ON qa_document(collection_name);

-- ── 问答会话（L2 会话记忆的归属容器）─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qa_conversation (
  id         VARCHAR(64) PRIMARY KEY,                 -- 前端生成的 UUID
  user_id    VARCHAR(64) NOT NULL,                    -- 匿名用户标识，来自请求头 X-User-Id
  title      VARCHAR(255),                            -- 首问摘要
  run_mode   VARCHAR(16) NOT NULL,                    -- LOCAL / CLOUD
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_conversation_user ON qa_conversation(user_id, updated_at);

-- ── 问答消息（同时充当 L2 会话记忆的持久化存储）──────────────────────────────
CREATE TABLE IF NOT EXISTS qa_message (
  id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  role            VARCHAR(16) NOT NULL,               -- user / assistant / system / tool
  content         CLOB        NOT NULL,               -- 长文本
  sources_json    CLOB,                               -- 引用的来源片段（JSON）
  token_usage     INT,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_message_conv ON qa_message(conversation_id, id);

-- ── 长期记忆（L3）：跨会话的「关于这个人的偏好与事实」────────────────────────
CREATE TABLE IF NOT EXISTS qa_memory (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  user_id        VARCHAR(64)  NOT NULL,               -- 归属人（匿名 UUID）
  memory_type    VARCHAR(16)  NOT NULL,               -- PREFERENCE / FACT / CONCLUSION
  mem_key        VARCHAR(128) NOT NULL,               -- 归一化主题键，用于去重合并
  content        VARCHAR(500) NOT NULL,               -- 一句话，禁止塞原文
  confidence     DECIMAL(3,2) NOT NULL DEFAULT 0.80,  -- 置信度 0.00~1.00
  importance     TINYINT      NOT NULL DEFAULT 3,     -- 重要度 1~5，注入排序用
  source_conv_id VARCHAR(64),                         -- 来源会话，便于追溯与回滚
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DELETED（软删）
  hit_count      INT          NOT NULL DEFAULT 0,     -- 被注入次数，用于淘汰
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 同一用户的同一主题只允许一条：抽取到新内容时走 upsert，避免记忆无限膨胀
  CONSTRAINT uk_qa_memory_user_key UNIQUE (user_id, mem_key)
);
CREATE INDEX IF NOT EXISTS idx_qa_memory_inject ON qa_memory(user_id, status, importance);
