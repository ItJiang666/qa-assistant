-- =============================================================================
-- qa-assistant · MySQL 建表脚本（可选 profile：QA_PROFILES=mysql,local）
-- =============================================================================
-- 与 sql/schema-h2.sql 表结构完全一致，只是方言不同。
-- 本版享有 MySQL 专有特性：ENGINE / CHARSET / 内联 INDEX / ON UPDATE CURRENT_TIMESTAMP。
--
-- ⚠️ 业务代码不得依赖 ON UPDATE CURRENT_TIMESTAMP —— H2 版没有它。
--    所有 UPDATE 语句必须显式写 updated_at = CURRENT_TIMESTAMP（开发文档 4.1 硬规范）。
--
-- 脚本必须幂等：spring.sql.init.mode=always 会在每次启动时执行它。
-- =============================================================================

-- ── 知识库文档元数据 ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qa_document (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  file_name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_type       VARCHAR(32)  NOT NULL COMMENT '扩展名：pdf/docx/txt/md/xlsx/html',
  file_size       BIGINT       NOT NULL COMMENT '字节',
  file_path       VARCHAR(512) NOT NULL COMMENT '落盘路径',
  chunk_count     INT          NOT NULL DEFAULT 0 COMMENT '分块数',
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING/PARSING/INDEXED/FAILED',
  error_message   VARCHAR(1000) NULL COMMENT '失败原因',
  embedding_model VARCHAR(64)  NOT NULL COMMENT '入库时用的向量模型，换模型需重建',
  collection_name VARCHAR(128) NOT NULL COMMENT '所属向量集合',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                  ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_qa_document_status (status),
  INDEX idx_qa_document_collection (collection_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

-- ── 问答会话 ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qa_conversation (
  id         VARCHAR(64)  NOT NULL COMMENT '会话ID（前端生成UUID）',
  user_id    VARCHAR(64)  NOT NULL COMMENT '匿名用户标识（请求头 X-User-Id）',
  title      VARCHAR(255) NULL COMMENT '首问摘要',
  run_mode   VARCHAR(16)  NOT NULL COMMENT 'LOCAL / CLOUD',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
             ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_qa_conversation_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答会话';

-- ── 问答消息（同时充当 L2 会话记忆的持久化存储）──────────────────────────────
CREATE TABLE IF NOT EXISTS qa_message (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  conversation_id VARCHAR(64) NOT NULL,
  role            VARCHAR(16) NOT NULL COMMENT 'user / assistant / system / tool',
  content         MEDIUMTEXT  NOT NULL,
  sources_json    TEXT        NULL COMMENT '引用的来源片段(JSON)',
  token_usage     INT         NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_qa_message_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答消息';

-- ── 长期记忆（L3）────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qa_memory (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  user_id        VARCHAR(64)  NOT NULL COMMENT '归属人（匿名UUID）',
  memory_type    VARCHAR(16)  NOT NULL COMMENT 'PREFERENCE/FACT/CONCLUSION',
  mem_key        VARCHAR(128) NOT NULL COMMENT '归一化主题键，用于去重合并',
  content        VARCHAR(500) NOT NULL COMMENT '记忆正文，一句话',
  confidence     DECIMAL(3,2) NOT NULL DEFAULT 0.80 COMMENT '置信度',
  importance     TINYINT      NOT NULL DEFAULT 3 COMMENT '重要度 1~5',
  source_conv_id VARCHAR(64)  NULL COMMENT '来源会话',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
  hit_count      INT          NOT NULL DEFAULT 0 COMMENT '被注入次数',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                 ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 同一用户同一主题只允许一条，保证长期记忆不膨胀
  UNIQUE KEY uk_qa_memory_user_key (user_id, mem_key),
  INDEX idx_qa_memory_inject (user_id, status, importance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆';
