-- 测试库预置数据（Hibernate create-drop 模式下在建表后自动执行；SingleLineSqlCommandExtractor 逐行执行，必须单行一条）
INSERT INTO roles (id, name, create_time, update_time) VALUES (1, 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO roles (id, name, create_time, update_time) VALUES (2, 'MODERATOR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO roles (id, name, create_time, update_time) VALUES (3, 'ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
